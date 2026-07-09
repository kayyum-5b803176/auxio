/*
 * Copyright (c) 2026 Auxio Project
 * DuplicatesViewModel.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.plugin.similarity

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Drives the duplicate-detection screen: fingerprints the current library,
 * groups matches, and handles user-requested deletions.
 *
 * The scan only ever runs while the user is on the duplicates screen — no
 * background jobs are scheduled, honoring the plugin contract that the
 * feature has zero footprint unless explicitly used.
 */
@HiltViewModel
class DuplicatesViewModel
@Inject
constructor(
    private val musicRepository: MusicRepository,
    private val fingerprinter: AudioFingerprinter,
    private val fingerprintRepository: FingerprintRepository,
    private val duplicateFinder: DuplicateFinder,
    private val qualityAnalyzer: QualityAnalyzer,
    private val pluginSettings: PluginSettings,
    private val songDeleter: SongDeleter
) : ViewModel() {

    /** A duplicate group with its files ranked by true quality (best first). */
    data class RankedGroup(
        val ranked: List<QualityAnalyzer.Ranked>,
        val minSimilarity: Float,
        /**
         * True when a priority-folder file was kept despite a higher-quality
         * copy existing elsewhere — the UI shows a warning so the user can
         * override the automatic choice.
         */
        val keptLowerQualityWarning: Boolean = false
    )

    sealed interface ScanState {
        data object Idle : ScanState

        data class Scanning(val done: Int, val total: Int, val currentFile: String?) : ScanState

        data class Results(val groups: List<RankedGroup>) : ScanState
    }

    sealed interface DeleteEvent {
        data class Success(val song: Song) : DeleteEvent

        data class NeedsConsent(val song: Song, val pendingIntent: PendingIntent) : DeleteEvent

        data class Failure(val song: Song) : DeleteEvent
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    private val _deleteEvent = MutableStateFlow<DeleteEvent?>(null)
    val deleteEvent: StateFlow<DeleteEvent?> = _deleteEvent

    /**
     * Rolling log of recently processed files, newest first, shown live on the
     * scan page so the user can see progress at the file level. Capped to a
     * small window to stay light.
     */
    private val _processingLog = MutableStateFlow<List<String>>(emptyList())
    val processingLog: StateFlow<List<String>> = _processingLog

    private var scanJob: Job? = null

    /** Start a scan if one isn't already running or complete. */
    fun scanIfNeeded() {
        if (_scanState.value !is ScanState.Idle) return
        scan()
    }

    fun rescan() {
        scanJob?.cancel()
        scan()
    }

    private fun scan() {
        val songs = musicRepository.library?.songs?.toList().orEmpty()
        _processingLog.value = emptyList()
        if (songs.isEmpty()) {
            _scanState.value = ScanState.Results(emptyList())
            return
        }

        scanJob =
            viewModelScope.launch {
                _scanState.value = ScanState.Scanning(0, songs.size, null)
                // Drop cache rows for songs no longer in the library so the DB
                // doesn't grow without bound as the user's collection changes.
                fingerprintRepository.prune(songs)

                var done = 0
                // Bounded parallelism: decoding is I/O+codec heavy; a couple
                // of concurrent decodes saturate most devices without
                // starving the UI or the media session.
                val semaphore = Semaphore(CONCURRENT_DECODES)
                val results =
                    songs
                        .map { song ->
                            async {
                                // Cache first: a valid cached entry skips the
                                // expensive decode+FFT entirely. Only true
                                // misses (new song, changed file, or bumped
                                // algorithm version) fall through to analysis.
                                val cached = fingerprintRepository.getCached(song)
                                val result =
                                    if (cached != null) {
                                        cached
                                    } else {
                                        semaphore.withPermit {
                                            val computed =
                                                fingerprinter.fingerprint(
                                                    song.uri, song.durationMs)
                                            // Persist even an empty/failed result
                                            // so an unanalyzable file isn't retried
                                            // every scan.
                                            val toStore =
                                                computed
                                                    ?: FingerprintResult(
                                                        IntArray(0), FloatArray(0))
                                            fingerprintRepository.put(song, toStore)
                                            computed
                                        }
                                    }
                                done++
                                val fileName = song.path.name ?: song.uri.toString()
                                pushLog(fileName, cached != null)
                                _scanState.value =
                                    ScanState.Scanning(done, songs.size, fileName)
                                if (result != null && result.fingerprint.isNotEmpty()) {
                                    song to result
                                } else {
                                    null
                                }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                        .toMap()

                L.d("Analyzed ${results.size}/${songs.size} songs")
                val groups = duplicateFinder.find(results)

                // Priority folders: a snapshot of the user's configured names,
                // read once per scan. Files under these folders are preferred as
                // the "keep" and require an extra delete confirmation.
                val priorityMatcher = PriorityMatcher(pluginSettings.priorityFolderNames)

                // Rank each group by TRUE quality (QualityAnalyzer), with
                // priority-folder files preferred as the keep.
                val rankedGroups =
                    groups.map { group ->
                        val candidates =
                            group.songs.map { song ->
                                QualityAnalyzer.Candidate(
                                    song,
                                    results[song]?.spectralProfile,
                                    prioritized = priorityMatcher.isPrioritized(song))
                            }
                        val result = qualityAnalyzer.rank(candidates)
                        RankedGroup(
                            result.ranked,
                            group.minSimilarity,
                            result.keptLowerQualityWarning)
                    }
                _scanState.value = ScanState.Results(rankedGroups)
            }
    }

    private fun pushLog(fileName: String, cached: Boolean) {
        val entry = if (cached) "$fileName (cached)" else fileName
        _processingLog.value = (listOf(entry) + _processingLog.value).take(LOG_WINDOW)
    }

    fun delete(song: Song) {
        viewModelScope.launch {
            when (val result = songDeleter.delete(song)) {
                is SongDeleter.Result.Deleted -> {
                    removeSongFromResults(song)
                    _deleteEvent.value = DeleteEvent.Success(song)
                }
                is SongDeleter.Result.NeedsConsent ->
                    _deleteEvent.value = DeleteEvent.NeedsConsent(song, result.pendingIntent)
                is SongDeleter.Result.Failed -> _deleteEvent.value = DeleteEvent.Failure(song)
            }
        }
    }

    /** Called by the UI after a consent-based (MediaStore) deletion succeeded. */
    fun onConsentDeleteFinished(song: Song) {
        removeSongFromResults(song)
        _deleteEvent.value = DeleteEvent.Success(song)
    }

    fun consumeDeleteEvent() {
        _deleteEvent.value = null
    }

    private fun removeSongFromResults(song: Song) {
        val current = _scanState.value as? ScanState.Results ?: return
        val newGroups =
            current.groups.mapNotNull { group ->
                val remaining = group.ranked.filter { it.song != song }
                // A "group" of one is no longer a duplicate.
                if (remaining.size < 2) return@mapNotNull null
                // If we removed the recommended-keep file, promote the new best
                // (the next entry, already quality-ordered) as the keep.
                val fixed =
                    if (remaining.none { it.isRecommendedKeep }) {
                        remaining.mapIndexed { index, r -> r.copy(isRecommendedKeep = index == 0) }
                    } else {
                        remaining
                    }
                group.copy(ranked = fixed)
            }
        _scanState.value = ScanState.Results(newGroups)
    }

    private companion object {
        const val CONCURRENT_DECODES = 2
        const val LOG_WINDOW = 6
    }
}

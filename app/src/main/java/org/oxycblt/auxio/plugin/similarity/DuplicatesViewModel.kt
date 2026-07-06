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
    private val duplicateFinder: DuplicateFinder,
    private val songDeleter: SongDeleter
) : ViewModel() {

    sealed interface ScanState {
        data object Idle : ScanState

        data class Scanning(val done: Int, val total: Int) : ScanState

        data class Results(val groups: List<DuplicateFinder.DuplicateGroup>) : ScanState
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
        if (songs.isEmpty()) {
            _scanState.value = ScanState.Results(emptyList())
            return
        }

        scanJob =
            viewModelScope.launch {
                _scanState.value = ScanState.Scanning(0, songs.size)
                var done = 0
                // Bounded parallelism: decoding is I/O+codec heavy; a couple
                // of concurrent decodes saturate most devices without
                // starving the UI or the media session.
                val semaphore = Semaphore(CONCURRENT_DECODES)
                val fingerprints =
                    songs
                        .map { song ->
                            async {
                                semaphore.withPermit {
                                    val fp = fingerprinter.fingerprint(song.uri, song.durationMs)
                                    done++
                                    _scanState.value = ScanState.Scanning(done, songs.size)
                                    if (fp != null && fp.isNotEmpty()) song to fp else null
                                }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                        .toMap()

                L.d("Fingerprinted ${fingerprints.size}/${songs.size} songs")
                val groups = duplicateFinder.find(fingerprints)
                _scanState.value = ScanState.Results(groups)
            }
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
                val remaining = group.songs.filter { it != song }
                // A "group" of one is no longer a duplicate.
                if (remaining.size >= 2) group.copy(songs = remaining) else null
            }
        _scanState.value = ScanState.Results(newGroups)
    }

    private companion object {
        const val CONCURRENT_DECODES = 2
    }
}

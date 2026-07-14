/*
 * Copyright (c) 2026 Auxio Project
 * AcousticScanViewModel.kt is part of Auxio.
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
import timber.log.Timber as L

/**
 * Drives the Acoustic Scan screen: proactively computes an audio-grounded seed
 * embedding for every song in the library, independent of playback, so new /
 * never-played songs are placed by how they SOUND from the start. Structurally
 * identical to the Find Duplicates screen: progress is always shown against the
 * TRUE total library size (never the filtered "still need work" count), already-
 * seeded songs are still counted and logged (annotated "(cached)") rather than
 * excluded from the denominator, and decoding runs with bounded parallelism.
 */
@HiltViewModel
class AcousticScanViewModel
@Inject
constructor(
    private val musicRepository: MusicRepository,
    private val chainRepository: ChainRepository
) : ViewModel() {

    sealed interface ScanState {
        data object Idle : ScanState
        data class Scanning(val done: Int, val total: Int, val currentFile: String?) : ScanState
        data class Results(val seeded: Int, val cached: Int, val failed: Int, val total: Int) :
            ScanState
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    private val _processingLog = MutableStateFlow<List<String>>(emptyList())
    val processingLog: StateFlow<List<String>> = _processingLog

    private var scanJob: Job? = null

    fun scanIfNeeded() {
        if (_scanState.value !is ScanState.Idle) return
        scan(force = false)
    }

    fun rescan() {
        scanJob?.cancel()
        _scanState.value = ScanState.Idle
        _processingLog.value = emptyList()
        scan(force = true)
    }

    private fun scan(force: Boolean) {
        val songs = musicRepository.library?.songs?.toList().orEmpty()
        _processingLog.value = emptyList()
        if (songs.isEmpty()) {
            _scanState.value = ScanState.Results(0, 0, 0, 0)
            return
        }

        scanJob =
            viewModelScope.launch {
                _scanState.value = ScanState.Scanning(0, songs.size, null)

                var done = 0
                var seededCount = 0
                var cachedCount = 0
                var failedCount = 0
                // Bounded parallelism: decoding is I/O+codec heavy; a couple of
                // concurrent decodes saturate most devices without starving the
                // UI or the media session (matches Find Duplicates). The cached-
                // status CHECK itself runs for every song inside this same
                // concurrent loop (never as a sequential pre-pass over the whole
                // library first) — that pre-pass was the startup-lag bug: 230
                // sequential DB round trips before any progress could show.
                val semaphore = Semaphore(CONCURRENT_DECODES)
                songs
                    .map { song ->
                        async {
                            val fileName = song.path.name ?: song.uri.toString()
                            val isCached = !force && chainRepository.isAcousticSeeded(song)
                            val ok =
                                if (isCached) {
                                    true
                                } else {
                                    semaphore.withPermit {
                                        try {
                                            chainRepository.seedAcoustic(song)
                                        } catch (e: Exception) {
                                            L.e("AcousticScan: seed failed for $fileName: $e")
                                            false
                                        }
                                    }
                                }
                            done++
                            when {
                                isCached -> cachedCount++
                                ok -> seededCount++
                                else -> failedCount++
                            }
                            pushLog(fileName, isCached, ok)
                            _scanState.value = ScanState.Scanning(done, songs.size, fileName)
                        }
                    }
                    .awaitAll()

                _scanState.value =
                    ScanState.Results(seededCount, cachedCount, failedCount, songs.size)
            }
    }

    private fun pushLog(fileName: String, cached: Boolean, ok: Boolean) {
        val entry =
            when {
                cached -> "$fileName (cached)"
                !ok -> "$fileName (failed)"
                else -> fileName
            }
        _processingLog.value = (listOf(entry) + _processingLog.value).take(LOG_WINDOW)
    }

    override fun onCleared() {
        scanJob?.cancel()
    }

    private companion object {
        const val LOG_WINDOW = 6
        const val CONCURRENT_DECODES = 2
    }
}

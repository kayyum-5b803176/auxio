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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.music.MusicRepository
import timber.log.Timber as L

/**
 * Drives the Acoustic Scan screen: proactively computes an audio-grounded seed
 * embedding for every song in the library, independent of playback, so new /
 * never-played songs are placed by how they SOUND from the start. Mirrors the
 * Find Duplicates screen's progress + per-file log structure.
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
        data class Results(val seeded: Int, val failed: Int, val total: Int) : ScanState
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    private val _processingLog = MutableStateFlow<List<String>>(emptyList())
    val processingLog: StateFlow<List<String>> = _processingLog

    private var scanJob: Job? = null

    fun scanIfNeeded() {
        if (_scanState.value !is ScanState.Idle) return
        scan()
    }

    fun rescan() {
        scanJob?.cancel()
        _scanState.value = ScanState.Idle
        _processingLog.value = emptyList()
        scan(force = true)
    }

    private fun scan(force: Boolean = false) {
        scanJob =
            viewModelScope.launch {
                val all = musicRepository.library?.songs?.toList().orEmpty()
                // Skip songs already acoustically seeded (cache across opens),
                // unless the user forced a full rescan.
                val songs = if (force) all else chainRepository.unseededAcoustic(all)
                val total = songs.size
                if (total == 0) {
                    // Nothing to do — either empty library or everything cached.
                    _scanState.value = ScanState.Results(0, 0, all.size)
                    return@launch
                }
                var seeded = 0
                var failed = 0
                for ((i, song) in songs.withIndex()) {
                    val name = song.path.name ?: song.uri.toString()
                    _scanState.value = ScanState.Scanning(i, total, name)
                    val ok =
                        try {
                            chainRepository.seedAcoustic(song)
                        } catch (e: Exception) {
                            L.e("AcousticScan: seed failed for $name: $e")
                            false
                        }
                    if (ok) seeded++ else failed++
                    appendLog(if (ok) "✓ $name" else "✗ $name (no acoustic seed)")
                }
                _scanState.value = ScanState.Results(seeded, failed, total)
            }
    }

    private fun appendLog(line: String) {
        val cur = _processingLog.value
        val next = (cur + line)
        _processingLog.value = if (next.size > LOG_CAP) next.takeLast(LOG_CAP) else next
    }

    override fun onCleared() {
        scanJob?.cancel()
    }

    private companion object {
        const val LOG_CAP = 200
    }
}

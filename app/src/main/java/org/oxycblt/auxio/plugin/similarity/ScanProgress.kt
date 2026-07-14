/*
 * Copyright (c) 2026 Auxio Project
 * ScanProgress.kt is part of Auxio.
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

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide, observable progress for a long-running foreground scan (acoustic
 * seeding or duplicate fingerprinting). The foreground SERVICE owns the work and
 * WRITES here; the Fragment/ViewModel only OBSERVES. This decouples the work's
 * lifetime from any UI: leaving the screen or turning it off no longer cancels
 * the scan (the service keeps running), and reopening the screen shows the real
 * current progress immediately instead of restarting from zero.
 *
 * Two independent singleton instances are provided (one per scan) so the two
 * features stay fully separate, matching their independent services.
 */
open class ScanProgress {
    sealed interface State {
        /** No scan running and none has completed this process lifetime. */
        data object Idle : State
        /** A scan is actively running. [currentFile] is the most recent item. */
        data class Running(
            val done: Int,
            val total: Int,
            val currentFile: String?,
            val log: List<String>
        ) : State
        /** The last scan finished (or was stopped) with these tallies. */
        data class Done(
            val processed: Int,
            val cached: Int,
            val failed: Int,
            val total: Int,
            val log: List<String>,
            val stopped: Boolean
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun set(state: State) {
        _state.value = state
    }

    fun reset() {
        _state.value = State.Idle
    }

    /** True if a scan is currently running (used to avoid double-starting). */
    val isRunning: Boolean
        get() = _state.value is State.Running
}

/** Hilt: one shared instance for the acoustic scan. */
@Singleton
class AcousticScanProgress @Inject constructor() : ScanProgress()

/** Hilt: one shared instance for the duplicate scan. */
@Singleton
class DuplicateScanProgress @Inject constructor() : ScanProgress()

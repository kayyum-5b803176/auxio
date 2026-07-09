/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackTracker.kt is part of Auxio.
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Observes playback and learns the behavioral chain: when one song is followed
 * by another, it records that transition weighted by how much of the following
 * song was actually listened to.
 *
 * Strictly gated by the Smart Chain plugin flag — when disabled, [onSongChanged]
 * returns immediately and nothing is tracked, observed, or stored, preserving
 * Auxio's stock behavior exactly.
 *
 * Integration: [PlaybackServiceFragment] forwards the relevant
 * [PlaybackStateManager.Listener] callbacks here. We don't register our own
 * listener, to avoid a second app-scoped observer and keep lifecycle ownership
 * with the service.
 */
class PlaybackTracker
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val chainRepository: ChainRepository,
    private val pluginSettings: PluginSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The song currently playing, and the furthest position (ms) we've observed
    // within it. Updated continuously from progression callbacks so that, at
    // the moment the song changes, we know how much of the OUTGOING song was
    // actually heard — the completion weight for the transition.
    private var currentSong: Song? = null
    private var lastPositionMs: Long = 0L

    /**
     * Forwarded from progression updates. Keeps [lastPositionMs] current for
     * the playing song so we can measure how far it got when it ends.
     */
    fun onProgression() {
        if (!pluginSettings.smartChainEnabled) return
        val song = playbackManager.currentSong ?: return
        if (song == currentSong) {
            lastPositionMs = playbackManager.progression.calculateElapsedPositionMs()
        }
    }

    /**
     * Called whenever the active song may have changed. Computes the
     * listened-fraction of the song that just ended (from the last observed
     * position within it) and records the transition into the one that started.
     */
    fun onSongChanged() {
        if (!pluginSettings.smartChainEnabled) {
            currentSong = null
            lastPositionMs = 0L
            return
        }

        val newSong = playbackManager.currentSong

        val previous = currentSong
        if (previous != null && newSong != null && previous != newSong) {
            // Fraction of the outgoing song actually heard, from the furthest
            // position we observed within it. Early skip -> small; full play ->
            // ~1.0 (auto-advance fires the change at/near the end).
            val fraction =
                if (previous.durationMs > 0) {
                    (lastPositionMs.toFloat() / previous.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
            val from = previous
            val to = newSong
            scope.launch {
                try {
                    chainRepository.recordTransition(from, to, fraction)
                } catch (e: Exception) {
                    L.w("Failed to record chain transition: $e")
                }
            }
        }

        currentSong = newSong
        // Reset observed position for the new song; progression callbacks will
        // advance it.
        lastPositionMs = playbackManager.progression.calculateElapsedPositionMs()
    }

    /** Reset tracking state (e.g. session ended). */
    fun reset() {
        currentSong = null
        lastPositionMs = 0L
    }
}

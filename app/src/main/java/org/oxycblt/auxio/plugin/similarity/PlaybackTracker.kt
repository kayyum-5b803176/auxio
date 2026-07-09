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
import javax.inject.Singleton
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
 * MUST be a @Singleton: the playback service forwards song-change/progression
 * callbacks here, while PlaybackViewModel forwards the user's explicit
 * next/prev intent here. Both must reach the SAME instance — otherwise the
 * intent and the song-change never meet and nothing is recorded.
 */
@Singleton
class PlaybackTracker
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val chainRepository: ChainRepository,
    private val pluginSettings: PluginSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        L.d("SmartChain: PlaybackTracker constructed (instance=${hashCode()})")
    }

    // The song currently playing, and the furthest position (ms) we've observed
    // within it. Updated continuously from progression callbacks so that, at
    // the moment the song changes, we know how much of the OUTGOING song was
    // actually heard — the completion weight for the transition.
    private var currentSong: Song? = null
    private var lastPositionMs: Long = 0L

    // The user's most recent explicit intent, and when it happened, so the next
    // song-change can be interpreted correctly:
    //  - NEXT tapped  -> the outgoing song was manually skipped (rejection,
    //                    strength scaled down by how little was heard).
    //  - PREV tapped  -> a deliberate jump back to replay (strong positive for
    //                    the song jumped TO).
    //  - null / stale -> a natural auto-advance (completion).
    private enum class Intent { NEXT, PREV }
    private var lastIntent: Intent? = null
    private var lastIntentAtMs: Long = 0L

    /**
     * Fed the live playback position (ms) roughly every 100ms while a song
     * plays, so the listened-fraction computed at a song change is accurate.
     * The progression state-change callback alone is too infrequent.
     */
    fun onPositionTick(positionMs: Long) {
        if (!pluginSettings.smartChainEnabled) return
        if (playbackManager.currentSong == currentSong) {
            lastPositionMs = positionMs
        }
    }

    /** Called when the user explicitly taps "next". */
    fun onUserNext() {
        L.d("SmartChain: onUserNext (enabled=${pluginSettings.smartChainEnabled})")
        if (!pluginSettings.smartChainEnabled) return
        lastIntent = Intent.NEXT
        lastIntentAtMs = System.currentTimeMillis()
    }

    /** Called when the user explicitly taps "previous". */
    fun onUserPrev() {
        L.d("SmartChain: onUserPrev (enabled=${pluginSettings.smartChainEnabled})")
        if (!pluginSettings.smartChainEnabled) return
        lastIntent = Intent.PREV
        lastIntentAtMs = System.currentTimeMillis()
    }

    private fun consumeIntent(): Intent? {
        val intent = lastIntent ?: return null
        val fresh = (System.currentTimeMillis() - lastIntentAtMs) <= INTENT_WINDOW_MS
        lastIntent = null
        return if (fresh) intent else null
    }

    /**
     * Forwarded from progression updates. Keeps [lastPositionMs] current for
     * the playing song so we can measure how far it got when it ends.
     */
    fun onProgression() {
        if (!pluginSettings.smartChainEnabled) {
            L.d("SmartChain: onProgression ignored (disabled)")
            return
        }
        val song = playbackManager.currentSong
        if (song == null) {
            L.d("SmartChain: onProgression — currentSong is null")
            return
        }
        if (song == currentSong) {
            lastPositionMs = playbackManager.progression.calculateElapsedPositionMs()
        } else {
            L.d("SmartChain: onProgression detected song change to ${song.path.name}")
            handleSongChange(song)
        }
    }

    /**
     * Called whenever the active song may have changed (skip, auto-advance, new
     * playback). Delegates to [handleSongChange], which is idempotent for the
     * same song so it's safe to call from multiple callbacks.
     */
    fun onSongChanged() {
        L.d("SmartChain: onSongChanged called (enabled=${pluginSettings.smartChainEnabled})")
        if (!pluginSettings.smartChainEnabled) {
            currentSong = null
            lastPositionMs = 0L
            return
        }
        val newSong = playbackManager.currentSong
        if (newSong == null) {
            L.d("SmartChain: onSongChanged — currentSong is null, skipping")
            return
        }
        handleSongChange(newSong)
    }

    private fun handleSongChange(newSong: Song) {
        val previous = currentSong
        L.d(
            "SmartChain: handleSongChange prev=${previous?.path?.name} " +
                "new=${newSong.path.name} lastPos=${lastPositionMs}ms")
        // Same song still playing — nothing changed; just refresh position.
        if (previous == newSong) {
            lastPositionMs = playbackManager.progression.calculateElapsedPositionMs()
            return
        }

        val intent = consumeIntent()
        L.d("SmartChain: transition ${previous?.path?.name} -> ${newSong.path.name}, intent=$intent")
        if (previous != null) {
            val heardFraction =
                if (previous.durationMs > 0) {
                    (lastPositionMs.toFloat() / previous.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }

            when (intent) {
                Intent.PREV -> {
                    // Jump BACK: the user returned to replay a song — a strong
                    // "I like this" signal for newSong. But the direction is
                    // BACKWARD (newSong usually precedes `previous`), so
                    // recording previous→newSong would pollute the chain with a
                    // reversed link. Phase 1 stores only transitions, not
                    // per-song scores, so we log the positive signal for
                    // visibility but do not write a misleading backward edge.
                    // (A per-song "liked" score is a natural future addition.)
                    logJumpBack(newSong)
                }
                Intent.NEXT -> {
                    // Manual skip: the user actively moved on. This is a
                    // rejection of the outgoing song as a follow — recorded
                    // with the (low) heard fraction, so an early skip yields a
                    // strong negative and a late skip a mild one.
                    recordAsync(previous, newSong, heardFraction, "Skip")
                }
                null -> {
                    // Natural auto-advance: the song was allowed to finish, a
                    // genuine completion. Use the heard fraction (≈1.0).
                    recordAsync(previous, newSong, heardFraction, "Play")
                }
            }
        }

        currentSong = newSong
        lastPositionMs = playbackManager.progression.calculateElapsedPositionMs()
    }

    private fun recordAsync(from: Song, to: Song, fraction: Float, kind: String) {
        L.d("SmartChain: recordAsync $kind ${from.path.name} -> ${to.path.name} frac=$fraction")
        scope.launch {
            try {
                chainRepository.recordTransition(from, to, fraction, kind)
            } catch (e: Exception) {
                L.e("SmartChain: recordTransition FAILED: $e")
            }
        }
    }

    private fun logJumpBack(replayed: Song) {
        scope.launch {
            try {
                chainRepository.logJumpBack(replayed)
            } catch (e: Exception) {
                L.w("Failed to log jump-back: $e")
            }
        }
    }

    /** Reset tracking state (e.g. session ended). */
    fun reset() {
        currentSong = null
        lastPositionMs = 0L
        lastIntent = null
    }

    private companion object {
        // How long after a next/prev tap the intent is still considered the
        // cause of the upcoming song change. Comfortably longer than the gap
        // between the tap and the resulting callback, but short enough that a
        // stale intent from a minute ago doesn't mislabel a later auto-advance.
        const val INTENT_WINDOW_MS = 5_000L
    }
}

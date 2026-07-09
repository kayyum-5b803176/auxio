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

    // Bug-2 fix (deferred edge scoring): the A->B edge must be judged by how
    // much of B actually played, which is only known when B itself ends. So we
    // remember the song that preceded the current one; when the current song
    // ends we finalize `pendingFrom -> currentSong` using the CURRENT song's
    // heard fraction (not the outgoing-of-outgoing song's).
    private var pendingFrom: Song? = null

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
            // How much of the OUTGOING song (`previous`) was actually heard.
            val previousHeard =
                if (previous.durationMs > 0) {
                    (lastPositionMs.toFloat() / previous.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }

            when (intent) {
                Intent.PREV -> {
                    // Bug-1 fix: a jump back only counts as a "like" for the song
                    // returned to if the user had genuinely been listening to the
                    // current song first — not an instant reflex tap. It's a
                    // signal about the SONG (node), never a backward edge.
                    if (previousHeard >= JUMP_BACK_MIN_HEARD) {
                        recordLikeAsync(newSong, previousHeard)
                    } else {
                        L.d("SmartChain: jump-back ignored (only heard ${(previousHeard*100).toInt()}% first)")
                    }
                    // A PREV also abandons any pending forward edge — the user
                    // didn't move forward, so there's no A->prev edge to finalize.
                    pendingFrom = null
                }
                else -> {
                    // Forward move (manual skip or natural advance).
                    //
                    // Bug-2 fix: finalize the PENDING edge (pendingFrom ->
                    // previous) now, using `previous`'s own heard fraction — this
                    // is the correct measure of "was `previous` a good follow of
                    // pendingFrom". This is the edge that was opened when we
                    // started playing `previous`.
                    val from = pendingFrom
                    if (from != null && from != previous) {
                        val kind = if (previousHeard < SKIP_HEARD) "Skip" else "Play"
                        recordEdgeAsync(from, previous, previousHeard, kind)
                    }
                    // Also update `previous`'s own node score by how much of it
                    // was heard (independent of context).
                    recordPlayAsync(previous, previousHeard)

                    // Open the next pending edge: previous -> newSong, to be
                    // finalized when newSong ends and we know its heard fraction.
                    pendingFrom = previous
                }
            }
        } else {
            // First song of the session: nothing precedes it, so just open the
            // pending slot when the NEXT song arrives (handled above next time).
            pendingFrom = null
        }

        currentSong = newSong
        lastPositionMs = playbackManager.progression.calculateElapsedPositionMs()
    }

    private fun recordEdgeAsync(from: Song, to: Song, fraction: Float, kind: String) {
        L.d("SmartChain: recordEdge $kind ${from.path.name} -> ${to.path.name} frac=$fraction")
        scope.launch {
            try {
                chainRepository.recordTransition(from, to, fraction, kind)
            } catch (e: Exception) {
                L.e("SmartChain: recordTransition FAILED: $e")
            }
        }
    }

    private fun recordPlayAsync(song: Song, fraction: Float) {
        scope.launch {
            try {
                chainRepository.recordSongPlay(song, fraction)
            } catch (e: Exception) {
                L.w("SmartChain: recordSongPlay failed: $e")
            }
        }
    }

    private fun recordLikeAsync(song: Song, contextHeard: Float) {
        scope.launch {
            try {
                chainRepository.recordJumpBack(song, contextHeard)
            } catch (e: Exception) {
                L.w("SmartChain: recordJumpBack failed: $e")
            }
        }
    }

    /** Reset tracking state (e.g. session ended). */
    fun reset() {
        currentSong = null
        lastPositionMs = 0L
        lastIntent = null
        pendingFrom = null
    }

    private companion object {
        // How long after a next/prev tap the intent is still considered the
        // cause of the upcoming song change. Comfortably longer than the gap
        // between the tap and the resulting callback, but short enough that a
        // stale intent from a minute ago doesn't mislabel a later auto-advance.
        const val INTENT_WINDOW_MS = 5_000L

        // A forward move counts as a real skip (vs a near-complete play) when
        // less than this fraction of the outgoing song was heard.
        const val SKIP_HEARD = 0.25f

        // A jump-back only counts as a "like" for the returned-to song if at
        // least this much of the song being abandoned had played first — so an
        // instant reflex prev-tap doesn't register as a like.
        const val JUMP_BACK_MIN_HEARD = 0.15f
    }
}

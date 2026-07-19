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

    // Zero-drift "actually heard" measurement (Media3-driven). Real audio time
    // is accumulated from WALL CLOCK, but only while the player reports
    // isPlaying == true. isPlaying is true only when the position is genuinely
    // advancing (not paused, buffering, seeking, or ended), so seeks contribute
    // nothing by construction — the position jump never enters the math at all.
    // `playedMs` is the real listened duration for the current song.
    private var playedMs: Long = 0L
    private var playingSinceMs: Long = -1L

    // Bug-2 fix (deferred edge scoring): the A->B edge must be judged by how
    // much of B actually played, which is only known when B itself ends. So we
    // remember the song that preceded the current one; when the current song
    // ends we finalize `pendingFrom -> currentSong` using the CURRENT song's
    // heard fraction (not the outgoing-of-outgoing song's).
    private var pendingFrom: Song? = null
    private var pendingFromHeard: Float = 0f

    // Skip-over ("pass") linking: the last GENUINELY-LISTENED song and how much
    // of it was heard. When the next genuinely-listened song arrives, we form a
    // full-strength "pass" edge lastListened -> thatSong ACROSS any intervening
    // skipped songs (A -> D across skipped B, C). This is separate from the
    // immediate-neighbour "skip" edges: both happen at once. Zone/bias applies
    // to pass edges (real listened links) but NOT to skip edges (pure rejection).
    private var lastListened: Song? = null
    private var lastListenedHeard: Float = 0f

    // Whether the CURRENT song was deliberately chosen by the user (tapped in
    // the library/queue) rather than arriving by auto-advance or skip. A
    // user-chosen follow that is then actually listened to is the strongest
    // possible "B goes after A" signal, and earns a bonus when the pending edge
    // is finalized.
    private var currentWasSelected: Boolean = false

    // The user's most recent explicit intent, and when it happened, so the next
    // song-change can be interpreted correctly:
    //  - NEXT tapped   -> the outgoing song was manually skipped.
    //  - PREV tapped   -> a deliberate jump back to replay.
    //  - SELECT tapped -> the user chose a specific song to play now.
    //  - null / stale  -> a natural auto-advance (completion).
    private enum class Intent { NEXT, PREV, SELECT }
    private var lastIntent: Intent? = null
    private var lastIntentAtMs: Long = 0L

    /**
     * Fed the live playback position (ms) roughly every 100ms while a song
     * plays, so the listened-fraction computed at a song change is accurate.
     * The progression state-change callback alone is too infrequent.
     */
    /**
     * Whether the tracker actually needs high-resolution (100ms) position
     * ticks. When Smart Chain is disabled every tick is a no-op, so the
     * caller's polling loop can slow down and save wakeups/CPU.
     */
    val needsPositionTicks: Boolean
        get() = pluginSettings.smartChainEnabled

    fun onPositionTick(positionMs: Long) {
        if (!pluginSettings.smartChainEnabled) return
        if (playbackManager.currentSong == currentSong) {
            lastPositionMs = positionMs
        }
    }

    /** Fold any in-flight playing interval into playedMs and clear the marker. */
    private fun foldPlayingInterval() {
        if (playingSinceMs >= 0) {
            val delta = System.currentTimeMillis() - playingSinceMs
            if (delta > 0) playedMs += delta
            playingSinceMs = -1L
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

    /**
     * Called when the user deliberately picks a specific song to play (tapping
     * it in the library, a detail screen, or the queue). The strongest "play
     * this now" signal — the resulting edge gets a bonus if the chosen song is
     * then actually listened to.
     */
    fun onUserSelect() {
        L.d("SmartChain: onUserSelect (enabled=${pluginSettings.smartChainEnabled})")
        if (!pluginSettings.smartChainEnabled) return
        lastIntent = Intent.SELECT
        lastIntentAtMs = System.currentTimeMillis()
    }

    private fun consumeIntent(): Intent? {
        val intent = lastIntent ?: return null
        val fresh = (System.currentTimeMillis() - lastIntentAtMs) <= INTENT_WINDOW_MS
        lastIntent = null
        return if (fresh) intent else null
    }

    /**
     * Forwarded from progression updates. Keeps [lastPositionMs] current and
     * drives the wall-clock played-time accumulator off the isPlaying edge:
     * time accrues only while the player is genuinely playing, so seeks and
     * pauses never inflate it.
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
            syncPlaying(playbackManager.progression.isPlaying)
            lastPositionMs = playbackManager.progression.calculateElapsedPositionMs()
        } else {
            L.d("SmartChain: onProgression detected song change to ${song.path.name}")
            handleSongChange(song)
        }
    }

    /**
     * Reconcile the accumulator with the current isPlaying state. Opens an
     * interval on the not-playing -> playing edge; folds it on the reverse.
     * Idempotent, so it's safe to call on every progression tick.
     */
    private fun syncPlaying(isPlaying: Boolean) {
        if (isPlaying) {
            if (playingSinceMs < 0) playingSinceMs = System.currentTimeMillis()
        } else {
            foldPlayingInterval()
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
            playedMs = 0L
            playingSinceMs = -1L
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

        // The outgoing song was playing right up to this transition; fold the
        // in-flight interval so playedMs is complete before we measure it.
        foldPlayingInterval()

        if (previous != null) {
            // How much of the OUTGOING song (`previous`) was actually HEARD —
            // real played audio (playedMs, wall-clock while isPlaying), NOT the
            // raw playhead. Seeking never inflates this.
            val previousHeard =
                if (previous.durationMs > 0) {
                    (playedMs.toFloat() / previous.durationMs).coerceIn(0f, 1f)
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
                    pendingFromHeard = 0f
                    lastListened = null
                    lastListenedHeard = 0f
                    currentWasSelected = false
                }
                else -> {
                    // Forward move (manual skip, natural advance, or selection).
                    //
                    // TWO things can happen on the same songs at once:
                    //  (1) an ADJACENT edge from the immediately-preceding song to
                    //      this one, and
                    //  (2) when this song was genuinely listened, a SKIP-OVER
                    //      "pass" edge from the last listened song to this one,
                    //      across any intervening skipped songs.
                    //
                    // Adjacent edge kind & magnitude:
                    //  - both endpoints listened (>= SKIP_HEARD) -> Play/Select,
                    //    full weight, zone/bias applies.
                    //  - either endpoint short -> Skip: PURE rejection (no
                    //    zone/bias). Magnitude is MAJOR if the SOURCE was itself
                    //    well-heard (a confident judgment), MINOR if the source was
                    //    itself a skip (low-confidence chained skip).
                    val srcListened = pendingFrom?.let { pf -> pendingFromHeard >= SKIP_HEARD } ?: false
                    val destListened = previousHeard >= SKIP_HEARD
                    val from = pendingFrom
                    if (from != null && from != previous) {
                        if (srcListened && destListened) {
                            val kind = if (currentWasSelected) "Select" else "Play"
                            recordEdgeAsync(
                                from, previous, pendingFromHeard, previousHeard, kind, 1f, true)
                        } else {
                            // Skip: pure rejection, magnitude by source confidence.
                            val weight = if (srcListened) SKIP_MAJOR_WEIGHT else SKIP_MINOR_WEIGHT
                            recordEdgeAsync(
                                from, previous, pendingFromHeard, previousHeard, "Skip", weight, false)
                        }
                    }

                    // Skip-over pass edge: last listened -> this song (if this song
                    // was genuinely listened). Full strength, zone/bias applies.
                    if (destListened) {
                        val ll = lastListened
                        if (ll != null && ll != previous && ll != from) {
                            recordEdgeAsync(
                                ll, previous, lastListenedHeard, previousHeard, "Pass", 1f, true)
                        }
                        lastListened = previous
                        lastListenedHeard = previousHeard
                    }

                    // Node score for the outgoing song by its own heard fraction.
                    recordPlayAsync(previous, previousHeard)

                    // Advance the immediate-neighbour anchor.
                    pendingFrom = previous
                    pendingFromHeard = previousHeard
                    currentWasSelected = intent == Intent.SELECT
                }
            }
        } else {
            // First song of the session: nothing precedes it. Seed the anchors
            // so the next transition can link from it if it gets listened.
            pendingFrom = null
            pendingFromHeard = 0f
            lastListened = null
            lastListenedHeard = 0f
            currentWasSelected = intent == Intent.SELECT
        }

        currentSong = newSong
        lastPositionMs = playbackManager.progression.calculateElapsedPositionMs()
        // Reset the actual-played accumulator for the new song. If it's already
        // playing at this instant, open a fresh interval so time counts from now.
        playedMs = 0L
        playingSinceMs =
            if (playbackManager.progression.isPlaying) System.currentTimeMillis() else -1L
    }

    private fun recordEdgeAsync(
        from: Song,
        to: Song,
        fromHeard: Float,
        toHeard: Float,
        kind: String,
        weight: Float,
        applyZone: Boolean
    ) {
        L.d("SmartChain: recordEdge $kind ${from.path.name} -> ${to.path.name} " +
            "src=$fromHeard dest=$toHeard w=$weight zone=$applyZone")
        scope.launch {
            try {
                chainRepository.recordTransition(from, to, fromHeard, toHeard, kind, weight, applyZone)
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
        playedMs = 0L
        playingSinceMs = -1L
        lastIntent = null
        pendingFrom = null
        pendingFromHeard = 0f
        lastListened = null
        lastListenedHeard = 0f
        currentWasSelected = false
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

        // Skip edge weights (pure rejection, no zone/bias). MAJOR when the source
        // song was itself well-heard (a confident judgment); MINOR when the source
        // was itself a skip (low-confidence chained skip — counts, but weakly).
        const val SKIP_MAJOR_WEIGHT = 1.0f
        const val SKIP_MINOR_WEIGHT = 0.1f
    }
}

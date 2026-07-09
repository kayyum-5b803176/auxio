/*
 * Copyright (c) 2026 Auxio Project
 * ChainRepository.kt is part of Auxio.
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
import kotlin.random.Random
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Records and queries the behavioral chain. Resolves songs to fingerprint-based
 * keys (via the fingerprint cache + [ChainKey]) so all learning is owned by the
 * music, not the file.
 */
interface ChainRepository {
    /**
     * Learn a transition: [from] was playing, then [to] played and was listened
     * to for [listenedFraction] (0f = skipped instantly, 1f = played fully).
     * The link strength is weighted by that fraction, so good follows
     * accumulate strength and early-skipped follows barely register.
     */
    suspend fun recordTransition(from: Song, to: Song, listenedFraction: Float, kind: String)

    /**
     * Note a jump-back (user tapped previous to replay [replayed]). A strong
     * positive signal for that song. Phase 1 records it in the log for
     * visibility; it does not yet feed a per-song score.
     */
    /**
     * Fold a play observation into the song's own (node) score: [listenedFraction]
     * near 1.0 is a positive signal, an early skip a negative one. Independent of
     * whatever played before it.
     */
    suspend fun recordSongPlay(song: Song, listenedFraction: Float)

    /**
     * Record a jump-back "like" for [song] — the user deliberately returned to
     * replay it. Feeds the song's node score, not any edge. [contextHeard] is how
     * much of the abandoned song had played (for the log line only).
     */
    suspend fun recordJumpBack(song: Song, contextHeard: Float)

    /**
     * Proven followers of [song], strongest first — the pool manual play picks
     * from. Empty if nothing has been learned yet (caller falls back).
     */
    suspend fun provenFollowers(song: Song): List<FollowerSong>

    /**
     * Produce a chain-aware ordering of [heap] (by heap index), starting at
     * [startHeapIndex], for the player's shuffle order.
     *
     * The walk greedily follows the strongest proven follower of the current
     * song that hasn't been placed yet. When a song has no unused proven
     * follower, it falls back to a random unused song.
     *
     * @param explore When true (shuffle mode — exploit + explore), each step has
     *   a probability of picking a random unused song instead of the top proven
     *   follower, so new pairings get discovered. When false (normal play —
     *   exploit only), proven followers are always preferred; randomness only
     *   fills gaps.
     * @return A permutation of `heap.indices` beginning with [startHeapIndex].
     *   The identity order (0..n-1 rotated to start) if the chain is empty.
     */
    suspend fun chainOrdering(
        heap: List<Song>,
        startHeapIndex: Int,
        explore: Boolean
    ): IntArray

    /** Clear all learned chain data. */
    suspend fun clear()

    /** A learned follower key with its strength (resolved to a real Song by the caller). */
    data class Follower(val toKey: String, val strength: Float)

    /** A follower already resolved to a concrete Song in the current library. */
    data class FollowerSong(val song: Song, val strength: Float)
}

class ChainRepositoryImpl
@Inject
constructor(
    private val dao: ChainDao,
    private val nodeDao: ChainNodeDao,
    private val fingerprintRepository: FingerprintRepository,
    private val musicRepository: org.oxycblt.auxio.music.MusicRepository,
    private val chainLog: ChainLog
) : ChainRepository {

    private suspend fun keyOf(song: Song): String? {
        // Prefer a fingerprint-based key (survives duplicate-deletion and format
        // changes). Fall back to the song's UID when no fingerprint is cached
        // yet (e.g. the user hasn't run a duplicate scan) so the chain still
        // works immediately — it just won't share nodes across duplicate files
        // until they've been fingerprinted.
        val result = fingerprintRepository.getCached(song)
        val fpKey = result?.let { ChainKey.of(it.fingerprint) }
        if (fpKey != null) return fpKey
        return "uid:" + song.uid.toString()
    }

    override suspend fun recordTransition(
        from: Song,
        to: Song,
        listenedFraction: Float,
        kind: String
    ) {
        val fromKey = keyOf(from)
        val toKey = keyOf(to)
        L.d("SmartChain: recordTransition fromKey=$fromKey toKey=$toKey kind=$kind")
        if (fromKey == null || toKey == null) {
            L.w("SmartChain: null key, aborting")
            return
        }
        if (fromKey == toKey) {
            L.d("SmartChain: same key (same recording), not chaining to self")
            return
        }

        // Completion-weighted strength delta. An early skip contributes a small
        // NEGATIVE amount (mild discouragement), a full listen a full positive.
        val delta = (listenedFraction - SKIP_THRESHOLD) * STRENGTH_SCALE

        val updated = dao.reinforce(fromKey, toKey, delta)
        if (updated == 0) {
            dao.insert(
                ChainTransition(
                    fromKey = fromKey,
                    toKey = toKey,
                    strength = delta.coerceAtLeast(0f),
                    count = 1))
        }
        val pct = (listenedFraction * 100).toInt()
        val sign = if (delta >= 0) "+" else ""
        val keyKind = if (fromKey.startsWith("uid:")) " [uid]" else ""
        val line =
            "$kind: ${nameOf(from)} → ${nameOf(to)}  " +
                "(heard $pct%, link $sign${"%.2f".format(delta)})$keyKind"
        L.d("SmartChain: writing log line -> $line")
        chainLog.log(line)
    }

    private fun nameOf(song: Song): String = song.path.name ?: song.uri.toString()

    override suspend fun recordSongPlay(song: Song, listenedFraction: Float) {
        val key = keyOf(song) ?: return
        // A completion contributes positively; an early skip negatively. Scaled
        // the same way as edges for consistency.
        val delta = (listenedFraction - SKIP_THRESHOLD) * STRENGTH_SCALE
        val isSkip = listenedFraction < SKIP_THRESHOLD
        foldNode(
            key = key,
            posDelta = if (delta > 0) delta else 0f,
            negDelta = if (delta < 0) -delta else 0f,
            playInc = if (isSkip) 0 else 1,
            skipInc = if (isSkip) 1 else 0,
            likeInc = 0)
    }

    override suspend fun recordJumpBack(song: Song, contextHeard: Float) {
        val key = keyOf(song) ?: return
        foldNode(
            key = key,
            posDelta = JUMP_BACK_BONUS,
            negDelta = 0f,
            playInc = 0,
            skipInc = 0,
            likeInc = 1)
        val keyKind = if (key.startsWith("uid:")) " [uid]" else ""
        chainLog.log("Jump-back ♥ ${nameOf(song)} (replayed — +$JUMP_BACK_BONUS like)$keyKind")
    }

    /** Fold a node observation, inserting the row first if it doesn't exist. */
    private suspend fun foldNode(
        key: String,
        posDelta: Float,
        negDelta: Float,
        playInc: Int,
        skipInc: Int,
        likeInc: Int
    ) {
        val now = System.currentTimeMillis()
        val updated = nodeDao.fold(key, posDelta, negDelta, playInc, skipInc, likeInc, now)
        if (updated == 0) {
            nodeDao.insert(
                ChainSongScore(
                    key = key,
                    positiveScore = posDelta,
                    negativeScore = negDelta,
                    playCount = playInc,
                    skipCount = skipInc,
                    jumpBackCount = likeInc,
                    lastUpdatedMs = now))
        }
    }

    override suspend fun provenFollowers(song: Song): List<ChainRepository.FollowerSong> {
        val fromKey = keyOf(song) ?: return emptyList()
        val followers = dao.followersOf(fromKey).filter { it.strength > 0f }
        if (followers.isEmpty()) return emptyList()

        // Resolve follower keys back to concrete songs in the CURRENT library.
        // A key may resolve to multiple files (duplicates); pick any present
        // one. Keys with no present file are silently dropped (the music was
        // removed) — its learning is retained in the DB for if it returns.
        val library = musicRepository.library ?: return emptyList()
        val byKey = mutableMapOf<String, Song>()
        for (s in library.songs) {
            val k = keyOf(s) ?: continue
            byKey.putIfAbsent(k, s)
        }
        return followers.mapNotNull { t ->
            byKey[t.toKey]?.let { ChainRepository.FollowerSong(it, t.strength) }
        }
    }

    override suspend fun chainOrdering(
        heap: List<Song>,
        startHeapIndex: Int,
        explore: Boolean
    ): IntArray {
        val n = heap.size
        if (n <= 1) return IntArray(n) { it }
        val start = startHeapIndex.coerceIn(0, n - 1)

        // Resolve every heap song to its chain key once. Map key -> list of heap
        // indices sharing that key (duplicates collapse to one key).
        val keyOfIndex = arrayOfNulls<String>(n)
        val indicesByKey = HashMap<String, MutableList<Int>>()
        for (i in 0 until n) {
            val k = keyOf(heap[i])
            keyOfIndex[i] = k
            if (k != null) indicesByKey.getOrPut(k) { mutableListOf() }.add(i)
        }

        val used = BooleanArray(n)
        val order = IntArray(n)
        order[0] = start
        used[start] = true

        // Cache followers per key across the walk (a key can recur only once as
        // "current", but caching keeps it cheap and avoids re-querying).
        val followerCache = HashMap<String, List<ChainTransition>>()

        var current = start
        for (pos in 1 until n) {
            val next = pickNext(current, keyOfIndex, indicesByKey, used, explore, followerCache)
            order[pos] = next
            used[next] = true
            current = next
        }
        return order
    }

    /**
     * Choose the next heap index after [current]. Prefers the strongest proven
     * follower of the current song that is still unused; when [explore] is on,
     * sometimes takes a random unused song instead; and always falls back to a
     * random unused song when no proven follower is available.
     */
    private suspend fun pickNext(
        current: Int,
        keyOfIndex: Array<String?>,
        indicesByKey: Map<String, List<Int>>,
        used: BooleanArray,
        explore: Boolean,
        followerCache: HashMap<String, List<ChainTransition>>
    ): Int {
        // Exploration: occasionally jump to a random unused song to discover new
        // pairings (shuffle only).
        if (explore && Random.nextFloat() < EXPLORE_PROBABILITY) {
            return randomUnused(used)
        }

        val key = keyOfIndex[current]
        if (key != null) {
            val followers =
                followerCache.getOrPut(key) {
                    dao.followersOf(key).filter { it.strength > 0f }
                }
            // Strongest-first; take the first follower with an unused heap slot.
            for (t in followers) {
                val candidates = indicesByKey[t.toKey] ?: continue
                val slot = candidates.firstOrNull { !used[it] } ?: continue
                return slot
            }
        }
        // No proven follower available: fall back to a random unused song.
        return randomUnused(used)
    }

    private fun randomUnused(used: BooleanArray): Int {
        // Reservoir pick over unused indices — O(n), no allocation of a list.
        var chosen = -1
        var seen = 0
        for (i in used.indices) {
            if (!used[i]) {
                seen++
                if (Random.nextInt(seen) == 0) chosen = i
            }
        }
        return chosen
    }

    override suspend fun clear() {
        dao.nuke()
        nodeDao.nuke()
    }

    private companion object {
        // Fraction below which a follow counts as "skipped" and yields a
        // negative strength contribution.
        const val SKIP_THRESHOLD = 0.25f
        const val STRENGTH_SCALE = 1.0f
        // Positive node-score bump for a deliberate jump-back replay.
        const val JUMP_BACK_BONUS = 1.0f
        // Chance, per step in shuffle mode, of exploring a random song instead
        // of the top proven follower. Keeps shuffle fresh while still leaning on
        // learned pairings the rest of the time.
        const val EXPLORE_PROBABILITY = 0.30f
    }
}

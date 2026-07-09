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
import kotlin.math.pow
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

        // Completion-weighted strength delta.
        var delta = (listenedFraction - SKIP_THRESHOLD) * STRENGTH_SCALE
        // Skips are punished harder than plays are rewarded: recommendations
        // must be RARELY skipped, so a rejected follow has to die fast. Two
        // instant skips now roughly erase one full listen.
        if (delta < 0) delta *= SKIP_PENALTY_MULTIPLIER
        // A follow the user DELIBERATELY chose (tapped the song) and then
        // actually listened to is the strongest possible sequence signal.
        if (delta > 0 && kind == "Select") delta *= SELECT_BONUS_MULTIPLIER

        val now = System.currentTimeMillis()
        val existing = dao.get(fromKey, toKey)
        if (existing != null) {
            // Time decay: old strength fades toward zero before the new
            // observation lands, so RECENT behavior dominates and the system
            // realigns to taste changes automatically.
            val decayed = decayedStrength(existing.strength, existing.lastUpdatedMs, now)
            val newStrength =
                (decayed + delta).coerceIn(STRENGTH_MIN, STRENGTH_MAX)
            dao.update(
                existing.copy(
                    strength = newStrength,
                    count = existing.count + 1,
                    lastUpdatedMs = now))
        } else {
            dao.insert(
                ChainTransition(
                    fromKey = fromKey,
                    toKey = toKey,
                    strength = delta.coerceIn(STRENGTH_MIN, STRENGTH_MAX),
                    count = 1,
                    lastUpdatedMs = now))
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

    /**
     * Strength faded by elapsed time: halves every [HALF_LIFE_DAYS]. Applied
     * lazily at write and read time — no background job needed.
     */
    private fun decayedStrength(strength: Float, lastUpdatedMs: Long, nowMs: Long): Float {
        val elapsedDays = (nowMs - lastUpdatedMs).coerceAtLeast(0L) / MS_PER_DAY
        if (elapsedDays <= 0.0) return strength
        return (strength * 2.0.pow(-elapsedDays / HALF_LIFE_DAYS)).toFloat()
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
        val now = System.currentTimeMillis()
        val followers =
            dao.followersOf(fromKey)
                .map { it to decayedStrength(it.strength, it.lastUpdatedMs, now) }
                .filter { (_, s) -> s > 0f }
                .sortedByDescending { (_, s) -> s }
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
        return followers.mapNotNull { (t, decayed) ->
            byKey[t.toKey]?.let { ChainRepository.FollowerSong(it, decayed) }
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

        // Node scores for every song in the heap, loaded ONCE in batch: the
        // song's standalone standing. net > 0 = liked/often played through;
        // net < 0 = often skipped. Drives both follower ranking and the
        // weighted random picks, so most-played songs surface MORE often and
        // chronically-skipped songs are downgraded.
        val netByKey = HashMap<String, Float>()
        run {
            val keys = indicesByKey.keys.toList()
            for (chunk in keys.chunked(NODE_BATCH)) {
                for (score in nodeDao.getAll(chunk)) {
                    netByKey[score.key] = score.positiveScore - score.negativeScore
                }
            }
        }
        val nodeNetOf = { i: Int -> keyOfIndex[i]?.let { netByKey[it] } ?: 0f }
        val now = System.currentTimeMillis()

        var current = start
        for (pos in 1 until n) {
            val next =
                pickNext(
                    current, keyOfIndex, indicesByKey, used, explore, followerCache, nodeNetOf,
                    now)
            order[pos] = next
            used[next] = true
            current = next
        }
        return order
    }

    /**
     * Choose the next heap index after [current].
     *
     * Priority: the follower with the best combined score — time-DECAYED edge
     * strength plus a node bonus for the candidate's standalone standing — as
     * long as that combined score is positive. When [explore] is on there's a
     * small chance of a node-WEIGHTED random pick instead (discover new
     * pairings, biased toward songs the user generally likes). When no proven
     * follower is available, fall back to a node-weighted random pick — never
     * uniform, so often-skipped songs stay rare and favorites surface more.
     */
    private suspend fun pickNext(
        current: Int,
        keyOfIndex: Array<String?>,
        indicesByKey: Map<String, List<Int>>,
        used: BooleanArray,
        explore: Boolean,
        followerCache: HashMap<String, List<ChainTransition>>,
        nodeNetOf: (Int) -> Float,
        nowMs: Long
    ): Int {
        // Minor exploration (shuffle only): a small chance to jump to a
        // node-weighted random song so new pairings keep getting discovered.
        if (explore && Random.nextFloat() < EXPLORE_PROBABILITY) {
            return weightedRandomUnused(used, nodeNetOf)
        }

        val key = keyOfIndex[current]
        if (key != null) {
            val followers = followerCache.getOrPut(key) { dao.followersOf(key) }
            // Rank by decayed edge strength + candidate's node standing; take
            // the best UNUSED candidate with a positive combined score.
            var bestSlot = -1
            var bestScore = 0f
            for (t in followers) {
                val candidates = indicesByKey[t.toKey] ?: continue
                val slot = candidates.firstOrNull { !used[it] } ?: continue
                val edge = decayedStrength(t.strength, t.lastUpdatedMs, nowMs)
                if (edge <= 0f) continue
                val combined = edge + NODE_WEIGHT * nodeNetOf(slot)
                if (combined > bestScore) {
                    bestScore = combined
                    bestSlot = slot
                }
            }
            if (bestSlot != -1) return bestSlot
        }
        // No proven follower available: node-weighted fallback.
        return weightedRandomUnused(used, nodeNetOf)
    }

    /**
     * Weighted random pick over unused songs. Weight grows with the song's node
     * standing (most played/liked -> picked more often) and shrinks for
     * chronically-skipped songs, floored so nothing is ever fully excluded —
     * a downgraded song still gets rare chances to redeem itself.
     */
    private fun weightedRandomUnused(used: BooleanArray, nodeNetOf: (Int) -> Float): Int {
        var total = 0.0
        var chosen = -1
        for (i in used.indices) {
            if (used[i]) continue
            val w =
                (PICK_BASE_WEIGHT + nodeNetOf(i))
                    .coerceIn(PICK_MIN_WEIGHT, PICK_MAX_WEIGHT)
                    .toDouble()
            total += w
            // Weighted reservoir: replace with probability w/total.
            if (Random.nextDouble() * total < w) chosen = i
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
        // Skips are punished this much harder than plays are rewarded, so a
        // rejected recommendation disappears quickly (goal: rarely skipped).
        const val SKIP_PENALTY_MULTIPLIER = 2.0f
        // Bonus when the user DELIBERATELY chose the follow and then listened.
        const val SELECT_BONUS_MULTIPLIER = 1.5f
        // Positive node-score bump for a deliberate jump-back replay.
        const val JUMP_BACK_BONUS = 1.0f
        // Edge strength bounds: cap keeps one pair from becoming unbeatable;
        // floor keeps a buried edge able to recover.
        const val STRENGTH_MIN = -2.0f
        const val STRENGTH_MAX = 6.0f
        // Edge strength halves this many days after its last update, so the
        // system realigns to CURRENT taste automatically.
        const val HALF_LIFE_DAYS = 30.0
        const val MS_PER_DAY = 86_400_000.0
        // Minor exploration: chance per step, in shuffle mode only, of a
        // node-weighted random pick instead of the best proven follower.
        const val EXPLORE_PROBABILITY = 0.12f
        // How much a candidate's standalone (node) standing counts alongside
        // the edge strength when ranking followers.
        const val NODE_WEIGHT = 0.3f
        // Weighted-random pick bounds for fallback/exploration.
        const val PICK_BASE_WEIGHT = 1.0f
        const val PICK_MIN_WEIGHT = 0.1f
        const val PICK_MAX_WEIGHT = 8.0f
        // SQLite variable limit safety for batch IN queries.
        const val NODE_BATCH = 500
    }
}

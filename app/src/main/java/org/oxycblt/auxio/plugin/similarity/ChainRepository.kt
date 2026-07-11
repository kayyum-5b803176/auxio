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
import kotlin.math.sqrt
import kotlin.random.Random
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Smart Chain, embedding edition.
 *
 * Every song has a vector in a shared latent space; similarity is the cosine
 * between vectors (symmetric, defined for every pair, generalizing). Learning
 * moves vectors together when one song genuinely follows another, apart when
 * one is skipped after another; explicit user feedback is the same move with a
 * larger step. New songs are seeded from metadata (artist/genre) so same-artist
 * tracks start clustered before any play — solving cold start by construction.
 */
interface ChainRepository {
    /**
     * Learn from an observed transition: [from] played, then [to] played and was
     * heard for [listenedFraction]. A good follow pulls the two vectors together;
     * an early skip pushes them apart. [kind] tags the source (Play/Skip/Select)
     * for weighting and the log.
     */
    suspend fun recordTransition(from: Song, to: Song, listenedFraction: Float, kind: String)

    /** Fold a standalone play observation into [song]'s quality score. */
    suspend fun recordSongPlay(song: Song, listenedFraction: Float)

    /** A jump-back "like" — strong positive for [song]'s quality score. */
    suspend fun recordJumpBack(song: Song, contextHeard: Float)

    /**
     * Explicit user feedback that [a] and [b] belong together (Similar) or not
     * (Different). Same vector move as learning, but a much larger step —
     * one confirmation outweighs many ordinary plays.
     */
    suspend fun confirmPairing(a: Song, b: Song, similar: Boolean)

    /**
     * Order [heap] by similarity for the player. Greedy nearest-neighbour walk
     * from [startHeapIndex], honoring Zone Axis compatibility (never chains a
     * song whose EFFECTIVE tags conflict with the current one). When [filter]
     * is non-null, only songs passing the filter (plus inherited-compatible
     * ones) are eligible to play next; the rest are pushed to the tail.
     *
     * @return a permutation of heap.indices starting at [startHeapIndex], or an
     *   empty array to signal "filter matched nothing" (caller should stop).
     */
    suspend fun chainOrdering(
        heap: List<Song>,
        startHeapIndex: Int,
        explore: Boolean,
        filter: ZoneFilter? = null
    ): IntArray

    /** Active hard-filter scope. Null fields mean "All" on that axis. */
    data class ZoneFilter(
        val languageValueId: Long?,
        val typeValueId: Long?,
        val minFrequency: FrequencyTier?
    ) {
        val isActive: Boolean
            get() = languageValueId != null || typeValueId != null || minFrequency != null
    }

    /** Clear all learned data. */
    suspend fun clear()
}

class ChainRepositoryImpl
@Inject
constructor(
    private val embeddingDao: EmbeddingDao,
    private val qualityDao: QualityDao,
    private val fingerprintRepository: FingerprintRepository,
    private val musicRepository: org.oxycblt.auxio.music.MusicRepository,
    private val zoneAxisRepository: ZoneAxisRepository,
    private val chainLog: ChainLog
) : ChainRepository {

    // ---- key resolution (fingerprint, uid: fallback) --------------------

    private suspend fun keyOf(song: Song): String? {
        val result = fingerprintRepository.getCached(song)
        val fpKey = result?.let { ChainKey.of(it.fingerprint) }
        if (fpKey != null) return fpKey
        return "uid:" + song.uid.toString()
    }

    private fun nameOf(song: Song): String = song.path.name ?: song.uri.toString()

    // ---- content-based seeding (cold start) ------------------------------

    /**
     * Deterministic starting vector from metadata: components derived from a
     * hash of each artist UID (and the genre), so every song by the same artist
     * begins clustered in the space before a single play. Stable across calls
     * (same song -> same seed), with a tiny key-derived jitter so identical
     * metadata doesn't collapse to the exact same point.
     */
    private fun seedVector(song: Song, key: String): FloatArray {
        val v = FloatArray(DIMENSIONS)
        fun mix(seed: Int, weight: Float) {
            var h = seed
            for (d in 0 until DIMENSIONS) {
                // xorshift-ish scramble per dimension for a stable pseudo-vector.
                h = h xor (h shl 13); h = h xor (h ushr 17); h = h xor (h shl 5)
                v[d] += weight * ((h and 0xFFFF) / 32768f - 1f)
            }
        }
        var any = false
        for (artist in song.artists) {
            mix(artist.uid.toString().hashCode(), ARTIST_SEED_WEIGHT)
            any = true
        }
        for (genre in song.genres) {
            mix(genre.uid.toString().hashCode(), GENRE_SEED_WEIGHT)
            any = true
        }
        // Always add a small key-derived jitter so no two songs are identical.
        mix(key.hashCode(), JITTER_WEIGHT)
        if (!any) mix(key.hashCode() * 31, ARTIST_SEED_WEIGHT)
        return normalized(v)
    }

    private suspend fun embeddingFor(song: Song, key: String, now: Long): SongEmbedding {
        embeddingDao.get(key)?.let {
            return it
        }
        val seeded =
            SongEmbedding(
                key = key,
                vector = seedVector(song, key),
                observationCount = 0,
                lastUpdatedMs = now)
        embeddingDao.put(seeded)
        return seeded
    }

    // ---- learning: attract / repel --------------------------------------

    override suspend fun recordTransition(
        from: Song,
        to: Song,
        listenedFraction: Float,
        kind: String
    ) {
        val fromKey = keyOf(from) ?: return
        val toKey = keyOf(to) ?: return
        if (fromKey == toKey) return

        val now = System.currentTimeMillis()

        // Zone Axis (Option A): if the user has EXPLICITLY tagged both songs
        // with conflicting axis values, never merge their vectors — "not
        // learning is better than learning in a wrong direction". Unassigned
        // songs still learn normally.
        val explicitlyCompatible = zoneAxisRepository.explicitlyCompatible(fromKey, toKey)

        val a = agedTowardAnchor(embeddingFor(from, fromKey, now), from, fromKey, now)
        val b = agedTowardAnchor(embeddingFor(to, toKey, now), to, toKey, now)

        // Signed pull: positive (heard) attracts, negative (skipped) repels.
        // Skips repel harder so rejected pairings separate quickly.
        var pull = (listenedFraction - SKIP_THRESHOLD)
        if (pull < 0) pull *= SKIP_REPEL_MULTIPLIER
        if (pull > 0 && kind == "Select") pull *= SELECT_ATTRACT_MULTIPLIER

        // Block only ATTRACTION across an explicit axis conflict. Repulsion
        // (negative pull) is always allowed — pushing conflicting songs apart is
        // never wrong.
        if (pull > 0 && !explicitlyCompatible) {
            chainLog.log(
                "⊘ Axis block: ${nameOf(from)} → ${nameOf(to)} (different axis, not linked)")
            return
        }

        applyPull(a, b, from, to, fromKey, toKey, pull, now, BASE_LEARNING_RATE)

        // Record lineage for inheritance: the follow (to) descends from (from),
        // weighted by the resulting similarity so "strongest ancestor" wins.
        if (pull > 0) {
            val sim = cosine(embeddingDao.get(fromKey)!!.vector, embeddingDao.get(toKey)!!.vector)
            zoneAxisRepository.recordLineage(toKey, fromKey, sim)
        }

        val pct = (listenedFraction * 100).toInt()
        val sim = cosine(embeddingDao.get(fromKey)!!.vector, embeddingDao.get(toKey)!!.vector)
        val keyKind = if (fromKey.startsWith("uid:")) " [uid]" else ""
        chainLog.log(
            "$kind: ${nameOf(from)} → ${nameOf(to)} (heard $pct%, sim now ${"%.2f".format(sim)})$keyKind")
    }

    override suspend fun confirmPairing(a: Song, b: Song, similar: Boolean) {
        val aKey = keyOf(a) ?: return
        val bKey = keyOf(b) ?: return
        if (aKey == bKey) return
        val now = System.currentTimeMillis()
        val ea = embeddingFor(a, aKey, now)
        val eb = embeddingFor(b, bKey, now)
        // Explicit feedback: big fixed step, direction from the user's answer.
        val pull = if (similar) 1f else -1f
        applyPull(ea, eb, a, b, aKey, bKey, pull, now, EXPLICIT_LEARNING_RATE)
        chainLog.log(
            "${if (similar) "✓ Similar" else "✗ Different"}: ${nameOf(a)} ⇄ ${nameOf(b)} (you)")
    }

    /**
     * Move two vectors toward (pull>0) or away from (pull<0) each other. Step
     * size shrinks as each vector matures (observationCount), so young vectors
     * settle fast and established ones resist single-session noise.
     */
    private suspend fun applyPull(
        ea: SongEmbedding,
        eb: SongEmbedding,
        aSong: Song,
        bSong: Song,
        aKey: String,
        bKey: String,
        pull: Float,
        now: Long,
        baseRate: Float
    ) {
        val va = ea.vector.copyOf()
        val vb = eb.vector.copyOf()
        val lrA = baseRate / (1f + ea.observationCount) * pull
        val lrB = baseRate / (1f + eb.observationCount) * pull
        val na = FloatArray(DIMENSIONS)
        val nb = FloatArray(DIMENSIONS)
        for (d in 0 until DIMENSIONS) {
            val diff = vb[d] - va[d]
            na[d] = va[d] + lrA * diff
            nb[d] = vb[d] - lrB * diff
        }
        embeddingDao.put(
            ea.copy(
                vector = normalized(na),
                observationCount = ea.observationCount + 1,
                lastUpdatedMs = now))
        embeddingDao.put(
            eb.copy(
                vector = normalized(nb),
                observationCount = eb.observationCount + 1,
                lastUpdatedMs = now))
    }

    /**
     * Lazy realignment: a vector untouched for a long time drifts slightly back
     * toward its content anchor before the next update, so stale associations
     * fade and the system re-aligns to current taste automatically.
     */
    private fun agedTowardAnchor(
        e: SongEmbedding,
        song: Song,
        key: String,
        now: Long
    ): SongEmbedding {
        val days = (now - e.lastUpdatedMs).coerceAtLeast(0L) / MS_PER_DAY
        if (days < REALIGN_MIN_DAYS) return e
        val anchor = seedVector(song, key)
        val t = (days / REALIGN_FULL_DAYS).coerceIn(0.0, 1.0).toFloat() * MAX_REALIGN
        val v = e.vector
        val blended = FloatArray(DIMENSIONS) { d -> v[d] + t * (anchor[d] - v[d]) }
        return e.copy(vector = normalized(blended))
    }

    // ---- quality (standalone standing) ----------------------------------

    override suspend fun recordSongPlay(song: Song, listenedFraction: Float) {
        val key = keyOf(song) ?: return
        val delta = (listenedFraction - SKIP_THRESHOLD)
        val isSkip = listenedFraction < SKIP_THRESHOLD
        foldQuality(
            key,
            posDelta = if (delta > 0) delta else 0f,
            negDelta = if (delta < 0) -delta else 0f,
            playInc = if (isSkip) 0 else 1,
            skipInc = if (isSkip) 1 else 0,
            likeInc = 0)
    }

    override suspend fun recordJumpBack(song: Song, contextHeard: Float) {
        val key = keyOf(song) ?: return
        foldQuality(key, posDelta = JUMP_BACK_BONUS, negDelta = 0f, 0, 0, 1)
        val keyKind = if (key.startsWith("uid:")) " [uid]" else ""
        chainLog.log("Jump-back ♥ ${nameOf(song)} (replayed)$keyKind")
    }

    private suspend fun foldQuality(
        key: String,
        posDelta: Float,
        negDelta: Float,
        playInc: Int,
        skipInc: Int,
        likeInc: Int
    ) {
        val now = System.currentTimeMillis()
        val updated = qualityDao.fold(key, posDelta, negDelta, playInc, skipInc, likeInc, now)
        if (updated == 0) {
            qualityDao.insert(
                SongQuality(key, posDelta, negDelta, playInc, skipInc, likeInc, now))
        }
    }

    // ---- ordering: nearest-neighbour walk -------------------------------

    override suspend fun chainOrdering(
        heap: List<Song>,
        startHeapIndex: Int,
        explore: Boolean,
        filter: ChainRepository.ZoneFilter?
    ): IntArray {
        val n = heap.size
        if (n <= 1) return IntArray(n) { it }
        val start = startHeapIndex.coerceIn(0, n - 1)

        // Resolve keys and gather vectors + quality once.
        val keys = arrayOfNulls<String>(n)
        for (i in 0 until n) keys[i] = keyOf(heap[i])

        val distinctKeys = keys.filterNotNull().distinct()
        val vecByKey = HashMap<String, FloatArray>()
        for (chunk in distinctKeys.chunked(BATCH)) {
            for (e in embeddingDao.getAll(chunk)) vecByKey[e.key] = e.vector
        }
        val qualByKey = HashMap<String, Float>()
        for (chunk in distinctKeys.chunked(BATCH)) {
            for (q in qualityDao.getAll(chunk)) qualByKey[q.key] = q.positiveScore - q.negativeScore
        }
        // Effective (explicit + inherited) tags per key, computed once.
        val effTags = HashMap<String, Pair<Long?, Long?>>()
        for (k in distinctKeys) effTags[k] = zoneAxisRepository.effectiveTags(k)

        val vectorOf = { i: Int ->
            val k = keys[i]
            when {
                k == null -> null
                vecByKey.containsKey(k) -> vecByKey[k]
                else -> seedVector(heap[i], k).also { vecByKey[k] = it }
            }
        }
        val qualOf = { i: Int -> keys[i]?.let { qualByKey[it] } ?: 0f }
        val tagsOf = { i: Int -> keys[i]?.let { effTags[it] } ?: (null to null) }

        // Rule A: two positions are compatible unless some axis where BOTH have
        // an effective value disagrees.
        val compatible = { i: Int, j: Int ->
            val (li, ti) = tagsOf(i)
            val (lj, tj) = tagsOf(j)
            (li == null || lj == null || li == lj) && (ti == null || tj == null || ti == tj)
        }

        // Hard filter membership (Step: filter is secondary). A position passes
        // if its effective tags match the selected axis values AND its frequency
        // tier is at least the floor. Null filter fields = "All".
        val passesFilter: suspend (Int) -> Boolean = pass@{ i ->
            if (filter == null || !filter.isActive) return@pass true
            val (lang, type) = tagsOf(i)
            if (filter.languageValueId != null && lang != filter.languageValueId) return@pass false
            if (filter.typeValueId != null && type != filter.typeValueId) return@pass false
            if (filter.minFrequency != null) {
                val k = keys[i] ?: return@pass false
                if (frequencyTierOrdinalOf(k) < filter.minFrequency.ordinal) return@pass false
            }
            true
        }

        // Precompute filter eligibility.
        val eligible = BooleanArray(n) { true }
        if (filter != null && filter.isActive) {
            var anyEligible = false
            for (i in 0 until n) {
                val ok = passesFilter(i)
                eligible[i] = ok
                if (ok) anyEligible = true
            }
            // Filter matched nothing -> signal caller to stop (empty result).
            if (!anyEligible) return IntArray(0)
        }

        val used = BooleanArray(n)
        val order = IntArray(n)
        order[0] = start
        used[start] = true
        var current = start
        var filled = 1

        for (pos in 1 until n) {
            val cur = vectorOf(current)
            // Candidates: unused, compatible with current, and (if filtering) eligible.
            val scored = ArrayList<Pair<Int, Float>>(n)
            for (i in 0 until n) {
                if (used[i]) continue
                if (!compatible(current, i)) continue
                if (filter != null && filter.isActive && !eligible[i]) continue
                val v = vectorOf(i)
                val sim = if (v == null || cur == null) -1f else cosine(cur, v)
                scored.add(i to (sim + QUALITY_TIEBREAK * qualOf(i)))
            }
            if (scored.isEmpty()) break // no compatible/eligible follow remains
            scored.sortByDescending { it.second }
            val next =
                if (explore) {
                    val k = minOf(EXPLORE_TOP_K, scored.size)
                    scored[Random.nextInt(k)].first
                } else {
                    scored[0].first
                }
            order[pos] = next
            used[next] = true
            current = next
            filled++
        }

        // Append any leftover positions (incompatible follows, or filtered-out
        // songs) at the tail so the result stays a full permutation — required
        // by BetterShuffleOrder. Filtered-out songs simply never play before the
        // eligible ones are exhausted.
        if (filled < n) {
            for (i in 0 until n) if (!used[i]) {
                order[filled++] = i
                used[i] = true
            }
        }
        return order
    }

    private suspend fun frequencyTierOrdinalOf(key: String): Int {
        val q = qualityDao.get(key) ?: return FrequencyTier.LOW_FREQUENT.ordinal
        val net = q.positiveScore - q.negativeScore
        val plays = q.playCount
        val skips = q.skipCount
        val skipRate = if (plays + skips > 0) skips.toFloat() / (plays + skips) else 0f
        return when {
            skipRate >= 0.8f && plays <= 1 -> FrequencyTier.NOT_LISTENABLE.ordinal
            skipRate >= 0.5f -> FrequencyTier.SKIP_OFTEN.ordinal
            net >= 3.0f && plays >= 5 -> FrequencyTier.HIGH_FREQUENT.ordinal
            else -> FrequencyTier.LOW_FREQUENT.ordinal
        }
    }

    override suspend fun clear() {
        embeddingDao.nuke()
        qualityDao.nuke()
    }

    // ---- vector math -----------------------------------------------------

    private fun normalized(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm < 1e-6f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) dot += a[i] * b[i]
        return dot // both are unit-normalized, so dot == cosine
    }

    companion object {
        const val DIMENSIONS = 24

        // Learning rates. Effective rate = base / (1 + observationCount), so a
        // young vector moves fast and a mature one resists single-session noise.
        const val BASE_LEARNING_RATE = 0.30f
        const val EXPLICIT_LEARNING_RATE = 0.80f // explicit feedback ≫ implicit

        // Follow vs skip.
        const val SKIP_THRESHOLD = 0.25f
        const val SKIP_REPEL_MULTIPLIER = 2.0f
        const val SELECT_ATTRACT_MULTIPLIER = 1.5f

        // Quality.
        const val JUMP_BACK_BONUS = 1.0f
        const val QUALITY_TIEBREAK = 0.15f // similarity dominates; quality breaks ties

        // Cold-start seeding weights.
        const val ARTIST_SEED_WEIGHT = 1.0f
        const val GENRE_SEED_WEIGHT = 0.4f
        const val JITTER_WEIGHT = 0.08f

        // Lazy realignment toward content anchor for stale vectors.
        const val MS_PER_DAY = 86_400_000.0
        const val REALIGN_MIN_DAYS = 30.0
        const val REALIGN_FULL_DAYS = 180.0
        const val MAX_REALIGN = 0.5f

        // Shuffle exploration: sample among the K nearest instead of the top 1.
        const val EXPLORE_TOP_K = 5

        const val BATCH = 500
    }
}

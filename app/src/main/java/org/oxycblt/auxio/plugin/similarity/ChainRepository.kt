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
/**
 * The three within-ring ordering sliders, each -1f..+1f. Magnitude = strength,
 * sign = direction (positive similarity/frequency/random = more-similar /
 * most-played / more-shuffled first; negative = the mirror). Magnitudes
 * normalize into shares summing to 1 when applied.
 */
data class QueueOrderWeights(val similarity: Float, val frequency: Float, val random: Float)

interface ChainRepository {
    /**
     * Learn from an observed transition: [from] played, then [to] played and was
     * heard for [listenedFraction]. A good follow pulls the two vectors together;
     * an early skip pushes them apart. [kind] tags the source (Play/Skip/Select)
     * for weighting and the log.
     */
    suspend fun recordTransition(
        from: Song,
        to: Song,
        fromHeard: Float,
        toHeard: Float,
        kind: String,
        weight: Float,
        applyZone: Boolean
    )

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
     * from [startHeapIndex]. Each candidate is scored by vector similarity minus
     * the normalized zone-space distance (continuous, no hard block), so a
     * zone-far song is demoted rather than excluded.
     *
     * @return a permutation of heap.indices starting at [startHeapIndex].
     */
    suspend fun chainOrdering(heap: List<Song>, startHeapIndex: Int, explore: Boolean): IntArray

    /** Clear all learned data. */
    suspend fun clear()

    /** Display rows of outgoing transitions from [song], strongest-first, for the log. */
    suspend fun transitionsForCurrent(song: Song): List<TransitionRow>
}

/** A display row for the transition log: destination name + counts + strength. */
data class TransitionRow(
    val toName: String,
    val plays: Int,
    val skips: Int,
    val strength: Float
)

class ChainRepositoryImpl
@Inject
constructor(
    private val embeddingDao: EmbeddingDao,
    private val qualityDao: QualityDao,
    private val fingerprintRepository: FingerprintRepository,
    private val musicRepository: org.oxycblt.auxio.music.MusicRepository,
    private val zoneAxisRepository: ZoneAxisRepository,
    private val transitionDao: TransitionDao,
    private val pluginSettings: PluginSettings,
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
        fromHeard: Float,
        toHeard: Float,
        kind: String,
        weight: Float,
        applyZone: Boolean
    ) {
        val fromKey = keyOf(from) ?: return
        val toKey = keyOf(to) ?: return
        if (fromKey == toKey) return

        // The destination's heard fraction is the "was this a good follow" signal
        // that drives attract/repel; the source's heard is carried for logging
        // and (via the caller's weight) magnitude.
        val listenedFraction = toHeard

        val now = System.currentTimeMillis()

        val a = agedTowardAnchor(embeddingFor(from, fromKey, now), from, fromKey, now)
        val b = agedTowardAnchor(embeddingFor(to, toKey, now), to, toKey, now)

        // Base signed pull from listening: positive (heard) attracts, negative
        // (skipped) repels. Skips repel harder so rejected pairings separate.
        var pull = (listenedFraction - SKIP_THRESHOLD)
        if (pull < 0) pull *= SKIP_REPEL_MULTIPLIER
        if (pull > 0 && kind == "Select") pull *= SELECT_ATTRACT_MULTIPLIER

        // Zone-space blend (additive, penalty-only): subtract the normalized
        // Zone + bias apply ONLY to "pass"/listened edges (real listened->listened
        // links), never to skip edges — a skip is a pure rejection and must not be
        // softened (or hardened) by mood agreement. When applyZone is false, zoneRel
        // stays 0 and no bias is added.
        var zoneRel = 0f
        if (applyZone) {
            val fromTag = zoneAxisRepository.tagForKey(fromKey)
            val toTag = zoneAxisRepository.tagForKey(toKey)
            val langRel =
                zoneAxisRepository.relationBetween(fromTag?.languageValueId, toTag?.languageValueId)
            val typeRel =
                zoneAxisRepository.relationBetween(fromTag?.typeValueId, toTag?.typeValueId)
            // Most-extreme-wins: pick the relation with the largest magnitude.
            zoneRel = if (kotlin.math.abs(langRel) >= kotlin.math.abs(typeRel)) langRel else typeRel
            pull += ZONE_LEARN_WEIGHT * zoneRel

            // Bias nudge (per-user love/understand of the DESTINATION song's tags):
            // into a loved/understood tag attracts a little more, into a hated one
            // repels a little. Neutral (0) = no effect.
            val toLangBias = zoneAxisRepository.biasOf(toTag?.languageValueId)
            val toTypeBias = zoneAxisRepository.biasOf(toTag?.typeValueId)
            pull += BIAS_LEARN_WEIGHT * (toLangBias + toTypeBias)
        }

        // Scale the entire learning magnitude by the caller's weight. A normal
        // edge uses 1.0; a pass-through (unlistened navigation skip) uses a tiny
        // ramped fraction so it barely moves the pair and only accumulates under
        // persistent repetition — without this, skipping past a song on the way
        // to another would corrupt otherwise-correct links.
        pull *= weight

        // Cosine BEFORE the pull, so we can report the signed change this single
        // transition caused (+ pushed the pair together, - pushed them apart).
        val simBefore = cosine(embeddingDao.get(fromKey)!!.vector, embeddingDao.get(toKey)!!.vector)

        applyPull(a, b, from, to, fromKey, toKey, pull, now, BASE_LEARNING_RATE)

        val srcPct = (fromHeard * 100).toInt()
        val destPct = (toHeard * 100).toInt()
        val simAfter = cosine(embeddingDao.get(fromKey)!!.vector, embeddingDao.get(toKey)!!.vector)
        val delta = simAfter - simBefore
        val keyKind = if (fromKey.startsWith("uid:")) " [uid]" else ""
        val zoneNote = if (zoneRel != 0f) " rel=${"%+.2f".format(zoneRel)}" else ""
        // Two lines: the transition, then the learning detail beneath —
        // heard [src : dest], resulting sim, and the signed delta.
        chainLog.log(
            "$kind: ${nameOf(from)} → ${nameOf(to)}$keyKind\n" +
                "heard [src $srcPct% : des $destPct%], sim ${"%.2f".format(simAfter)} " +
                "(${"%+.3f".format(delta)})$zoneNote")

        // Directed transition graph: count this as a real, proven A->B transition
        // when enabled AND this is a genuine listened edge (applyZone marks
        // Play/Select/Pass; skip edges never count as transition evidence). Play
        // vs skip is decided by the destination's own heard fraction against the
        // graph's independent threshold.
        if (applyZone && pluginSettings.transitionGraphEnabled) {
            val isPlay = toHeard >= TRANSITION_PLAY_THRESHOLD
            transitionDao.upsertDelta(
                fromKey, toKey,
                plays = if (isPlay) 1 else 0,
                skips = if (isPlay) 0 else 1,
                now = now)
        }
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
        explore: Boolean
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
        val freqByKey = HashMap<String, Float>()
        for (k in distinctKeys) freqByKey[k] = zoneAxisRepository.frequencyOf(k)

        // Tags per key (which Language/Type value each song carries), batched.
        val tagByKey = HashMap<String, SongZoneTag>()
        for (chunk in distinctKeys.chunked(BATCH)) {
            for (t in zoneAxisRepository.tagsForKeys(chunk)) tagByKey[t.songKey] = t
        }
        // All stored relations (sparse) for cheap in-memory lookup during the walk.
        val relations = zoneAxisRepository.allRelations()
        fun rel(a: Long?, b: Long?): Float {
            if (a == null || b == null || a == b) return 0f
            return relations[minOf(a, b) to maxOf(a, b)] ?: 0f
        }
        // Per-tag bias (love/understand), sparse; unset/neutral = 0 = no effect.
        val biases = zoneAxisRepository.biasByValue()
        fun bias(id: Long?): Float = id?.let { biases[it] } ?: 0f

        // Directed transition graph: proven "played B after A" evidence. Only
        // consulted when enabled. Strength = plays/(plays+skips) with +1
        // shrinkage so a handful of plays isn't over-trusted. Cached per source
        // key as we encounter each current song during the walk.
        val transitionEnabled = pluginSettings.transitionGraphEnabled
        val transitionCache = HashMap<String, Map<String, Float>>()
        suspend fun transitionsOf(sourceKey: String?): Map<String, Float> {
            if (!transitionEnabled || sourceKey == null) return emptyMap()
            return transitionCache.getOrPut(sourceKey) {
                val out = HashMap<String, Float>()
                for (e in transitionDao.outgoingFromNow(sourceKey)) {
                    val total = e.plays + e.skips
                    // Shrunk strength, only meaningfully positive with real plays.
                    out[e.toKey] = if (total > 0) e.plays.toFloat() / (total + 1) else 0f
                }
                out
            }
        }

        val vectorOf = { i: Int ->
            val k = keys[i]
            when {
                k == null -> null
                vecByKey.containsKey(k) -> vecByKey[k]
                else -> seedVector(heap[i], k).also { vecByKey[k] = it }
            }
        }
        val tagOf = { i: Int -> keys[i]?.let { tagByKey[it] } }
        val freqOf = { i: Int -> keys[i]?.let { freqByKey[it] } ?: 0f }

        // Within-ring order weights (the 3-slider blend). Magnitudes normalize
        // into shares summing to 1; sign gives direction (see QueueOrderWeights).
        val w = queueOrderWeights()
        val magSum = (kotlin.math.abs(w.similarity) + kotlin.math.abs(w.frequency) +
            kotlin.math.abs(w.random)).coerceAtLeast(1e-4f)
        val simShare = kotlin.math.abs(w.similarity) / magSum
        val simDir = if (w.similarity >= 0f) 1f else -1f
        val freqShare = kotlin.math.abs(w.frequency) / magSum
        val freqDir = if (w.frequency >= 0f) 1f else -1f
        val randShare = kotlin.math.abs(w.random) / magSum

        // Random dissolves intentional structure: similarity/frequency shares
        // already shrink as randShare grows (shared-budget normalization above);
        // fade the cross-ring BIAS pull directionally by Random's SIGN — a linear
        // map where -1 = full bias, 0 = half, +1 = no bias. (Random's magnitude
        // still drives within-ring shuffle via randShare above; here its sign
        // additionally controls how much user bias shapes cross-ring order.)
        val biasFade = ((1f - w.random) / 2f).coerceIn(0f, 1f)
        val effectiveBiasWeight = BIAS_ORDER_WEIGHT * biasFade

        val used = BooleanArray(n)
        val order = IntArray(n)
        order[0] = start
        used[start] = true
        var current = start
        var filled = 1

        // Recently-played guard: avoid resurfacing a song heard in the last N
        // picks unless nothing else is available (soft, not a hard rule).
        val recent = ArrayDeque<String>()
        fun pushRecent(i: Int) {
            keys[i]?.let {
                recent.addLast(it)
                while (recent.size > RECENT_WINDOW) recent.removeFirst()
            }
        }
        pushRecent(start)

        for (pos in 1 until n) {
            val curTag = tagOf(current)
            val cur = vectorOf(current)
            // Proven transitions FROM the current song (empty if disabled).
            val curTransitions = transitionsOf(keys[current])
            // The Similarity slider gates how much a proven transition may bypass
            // the ring cascade: low similarity share = rigid cascade (little
            // bypass), high = strong habits override mood structure. Positive
            // direction only (a "least similar first" setting shouldn't grant
            // bypass power). 0..1.
            val bypassGate = (simShare * simDir).coerceIn(0f, 1f)

            var bestI = -1
            var bestScore = -Float.MAX_VALUE
            var bestNonRecentI = -1
            var bestNonRecentScore = -Float.MAX_VALUE
            for (i in 0 until n) {
                if (used[i]) continue
                val v = vectorOf(i)
                val sim = if (v == null || cur == null) -1f else cosine(cur, v)
                val iTag = tagOf(i)
                // Ring priority: Type first (mood), then Language, most-extreme
                // relation wins; positive attracts, negative pushes to the tail.
                val typeRel = rel(curTag?.typeValueId, iTag?.typeValueId)
                val langRel = rel(curTag?.languageValueId, iTag?.languageValueId)
                // Type weighted heavier than Language so mood leads the cascade.
                val ringScore = RING_TYPE_WEIGHT * typeRel + RING_LANG_WEIGHT * langRel

                // Proven transition strength from current -> candidate (0 if none
                // or disabled). Real play history only — cold-start similarity
                // never grants this.
                val transStrength = keys[i]?.let { curTransitions[it] } ?: 0f

                // Within-ring blend: similarity + frequency (signed) + random,
                // plus proven transition strength (orders WITHIN a ring by real
                // habit). Kept below the ring term so it only sorts within a ring.
                val within =
                    simShare * simDir * sim +
                        freqShare * freqDir * freqOf(i) +
                        randShare * (Random.nextFloat() * 2f - 1f) +
                        TRANSITION_ORDER_WEIGHT * transStrength

                // Bias (per-user love/understand of the candidate's own tags).
                // Added at ring scale so a strongly-loved-but-far tag can surface
                // earlier than a closer-but-disliked one (combines across rings).
                // Faded by Random (effectiveBiasWeight): more random = less bias.
                // Sum of Language-understand + Type-love; neutral (0) = no effect.
                val biasTerm = effectiveBiasWeight * (bias(iTag?.languageValueId) + bias(iTag?.typeValueId))

                // Ring BYPASS: a strong PROVEN transition can jump the candidate
                // ahead of its ring, scaled by the Similarity gate. At full gate a
                // strong habit is worth ~one ring step; at zero gate the cascade
                // stays rigid. Only real transition evidence contributes.
                val bypass = RING_SCALE * bypassGate * transStrength

                val score = RING_SCALE * ringScore + biasTerm + within + bypass
                if (score > bestScore) {
                    bestScore = score
                    bestI = i
                }
                val isRecent = keys[i] in recent
                if (!isRecent && score > bestNonRecentScore) {
                    bestNonRecentScore = score
                    bestNonRecentI = i
                }
            }
            if (bestI < 0) break
            // Prefer the best non-recent candidate; fall back to best overall
            // only if everything left was recently played.
            val next = if (bestNonRecentI >= 0) bestNonRecentI else bestI
            order[pos] = next
            used[next] = true
            current = next
            pushRecent(next)
            filled++
        }

        // Append any leftovers at the tail so the result stays a full
        // permutation (required by BetterShuffleOrder).
        if (filled < n) {
            for (i in 0 until n) if (!used[i]) {
                order[filled++] = i
                used[i] = true
            }
        }
        return order
    }

    private fun queueOrderWeights(): QueueOrderWeights =
        QueueOrderWeights(
            similarity = pluginSettings.queueOrderSimilarity,
            frequency = pluginSettings.queueOrderFrequency,
            random = pluginSettings.queueOrderRandom)

    override suspend fun clear() {
        embeddingDao.nuke()
        qualityDao.nuke()
        transitionDao.nuke()
    }

    override suspend fun transitionsForCurrent(song: Song): List<TransitionRow> {
        val fromKey = keyOf(song) ?: return emptyList()
        val edges = transitionDao.outgoingFromNow(fromKey)
        if (edges.isEmpty()) return emptyList()
        // Resolve each destination key to a display name. uid: keys resolve via
        // the music library; fingerprint keys fall back to a short key label.
        val rows =
            edges.map { e ->
                val name = resolveKeyName(e.toKey)
                val total = e.plays + e.skips
                val strength = if (total > 0) e.plays.toFloat() / total else 0f
                TransitionRow(name, e.plays, e.skips, strength)
            }
        return rows.sortedByDescending { it.strength }
    }

    private fun resolveKeyName(key: String): String {
        if (key.startsWith("uid:")) {
            val uidStr = key.removePrefix("uid:")
            val uid = org.oxycblt.musikr.Music.UID.fromString(uidStr)
            val song = uid?.let { musicRepository.library?.findSong(it) }
            if (song != null) return nameOf(song)
        }
        // Fingerprint or unresolvable: show a short, stable label.
        return key.take(16) + "…"
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

        // Zone learning blend: signed relative value added to the listening
        // pull. "Firm" ~= 1.0 so a solidly-opposite tag pair (-1) can flip a
        // clean listen to net repel, and an aligned pair (+1) reinforces.
        const val ZONE_LEARN_WEIGHT = 1.0f

        // Cascade ordering. The ring term (relation to the current song) is
        // scaled far above the within-ring blend so songs sort into rings first
        // (same tags -> nearest Language -> Type positive-first -> negatives
        // last) and only sort by similarity/frequency/random WITHIN a ring.
        // Type leads the cascade (mood matters most), Language second.
        const val RING_SCALE = 10.0f
        const val RING_TYPE_WEIGHT = 1.0f
        const val RING_LANG_WEIGHT = 0.6f

        // Per-tag bias (love/understand). Learning: gentle nudge on the pull.
        // Ordering: scaled so a strong bias (±1) is worth roughly one ring step
        // (RING_SCALE), letting loved-but-far tags surface earlier while a
        // neutral 0 bias contributes nothing. Tuning knobs; structure is fixed.
        const val BIAS_LEARN_WEIGHT = 0.3f
        const val BIAS_ORDER_WEIGHT = 5.0f

        // Transition graph's OWN play/skip threshold (independent of SKIP_THRESHOLD
        // and the tracker's SKIP_HEARD), tunable separately: a destination heard
        // at least this fraction counts the A->B edge as a play, else a skip.
        const val TRANSITION_PLAY_THRESHOLD = 0.5f

        // Within-ring ordering weight for proven transition strength. On the same
        // scale as the other within-ring signals (well below RING_SCALE) so it
        // orders WITHIN a ring; the separate bypass term (RING_SCALE * gate *
        // strength) is what lets a strong habit jump rings.
        const val TRANSITION_ORDER_WEIGHT = 0.5f

        // Recently-played guard: don't resurface a song heard within this many
        // picks unless nothing else remains.
        const val RECENT_WINDOW = 3

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

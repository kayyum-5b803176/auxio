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
data class QueueOrderWeights(
    val similarity: Float,
    val frequency: Float,
    val random: Float,
    val axisMetadata: Float,
    val typeFilterId: Long?,
    val languageFilterId: Long?
)

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

    /**
     * Force-(re)compute the acoustic seed for one song and persist it, replacing
     * any existing embedding's vector. Used by the proactive Acoustic Scan so the
     * whole library gets audio-grounded seeds without waiting for organic
     * playback. Returns true if an acoustic seed was written, false if extraction
     * failed (left untouched / hash-seeded).
     */
    suspend fun seedAcoustic(song: Song): Boolean

    /**
     * Whether [song] already has a real acoustic seed. Cheap per-song check
     * (one key resolve + one DB lookup) — call this INSIDE a concurrent loop,
     * never in a sequential pre-pass over the whole library, or the scan stalls
     * before any progress can show (this was the acoustic-scan startup-lag bug).
     */
    suspend fun isAcousticSeeded(song: Song): Boolean
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
    private val acousticFeatures: AcousticFeatures,
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
        // Prefer an ACOUSTIC seed (grounded in how the song sounds); this decodes
        // audio, so it runs once here and is then persisted. Fall back to the
        // cheap artist/genre-hash seed if extraction fails (DRM, codec, etc.).
        val acoustic =
            try {
                acousticFeatures.extract(song.uri, song.durationMs)
            } catch (e: Exception) {
                L.e("SmartChain: acoustic seed failed, using hash seed: $e")
                null
            }
        val acousticOk = acoustic != null && acoustic.size == DIMENSIONS
        val seedVec = if (acousticOk) normalized(acoustic!!) else seedVector(song, key)
        val seeded =
            SongEmbedding(
                key = key,
                vector = seedVec,
                observationCount = 0,
                lastUpdatedMs = now,
                acousticSeeded = acousticOk)
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
                fromKey, toKey, nameOf(to),
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

        // Resolve keys and gather vectors + quality once — BATCHED. This used
        // to run one Room query per song for the fingerprint (keyOf) and then
        // ANOTHER query per key for frequency; on a large shuffle-all that was
        // thousands of sequential SQLite round-trips and a multi-second CPU
        // spike every time the queue was (re)shuffled. Now it's a handful of
        // chunked IN queries.
        val fpByUid = fingerprintRepository.getCachedBatch(heap)
        val keys = arrayOfNulls<String>(n)
        for (i in 0 until n) {
            val song = heap[i]
            val fpKey = fpByUid[song.uid.toString()]?.let { ChainKey.of(it.fingerprint) }
            keys[i] = fpKey ?: ("uid:" + song.uid.toString())
        }

        val distinctKeys = keys.filterNotNull().distinct()
        val vecByKey = HashMap<String, FloatArray>()
        for (chunk in distinctKeys.chunked(BATCH)) {
            for (e in embeddingDao.getAll(chunk)) vecByKey[e.key] = e.vector
        }
        val freqByKey = HashMap<String, Float>(zoneAxisRepository.frequencyByKeys(distinctKeys))

        // Tags per key (which Language/Type value each song carries), batched.
        val tagByKey = HashMap<String, SongZoneTag>()
        for (chunk in distinctKeys.chunked(BATCH)) {
            for (t in zoneAxisRepository.tagsForKeys(chunk)) tagByKey[t.songKey] = t
        }
        // All stored relations (sparse) for cheap in-memory lookup during the walk.
        val relations = zoneAxisRepository.allRelations()
        fun rel(a: Long?, b: Long?): Float {
            // No tag on either side -> neutral (0), genuinely no information.
            if (a == null || b == null) return 0f
            // Same tag -> maximally close (+1). Previously this returned 0, which
            // made an identically-tagged song score the SAME as an untagged one,
            // and let a different-but-positively-related tag outrank an identical
            // one. Same-tag must always be the closest.
            if (a == b) return 1f
            return relations[minOf(a, b) to maxOf(a, b)] ?: 0f
        }
        // Per-tag bias (love/understand), sparse; unset/neutral = 0 = no effect.
        val biases = zoneAxisRepository.biasByValue()
        fun bias(id: Long?): Float = id?.let { biases[it] } ?: 0f

        // Directed transition graph: proven "played B after A" (transStrength)
        // and "skipped B after A" (skipStrength) evidence from the CURRENT song.
        // Used by Frequency's adaptive fallback: proven pair-specific history when
        // it exists, else general play count. Empty when transitions disabled.
        val transStrengthByKey = HashMap<String, Float>()
        val skipStrengthByKey = HashMap<String, Float>()
        if (pluginSettings.transitionGraphEnabled) {
            keys[start]?.let { srcKey ->
                for (e in transitionDao.outgoingFromNow(srcKey)) {
                    val total = e.plays + e.skips
                    transStrengthByKey[e.toKey] = e.plays.toFloat() / (total + 1)
                    skipStrengthByKey[e.toKey] = e.skips.toFloat() / (total + 1)
                }
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

        // Output permutation; the start song is fixed at position 0.
        val used = BooleanArray(n)
        val order = IntArray(n)
        order[0] = start
        used[start] = true
        var filled = 1

        // Recently-played guard: songs heard in the last N picks are pushed to the
        // back of the eligible order (soft, not excluded).
        val recent = ArrayDeque<String>()
        keys[start]?.let { recent.addLast(it) }

        // ================= 3-STAGE FILTER/SORT PIPELINE =================
        // Replaces the old additive-score-with-magic-constants walk. The three
        // sliders are applied as ordered LAYERS, broadest first: Random scopes
        // WHICH tag-clusters are eligible, Frequency sorts survivors by play
        // count, Similarity does the final fine-grained vector sort. Each stage
        // operates strictly within what the previous stage admitted (3 -> 2 -> 1).
        val w = queueOrderWeights()
        val simV = w.similarity.coerceIn(-1f, 1f)
        val freqV = w.frequency.coerceIn(-1f, 1f)
        val randV = w.random.coerceIn(-1f, 1f)

        val curTag0 = tagOf(start)
        val curVec0 = vectorOf(start)

        // ---- STAGE 3 (Random): admit tag-groups by RANKED DEPTH, honoring any ----
        // ---- hard Type/Language filters. ----
        // Instead of an additive threshold, rank every candidate by tag closeness
        // to the current song, then admit the closest fraction. Random sets the
        // DEPTH: -1 admits only the single closest group, +1 admits all.
        // A SET filter (not "Any") hard-locks that axis to one specific value —
        // ranked-depth then only varies the OTHER, unfiltered axis. This exists
        // because the ranked-depth formula always weights Type over Language, so
        // "any Type, but Language must be X" is NOT reachable by Random alone —
        // it needs an explicit filter, not just a slider position.
        val typeFilterId = w.typeFilterId
        val languageFilterId = w.languageFilterId

        fun passesFilters(i: Int): Boolean {
            val iTag = tagOf(i)
            if (typeFilterId != null && iTag?.typeValueId != typeFilterId) return false
            if (languageFilterId != null && iTag?.languageValueId != languageFilterId) return false
            return true
        }

        fun tagCloseness(i: Int): Float {
            val iTag = tagOf(i)
            if (iTag?.typeValueId == null && iTag?.languageValueId == null) {
                // No zone tag at all (e.g. a brand-new, never-tagged song). Fall
                // back to acoustic similarity as a stand-in so it isn't stranded
                // at a flat neutral value regardless of how it actually sounds.
                val v = vectorOf(i)
                val cos = if (v == null || curVec0 == null) 0f else cosine(curVec0, v)
                return ACOUSTIC_FALLBACK_SCALE * cos
            }
            val typeRel = rel(curTag0?.typeValueId, iTag.typeValueId)
            val langRel = rel(curTag0?.languageValueId, iTag.languageValueId)
            val b = bias(iTag.typeValueId) + bias(iTag.languageValueId)
            return when {
                // Both axes locked: everyone left already matches exactly on both;
                // ranking between them is moot, Random has nothing left to do.
                typeFilterId != null && languageFilterId != null -> 0f
                // Type locked: only Language varies, so rank by Language alone.
                typeFilterId != null -> langRel + BIAS_BLEND_NUDGE * b
                // Language locked: only Type varies, so rank by Type alone.
                languageFilterId != null -> 2f * typeRel + BIAS_BLEND_NUDGE * b
                // Neither locked: original Type-dominant hierarchy.
                else -> 2f * typeRel + langRel + BIAS_BLEND_NUDGE * b
            }
        }
        val filtered = (0 until n).filter { !used[it] && passesFilters(it) }
        // Never strand the queue if a filter combination matches nothing.
        val candidates = if (filtered.isEmpty()) (0 until n).filter { !used[it] } else filtered
        // tagCloseness may compute a cosine per call and was previously
        // re-evaluated up to three times per candidate (rank sort, cutoff,
        // admit filter) plus O(n log n) times inside the comparator - compute
        // it exactly once per candidate instead.
        val closeness = FloatArray(n)
        for (i in candidates) closeness[i] = tagCloseness(i)
        // Rank closest -> farthest.
        val rankedByCloseness = candidates.sortedByDescending { closeness[it] }
        // Depth fraction: randV -1 -> 0 (only rank 0), +1 -> 1 (all).
        val depthFrac = ((randV + 1f) / 2f).coerceIn(0f, 1f)
        val cutoffIdx =
            if (rankedByCloseness.isEmpty()) 0
            else (depthFrac * (rankedByCloseness.size - 1)).toInt()
        // Admit every candidate whose closeness is >= the cutoff rank's closeness
        // (so a whole tag-group is admitted together, not split mid-group).
        val cutoffCloseness =
            if (rankedByCloseness.isEmpty()) 0f else closeness[rankedByCloseness[cutoffIdx]]
        val eligible = ArrayList<Int>(n)
        for (i in candidates) if (closeness[i] >= cutoffCloseness - 1e-4f) eligible.add(i)
        // Never strand the queue.
        if (eligible.isEmpty()) eligible.addAll(candidates)

        // Embedded-metadata closeness to the current song (album > artist > year).
        // This is now purely a Stage 1 fine-sort input (see below) — it no longer
        // ties its strength to Random.
        val startSong = heap[start]
        val startAlbumUid = startSong.album.uid
        val startArtistUids = startSong.artists.map { it.uid }.toHashSet()
        val startYear = startSong.date?.year
        fun metaCloseness(i: Int): Float {
            val s = heap[i]
            var score = 0f
            if (s.album.uid == startAlbumUid) score += META_ALBUM_WEIGHT
            if (s.artists.any { it.uid in startArtistUids }) score += META_ARTIST_WEIGHT
            val y = s.date?.year
            if (y != null && startYear != null && y == startYear) score += META_YEAR_WEIGHT
            return score
        }

        // ---- STAGE 1 (fine-sort): Similarity fades between STRUCTURED signals ----
        // ---- (Frequency + Axis/Metadata) and PURE ACOUSTIC. ----
        // +sim/-sim direction picks close-vector-first vs far-vector-first.
        // |sim| is a FADE: at 0, order is fully Frequency+Axis/Metadata (today's
        // structured signals); as |sim| rises, acoustic progressively SURPASSES
        // them; at +/-1, order is PURELY acoustic. Continuous, no cliff.
        fun axisCloseness(i: Int): Float {
            val iTag = tagOf(i)
            val typeRel = rel(curTag0?.typeValueId, iTag?.typeValueId)
            val langRel = rel(curTag0?.languageValueId, iTag?.languageValueId)
            return (2f * typeRel + langRel) / 3f // normalized to -1..+1
        }
        fun simTo(i: Int): Float {
            val v = vectorOf(i)
            return if (v == null || curVec0 == null) 0f else cosine(curVec0, v)
        }
        // Axis<->Metadata: ONE dial, 0=equal, +1=pure axis, -1=pure metadata.
        val axisMetaDial = w.axisMetadata.coerceIn(-1f, 1f)
        val axisShare = (axisMetaDial + 1f) / 2f // 0..1
        val metaShare = 1f - axisShare

        // Frequency: proven transition/skip strength for THIS pair if it exists,
        // else general play count — never a fixed blend, so a sparse pair falls
        // back cleanly instead of wasting weight on data that isn't there.
        fun freqContribution(i: Int): Float {
            val k = keys[i]
            return if (freqV >= 0f) {
                val trans = k?.let { transStrengthByKey[it] }
                (trans ?: freqOf(i)) * freqV
            } else {
                val skip = k?.let { skipStrengthByKey[it] }
                (skip ?: (1f - freqOf(i))) * (-freqV)
            }
        }

        val fade = kotlin.math.abs(simV)
        // Score each candidate exactly ONCE. The comparator form re-evaluated
        // the full score (including a 24-dim cosine) O(n log n) times.
        val sortScore = FloatArray(n)
        for (i in eligible) {
            val axisMetaContrib =
                axisShare * axisCloseness(i) + metaShare * (metaCloseness(i) / META_MAX)
            val structured = freqContribution(i) * STAGE2_WEIGHT + axisMetaContrib
            val acoustic = simTo(i) * (if (simV >= 0f) 1f else -1f)
            sortScore[i] = (1f - fade) * structured + fade * acoustic
        }
        val eligibleSorted = eligible.sortedByDescending { sortScore[it] }

        // Emit, honoring the recently-played guard as a soft de-prioritizer.
        val nonRecent = eligibleSorted.filter { keys[it] !in recent }
        val recentOnes = eligibleSorted.filter { keys[it] in recent }
        for (i in nonRecent) {
            order[filled++] = i
            used[i] = true
        }
        for (i in recentOnes) {
            order[filled++] = i
            used[i] = true
        }

        // Append any leftovers (songs Stage 3 never admitted) at the tail so the
        // result stays a full permutation (required by BetterShuffleOrder). With
        // Loop off + max Similarity this tail is where playback naturally ends;
        // with Loop on the queue cycles back through it.
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
            random = pluginSettings.queueOrderRandom,
            axisMetadata = pluginSettings.queueOrderAxisMetadata,
            typeFilterId = pluginSettings.queueOrderTypeFilterId,
            languageFilterId = pluginSettings.queueOrderLanguageFilterId)

    override suspend fun clear() {
        embeddingDao.nuke()
        qualityDao.nuke()
        transitionDao.nuke()
    }

    override suspend fun isAcousticSeeded(song: Song): Boolean {
        val key = keyOf(song) ?: return false
        return embeddingDao.get(key)?.acousticSeeded == true
    }

    override suspend fun seedAcoustic(song: Song): Boolean {
        val key = keyOf(song) ?: return false
        val now = System.currentTimeMillis()
        val acoustic =
            try {
                acousticFeatures.extract(song.uri, song.durationMs)
            } catch (e: Exception) {
                L.e("SmartChain: acoustic scan extract failed: $e")
                null
            }
        if (acoustic == null || acoustic.size != DIMENSIONS) return false
        val existing = embeddingDao.get(key)
        // Preserve learned observation history if present; only replace the vector.
        embeddingDao.put(
            SongEmbedding(
                key = key,
                vector = normalized(acoustic),
                observationCount = existing?.observationCount ?: 0,
                lastUpdatedMs = now,
                acousticSeeded = true))
        return true
    }

    override suspend fun transitionsForCurrent(song: Song): List<TransitionRow> {        val fromKey = keyOf(song) ?: return emptyList()
        val edges = transitionDao.outgoingFromNow(fromKey)
        if (edges.isEmpty()) return emptyList()
        // Resolve each destination key to a display name. uid: keys resolve via
        // the music library; fingerprint keys fall back to a short key label.
        val rows =
            edges.map { e ->
                val total = e.plays + e.skips
                val strength = if (total > 0) e.plays.toFloat() / total else 0f
                TransitionRow(e.toName, e.plays, e.skips, strength)
            }
        return rows.sortedByDescending { it.strength }
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

        // Transition graph's play/skip threshold, matched to the smart-chain
        // listen threshold (SKIP_THRESHOLD) so a song counted as "listened" by
        // the chain is also counted as a play here (was 0.5, too strict).
        const val TRANSITION_PLAY_THRESHOLD = 0.25f

        // Pipeline tuning. BIAS_BLEND_NUDGE: how much per-tag love/understand
        // loosens the Random admission gate for that tag. STAGE2_WEIGHT: keeps
        // the frequency sort below the similarity sort so Similarity (finest
        // stage) dominates when both sliders are set.
        const val BIAS_BLEND_NUDGE = 0.3f
        const val STAGE2_WEIGHT = 0.5f

        // Embedded-metadata closeness weights (album > artist > year), used as
        // one of Stage 1's three composition signals. Album implies artist+era
        // so it's strongest; shared artist across albums is looser; same year
        // is loosest. META_MAX is the max possible sum, used to normalize to 0..1.
        const val META_ALBUM_WEIGHT = 0.6f
        const val META_ARTIST_WEIGHT = 0.4f
        const val META_YEAR_WEIGHT = 0.2f
        const val META_MAX = META_ALBUM_WEIGHT + META_ARTIST_WEIGHT + META_YEAR_WEIGHT

        // Stage 3 fallback: for a song with NO zone tag at all, this scales
        // acoustic cosine (-1..1) to roughly the same range as tag-based
        // closeness (2*typeRel + langRel, up to ±3) so it lands in a believable
        // middle tier rather than a flat neutral 0.
        const val ACOUSTIC_FALLBACK_SCALE = 2.0f

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

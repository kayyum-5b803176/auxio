/*
 * Copyright (c) 2026 Auxio Project
 * DuplicateFinder.kt is part of Auxio.
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
import kotlin.math.abs
import kotlin.math.min
import org.oxycblt.musikr.Song

/**
 * Compares fingerprints produced by [AudioFingerprinter] and groups songs
 * that appear to contain the same recording.
 *
 * Pipeline per pair:
 * 1. Duration prefilter — recordings differing by more than a few seconds
 *    can't be the same audio; skips the vast majority of pairs instantly and
 *    keeps the O(n^2) scan tractable.
 * 2. Aligned Hamming similarity — bit-error-rate between the fingerprint
 *    streams, searched over a small ± frame offset so slightly different
 *    leading padding between encodes doesn't mask a true duplicate.
 * 3. Threshold — pairs at/above [MATCH_THRESHOLD] are grouped. The threshold
 *    is deliberately high: for a feature whose endpoint is *file deletion*,
 *    a missed duplicate is a minor annoyance, but a false match could steer
 *    a user into deleting a song they wanted. Tune down only with evidence.
 */
class DuplicateFinder @Inject constructor() {

    data class Match(val a: Song, val b: Song, val similarity: Float)

    data class DuplicateGroup(
        val songs: List<Song>,
        /** Lowest pairwise similarity within the group, as displayed confidence. */
        val minSimilarity: Float
    )

    fun find(fingerprints: Map<Song, IntArray>): List<DuplicateGroup> {
        val songs = fingerprints.keys.toList()
        val matches = mutableListOf<Match>()

        for (i in songs.indices) {
            for (j in i + 1 until songs.size) {
                val a = songs[i]
                val b = songs[j]
                if (abs(a.durationMs - b.durationMs) > DURATION_TOLERANCE_MS) continue

                val fpA = fingerprints.getValue(a)
                val fpB = fingerprints.getValue(b)
                val similarity = alignedSimilarity(fpA, fpB)
                if (similarity >= MATCH_THRESHOLD) {
                    matches.add(Match(a, b, similarity))
                }
            }
        }

        return cluster(matches)
    }

    /**
     * Best Hamming similarity over a small alignment offset search.
     * Similarity = 1 - bit_error_rate over the overlapping region.
     */
    fun alignedSimilarity(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var best = 0f
        for (offset in -MAX_ALIGN_OFFSET..MAX_ALIGN_OFFSET) {
            best = maxOf(best, similarityAtOffset(a, b, offset))
        }
        return best
    }

    private fun similarityAtOffset(a: IntArray, b: IntArray, offset: Int): Float {
        // offset > 0 means b is shifted forward relative to a
        val aStart = maxOf(0, offset)
        val bStart = maxOf(0, -offset)
        val overlap = min(a.size - aStart, b.size - bStart)
        // Require a meaningful overlap so a tiny sliver of frames can't
        // produce a spuriously high score.
        if (overlap < MIN_OVERLAP_FRAMES) return 0f

        var differingBits = 0
        for (k in 0 until overlap) {
            differingBits += Integer.bitCount(a[aStart + k] xor b[bStart + k])
        }
        val totalBits = overlap * BITS_PER_FRAME
        return 1f - differingBits.toFloat() / totalBits
    }

    /** Transitive grouping (union-find): A~B and B~C puts A, B, C in one group. */
    private fun cluster(matches: List<Match>): List<DuplicateGroup> {
        if (matches.isEmpty()) return emptyList()

        val parent = HashMap<Song, Song>()
        fun find(x: Song): Song {
            var root = x
            while (parent.getOrDefault(root, root) != root) root = parent.getValue(root)
            parent[x] = root
            return root
        }
        fun union(x: Song, y: Song) {
            val rx = find(x)
            val ry = find(y)
            if (rx != ry) parent[rx] = ry
        }
        matches.forEach { union(it.a, it.b) }

        val groups = HashMap<Song, MutableList<Song>>()
        matches
            .flatMap { listOf(it.a, it.b) }
            .distinct()
            .forEach { song -> groups.getOrPut(find(song)) { mutableListOf() }.add(song) }

        return groups.values.map { members ->
            val memberSet = members.toSet()
            val groupSims =
                matches.filter { it.a in memberSet && it.b in memberSet }.map { it.similarity }
            DuplicateGroup(
                // Highest quality first so the UI's "keep" recommendation is
                // simply the top row.
                songs = members.sortedWith(
                    compareByDescending<Song> { it.bitrateKbps }.thenByDescending { it.size }),
                minSimilarity = groupSims.minOrNull() ?: MATCH_THRESHOLD)
        }
    }

    companion object {
        /**
         * Fingerprint frames carry 15 meaningful bits (band-gradient signs) —
         * see [AudioFingerprinterImpl.fingerprintPcm]. Using 32 here would
         * dilute the error rate with always-equal zero bits and inflate
         * similarity scores.
         */
        const val BITS_PER_FRAME = 15
        /**
         * Empirical basis: in synthetic stress tests (worse noise than real
         * transcodes, pathologically similar "different songs"), same-recording
         * pairs scored >= 0.63 and different recordings capped at ~0.54. Real
         * transcodes of the same recording score far higher (band energies are
         * nearly identical under masked codec noise), so 0.85 sits well above
         * the different-song ceiling while catching genuine duplicates. Kept
         * deliberately high because the endpoint of a match is file deletion.
         */
        const val MATCH_THRESHOLD = 0.85f
        const val DURATION_TOLERANCE_MS = 4_000L
        /**
         * ±frames of alignment search; at ~46ms per hop this covers ~±4.6s,
         * enough for the worst-case misalignment allowed by
         * [DURATION_TOLERANCE_MS] plus the relative seek offset.
         */
        const val MAX_ALIGN_OFFSET = 100
        const val MIN_OVERLAP_FRAMES = 200
    }
}

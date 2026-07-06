/*
 * Copyright (c) 2026 Auxio Project
 * QualityAnalyzer.kt is part of Auxio.
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
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Format

/**
 * Decides which file in a duplicate group has the highest TRUE quality, and
 * explains why — the "highest quality" mechanism for the similarity feature.
 *
 * Core principle (in strict priority order):
 *  1. MEASURED TRUE QUALITY comes first. We compare each file's real spectral
 *     content (from AudioFingerprinter's source-rate spectral profile). A file
 *     that retains genuine high-frequency energy where another file is cut off
 *     truly carries more information and wins — regardless of its format,
 *     bitrate, or size. This is what catches "fake lossless": a FLAC transcoded
 *     from a 128kbps MP3 has a brick-wall cutoff a real MP3 at 320 might beat,
 *     so the MP3 is correctly preferred over the bloated FLAC.
 *  2. Only when files are spectrally EQUIVALENT (same real information, e.g. all
 *     transcoded from one source) does format design-intent break the tie —
 *     see [formatTier]. Formats designed for listening fidelity rank above
 *     formats designed for efficiency/storage/streaming. Efficiency is NOT
 *     rewarded (this is why Opus ranks low despite being technically efficient).
 *  3. Remaining ties fall to sample rate, then smaller size, then stable order.
 *
 * Format/bitrate/size NEVER override a real measured quality difference. They
 * are pure tie-breakers for genuinely equivalent files.
 */
class QualityAnalyzer @Inject constructor() {

    /** A song plus its analyzed spectral profile (may be null if analysis failed). */
    data class Candidate(val song: Song, val spectralProfile: FloatArray?)

    /** Ranking outcome for one file, with a structured explanation. */
    data class Ranked(
        val song: Song,
        val isRecommendedKeep: Boolean,
        /** Structured reason; the UI resolves it to a localized string (see Reason). */
        val reason: Reason
    )

    /**
     * Why a file landed where it did in the ranking. Structured (not a raw
     * string) so the UI layer can localize it — [QualityAnalyzer] has no
     * Context and shouldn't build user-facing text itself.
     *
     * @param formatName Short display name of the file's format (e.g. "FLAC").
     * @param approxKHz Approximate cutoff in kHz, where relevant (else null).
     */
    sealed interface Reason {
        val formatName: String

        /** Genuinely highest real detail in the group; a real lossless file. */
        data class RealLossless(override val formatName: String) : Reason

        /** Genuinely the most real detail in the group (not a lossless container). */
        data class MostDetail(override val formatName: String) : Reason

        /** Lossless container but transcoded from lossy — cut off at ~[approxKHz]. */
        data class FakeLossless(override val formatName: String, val approxKHz: Int) : Reason

        /** Fewer real information than the group's best; content only to ~[approxKHz]. */
        data class LessDetail(override val formatName: String, val approxKHz: Int) : Reason

        /** Same audio as the kept file; this one was chosen to keep (format/size). */
        data class EquivalentKept(override val formatName: String) : Reason

        /** Same audio as the kept file; a redundant copy. */
        data class EquivalentDuplicate(override val formatName: String) : Reason

        /** Audio could not be analyzed (decode failure); ranked on metadata only. */
        data class Unanalyzable(override val formatName: String) : Reason

        /** Only file in the group (shouldn't normally happen for a duplicate group). */
        data class Single(override val formatName: String) : Reason
    }

    /**
     * Rank [candidates] (all members of one duplicate group) best-first.
     * The first entry is the recommended keep.
     */
    fun rank(candidates: List<Candidate>): List<Ranked> {
        if (candidates.isEmpty()) return emptyList()
        if (candidates.size == 1) {
            return listOf(Ranked(candidates[0].song, true, describeSingle(candidates[0])))
        }

        // Cutoff frequency (kHz) per file: the highest frequency that still
        // carries real energy before the spectrum falls to the noise floor and
        // stays there. This is the primary quality signal — a genuine lossless
        // file reaches ~21-22kHz; a transcode cliffs lower (128k ~16, 320k ~20).
        val cutoffs = candidates.associateWith { cutoffKHz(it.spectralProfile) }
        val maxCutoff = cutoffs.values.maxOrNull() ?: 0f

        // Files are "spectrally equivalent" when their cutoffs are within a
        // small margin — quality can't separate them, so format-intent decides.
        val observed = cutoffs.values.filter { it > 0f }
        val minCutoff = observed.minOrNull() ?: 0f
        val spectrallyEquivalent = (maxCutoff - minCutoff) <= EQUIVALENCE_KHZ_MARGIN

        // A lossless CONTAINER whose real content is cut off is "fake lossless"
        // (transcoded from lossy). Detected two ways:
        //   (a) RELATIVE: cutoff clearly below a better sibling in the group.
        //   (b) ABSOLUTE: cutoff below where genuine lossless reaches — catches
        //       the case where ALL files are transcodes of one lossy source.
        // Such a file must never be the recommended keep.
        fun isFakeLossless(c: Candidate): Boolean {
            if (!isLosslessContainer(c.song.format)) return false
            val cutoff = cutoffs[c] ?: return false
            if (cutoff <= 0f) return false // unanalyzable — don't accuse it
            val relative = cutoff < maxCutoff - FAKE_LOSSLESS_RELATIVE_KHZ
            val absolute = cutoff < LOSSLESS_MIN_EXPECTED_KHZ
            return relative || absolute
        }

        val ranked =
            candidates.sortedWith(
                // 0. Fake-lossless files sink to the bottom.
                compareBy<Candidate> { isFakeLossless(it) }
                    // 1. True quality: higher cutoff frequency first — UNLESS
                    //    everything is equivalent, in which case this is constant.
                    .thenByDescending {
                        if (spectrallyEquivalent) 0f else (cutoffs[it] ?: -1f)
                    }
                    // 2. Format design-intent tier (higher = more fidelity-oriented).
                    .thenByDescending { formatTier(it.song.format) }
                    // 3. Higher sample rate.
                    .thenByDescending { it.song.sampleRateHz }
                    // 4. Smaller file (only when quality is a genuine tie).
                    .thenBy { it.song.size }
                    // 5. Stable tiebreak.
                    .thenBy { it.song.uid.hashCode() })

        val keep = ranked.first()
        return ranked.map { candidate ->
            Ranked(
                song = candidate.song,
                isRecommendedKeep = candidate === keep,
                reason =
                    describe(
                        candidate,
                        cutoffs[candidate] ?: -1f,
                        maxCutoff,
                        spectrallyEquivalent,
                        candidate === keep))
        }
    }

    /**
     * Estimate the audio's real cutoff frequency in kHz from its peak-hold
     * profile (see AudioFingerprinter.computeQualityProfile). Walks DOWN from
     * the highest sampled frequency and returns the first frequency whose peak
     * energy rises above the noise floor — i.e. the top of the real content.
     *
     * Returns 0 if the profile is missing/empty (analysis failed) so the file
     * sorts last on quality but can still rank on format/size, and is never
     * falsely accused of being fake lossless.
     *
     * Because the profile is PEAK-HOLD (loudest moment per frequency, like a
     * Spek spectrogram) rather than an average, genuine but intermittent
     * high-frequency content (cymbals, sibilance) registers and real lossless
     * is no longer mistaken for a transcode.
     */
    private fun cutoffKHz(profile: FloatArray?): Float {
        if (profile == null || profile.isEmpty()) return 0f
        // profile[0] is the reference (normalized to 1.0). Points 1..n are the
        // high-frequency anchors in QUALITY_PROFILE_FREQS_HZ order.
        val freqs = AudioFingerprinterImpl.QUALITY_PROFILE_FREQS_HZ
        for (i in profile.indices.reversed()) {
            if (i == 0) break // reference point, not a cutoff candidate
            val v = profile[i]
            if (v < 0f) continue // above this file's Nyquist — unobservable, skip
            if (v > NOISE_FLOOR) {
                return freqs[i] / 1000f
            }
        }
        // No high-frequency content above the floor at all — treat as the
        // lowest anchor (heavily band-limited).
        return freqs[1] / 1000f
    }

    /**
     * Format design-intent tiers (Option B). Higher = designed more for
     * listening fidelity, lower = designed more for efficiency/storage/legacy.
     * Used ONLY to break ties between spectrally equivalent files.
     */
    private fun formatTier(format: Format): Int =
        when {
            // Tier 5: lossless, designed to preserve everything.
            isLosslessContainer(format) -> 5
            // Tier 4: lossy codecs designed primarily as music-listening formats.
            //   MP3, AAC, and lossy MP4 (AAC-in-MP4) are peers on design intent.
            format is Format.MPEG3 -> 4
            format is Format.AAC -> 4
            format is Format.MPEG4 -> 4 // AAC-in-MP4 (ALAC-in-MP4 handled by lossless check above)
            // Tier 3: general-music codec with an efficiency/openness lean.
            format is Format.Vorbis -> 3
            format is Format.Ogg -> 3 // generic Ogg (Vorbis-like); Opus-in-Ogg handled below
            // Tier 2: designed primarily for efficiency / low-bitrate / real-time.
            format is Format.Opus -> 2
            // Tier 1: everything else / legacy / uncertain — never auto-preferred.
            else -> 1
        }

    private fun formatName(format: Format): String =
        when (format) {
            is Format.FLAC -> "FLAC"
            is Format.ALAC -> "ALAC"
            is Format.Wav -> "WAV"
            is Format.MPEG3 -> "MP3"
            is Format.AAC -> "AAC"
            is Format.MPEG4 -> if (isLosslessContainer(format)) "ALAC" else "M4A"
            is Format.Vorbis -> "Vorbis"
            is Format.Ogg -> "Ogg"
            is Format.Opus -> "Opus"
            is Format.Unknown -> format.extension ?: "audio"
            else -> "audio"
        }

    private fun isLosslessContainer(format: Format): Boolean {
        return when (format) {
            is Format.FLAC,
            is Format.ALAC,
            is Format.Wav -> true
            // MP4 and Ogg are containers — lossless only if what's inside is.
            is Format.MPEG4 -> isLosslessContainer(format.containing ?: return false)
            is Format.Ogg -> isLosslessContainer(format.containing ?: return false)
            else -> false
        }
    }

    private fun describeSingle(candidate: Candidate): Reason =
        Reason.Single(formatName(candidate.song.format))

    private fun describe(
        candidate: Candidate,
        cutoff: Float,
        maxCutoff: Float,
        equivalent: Boolean,
        isKeep: Boolean
    ): Reason {
        val fmt = formatName(candidate.song.format)
        val lossless = isLosslessContainer(candidate.song.format)

        // Fake-lossless: same logic as the ranking's isFakeLossless, so the
        // label always matches the ranking decision.
        if (lossless && cutoff > 0f) {
            val relativeFake = cutoff < maxCutoff - FAKE_LOSSLESS_RELATIVE_KHZ
            val absoluteFake = cutoff < LOSSLESS_MIN_EXPECTED_KHZ
            if (relativeFake || absoluteFake) {
                return Reason.FakeLossless(fmt, cutoff.toInt())
            }
        }

        if (equivalent) {
            return if (isKeep) Reason.EquivalentKept(fmt) else Reason.EquivalentDuplicate(fmt)
        }

        // Genuine quality differences.
        return when {
            cutoff <= 0f -> Reason.Unanalyzable(fmt)
            cutoff >= maxCutoff && lossless -> Reason.RealLossless(fmt)
            cutoff >= maxCutoff -> Reason.MostDetail(fmt)
            else -> Reason.LessDetail(fmt, cutoff.toInt())
        }
    }

    companion object {
        /**
         * Peak-hold energy (relative to the reference mid-band) above this
         * counts as "real content present" at a frequency. Set low because
         * genuine high-frequency content — even at its loudest moment — is much
         * quieter than the mid-band; the peak-hold profile is what makes this
         * low floor safe (it captures the loudest instant, not an average).
         */
        private const val NOISE_FLOOR = 0.004f

        /**
         * Two files whose cutoff frequencies are within this many kHz are
         * treated as spectrally equivalent (quality can't separate them, so
         * format-intent decides).
         */
        private const val EQUIVALENCE_KHZ_MARGIN = 1.5f

        /**
         * Genuine lossless normally reaches ~20-22kHz. A lossless-CONTAINER
         * file whose cutoff is below this is treated as transcoded-from-lossy
         * even with no full-spectrum sibling to compare against. Set at 19kHz:
         * catches 128-256kbps-origin transcodes (cut ~16-19kHz) while sparing
         * genuine lossless and high-bitrate (320k cuts ~20kHz, which we do NOT
         * flag — see note). This is deliberately conservative to avoid the
         * false positives on real lossless that the previous design produced.
         */
        private const val LOSSLESS_MIN_EXPECTED_KHZ = 19f

        /**
         * A lossless file whose cutoff is this many kHz below a better sibling
         * in the same group is flagged fake even if above the absolute floor —
         * catches e.g. a 20kHz-cut "FLAC" sitting next to a genuine 22kHz FLAC.
         */
        private const val FAKE_LOSSLESS_RELATIVE_KHZ = 2.5f
    }
}

/*
 * Copyright (c) 2026 Auxio Project
 * ChainKey.kt is part of Auxio.
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

import java.security.MessageDigest

/**
 * Derives a stable, fingerprint-based key identifying a piece of MUSIC (not a
 * file), so chain links and learned data survive duplicate-deletion and format
 * changes: any file of the same recording resolves to the same key.
 *
 * Approach: the acoustic fingerprint is COARSENED (each frame reduced to a few
 * high bits) before hashing, so near-identical fingerprints — the same
 * recording in different formats/bitrates — collapse to the same key, while
 * genuinely different recordings do not. This is an intentional trade: exact
 * fingerprint hashing would split a FLAC and its MP3 twin into two keys; the
 * coarsened hash keeps them together, matching the "music owns the data" goal.
 *
 * Not a similarity SEARCH (that would be expensive and library-wide) — just a
 * cheap, deterministic identity hash computed once per file from data we
 * already cache.
 */
object ChainKey {
    /**
     * @return a stable key string, or null if [fingerprint] is empty/too short
     *   to identify (caller should skip chain learning for such files).
     */
    fun of(fingerprint: IntArray): String? {
        if (fingerprint.size < MIN_FRAMES) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(fingerprint.size)
        for (i in fingerprint.indices) {
            // Keep only the top bits of each frame's fingerprint. Low bits are
            // the most sensitive to encoding differences; dropping them makes
            // same-recording files hash identically while different recordings
            // (which differ in the high bits too) still diverge.
            buffer[i] = ((fingerprint[i] ushr COARSEN_SHIFT) and 0xFF).toByte()
        }
        digest.update(buffer)
        // Only the first KEY_LENGTH hex chars are kept, so format just those
        // bytes - and with a lookup table instead of String.format, which does
        // a locale lookup + parse per byte (this runs once per song during the
        // ordering pass, so it's on a hot-ish path for big libraries).
        val hash = digest.digest()
        val sb = StringBuilder(KEY_LENGTH)
        var i = 0
        while (sb.length < KEY_LENGTH && i < hash.size) {
            val b = hash[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4])
            if (sb.length < KEY_LENGTH) sb.append(HEX[b and 0xF])
            i++
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private const val MIN_FRAMES = 8
    private const val COARSEN_SHIFT = 7 // drop the 7 lowest (most encoding-sensitive) bits
    private const val KEY_LENGTH = 24
}

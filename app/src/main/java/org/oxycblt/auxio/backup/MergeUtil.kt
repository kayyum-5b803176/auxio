/*
 * Copyright (c) 2026 Auxio Project
 * MergeUtil.kt is part of Auxio.
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

package org.oxycblt.auxio.backup

import org.json.JSONArray

/**
 * Shared merge primitives so every [BackupModule] applies the same "nothing
 * is lost, combine additively where possible" philosophy consistently,
 * instead of each module reinventing slightly different rules.
 */
object MergeUtil {
    /**
     * Weighted average of two vectors, weighted by their observation counts.
     * This is the sound way to combine two independently-learned embeddings:
     * a vector backed by many observations should move less than one backed
     * by few, exactly like each vector already behaves against new plays on
     * a single device (see ChainRepositoryImpl's self-adjusting learning
     * rate). Falls back to the more-observed side if weights are both zero.
     */
    fun weightedAverage(
        a: FloatArray,
        weightA: Int,
        b: FloatArray,
        weightB: Int
    ): FloatArray {
        if (a.size != b.size) return if (weightA >= weightB) a else b
        val totalWeight = (weightA + weightB).coerceAtLeast(1)
        val out = FloatArray(a.size)
        for (i in a.indices) {
            out[i] = (a[i] * weightA + b[i] * weightB) / totalWeight
        }
        return out
    }

    /** FloatArray <-> JSONArray, used by every module storing vectors. */
    fun FloatArray.toJson(): JSONArray = JSONArray().apply { forEach { put(it.toDouble()) } }

    fun JSONArray.toFloatArray(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }

    fun IntArray.toJsonArray(): JSONArray = JSONArray().apply { forEach { put(it) } }

    fun JSONArray.toIntArray(): IntArray = IntArray(length()) { getInt(it) }

    /** Case-insensitive set union that preserves the destination's original casing on overlap. */
    fun unionPreservingCase(current: Collection<String>, incoming: Collection<String>): List<String> {
        val out = LinkedHashMap<String, String>()
        for (s in current) out[s.lowercase()] = s
        for (s in incoming) out.putIfAbsent(s.lowercase(), s)
        return out.values.toList()
    }

    /** Whether two floats differ by more than a tiny epsilon (true numeric conflict, not FP noise). */
    fun floatsConflict(a: Float, b: Float, epsilon: Float = 0.001f): Boolean = kotlin.math.abs(a - b) > epsilon
}

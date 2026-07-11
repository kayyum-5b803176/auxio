/*
 * Copyright (c) 2026 Auxio Project
 * ZoneAxisRepository.kt is part of Auxio.
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
import kotlinx.coroutines.flow.Flow
import org.oxycblt.musikr.Song

/**
 * The four fixed, system-derived frequency tiers (NOT user-editable). Ordered
 * worst→best; [ordinal] is used for filtering ("at least Low-frequent").
 */
enum class FrequencyTier(val label: String) {
    NOT_LISTENABLE("Not-listenable"),
    SKIP_OFTEN("Skip-often"),
    LOW_FREQUENT("Low-frequent"),
    HIGH_FREQUENT("High-frequent")
}

interface ZoneAxisRepository {
    /** Reactive list of values for an axis (drives the CRUD screen + dropdowns). */
    fun values(axis: String): Flow<List<ZoneAxisValue>>

    suspend fun addValue(axis: String, label: String): Long

    suspend fun renameValue(id: Long, newLabel: String)

    /** Set a value's axis position (-1f..+1f). */
    suspend fun setPosition(id: Long, position: Float)

    /** Delete a value and un-assign it from all songs. */
    suspend fun deleteValue(id: Long)

    /** How many songs currently use this value (for a delete confirmation). */
    suspend fun countUsers(id: Long): Int

    /** The current tag assignment for [song], resolved to its ChainKey. */
    suspend fun tagFor(song: Song): SongZoneTag?

    /** Set (or clear, with null) the given axis value on [song]. */
    suspend fun assign(song: Song, axis: String, valueId: Long?)

    /** Frequency tier for [song], derived live from behavioral quality data. */
    suspend fun frequencyOf(song: Song): FrequencyTier

    /**
     * The 3D zone-space coordinate for a song key: (languagePos, typePos,
     * frequencyPos). Language/Type come from the assigned values' slider
     * positions (blank axis = 0, neutral center). Frequency is computed live,
     * per-song, from listening behavior. Used by both learning and ordering.
     */
    suspend fun zonePosition(songKey: String): ZonePoint

    /** Batch form of [zonePosition] for the ordering pass. */
    suspend fun zonePositions(songKeys: List<String>): Map<String, ZonePoint>

    /**
     * Normalized 0..1 zone-distance between two coordinates: raw Euclidean
     * distance divided by the cube diagonal (sqrt(12) ~= 3.46), so it lines up
     * with the roughly -1..1 listening-pull scale for blending.
     */
    fun normalizedDistance(a: ZonePoint, b: ZonePoint): Float

    suspend fun clear()
}

/** A song's point in the 3D zone-space. Each component is -1f..+1f. */
data class ZonePoint(val language: Float, val type: Float, val frequency: Float)

class ZoneAxisRepositoryImpl
@Inject
constructor(
    private val dao: ZoneAxisDao,
    private val qualityDao: QualityDao,
    private val fingerprintRepository: FingerprintRepository
) : ZoneAxisRepository {

    private suspend fun keyOf(song: Song): String {
        val result = fingerprintRepository.getCached(song)
        val fpKey = result?.let { ChainKey.of(it.fingerprint) }
        return fpKey ?: ("uid:" + song.uid.toString())
    }

    override fun values(axis: String): Flow<List<ZoneAxisValue>> = dao.valuesFor(axis)

    override suspend fun addValue(axis: String, label: String): Long {
        val clean = label.trim()
        if (clean.isEmpty()) return -1
        // De-dupe case-insensitively within the axis.
        val existing = dao.valuesForNow(axis).firstOrNull { it.label.equals(clean, true) }
        if (existing != null) return existing.id
        return dao.insertValue(
            ZoneAxisValue(axis = axis, label = clean, createdAtMs = System.currentTimeMillis()))
    }

    override suspend fun renameValue(id: Long, newLabel: String) {
        val clean = newLabel.trim()
        if (clean.isEmpty()) return
        val current = dao.valueById(id) ?: return
        dao.updateValue(current.copy(label = clean))
    }

    override suspend fun setPosition(id: Long, position: Float) {
        dao.updatePosition(id, position.coerceIn(-1f, 1f))
    }

    override suspend fun deleteValue(id: Long) {
        val value = dao.valueById(id) ?: return
        when (value.axis) {
            ZoneAxis.LANGUAGE -> dao.clearLanguageValue(id)
            ZoneAxis.TYPE -> dao.clearTypeValue(id)
        }
        dao.deleteValue(id)
    }

    override suspend fun countUsers(id: Long): Int {
        val value = dao.valueById(id) ?: return 0
        return when (value.axis) {
            ZoneAxis.LANGUAGE -> dao.countLanguageUsers(id)
            ZoneAxis.TYPE -> dao.countTypeUsers(id)
            else -> 0
        }
    }

    override suspend fun tagFor(song: Song): SongZoneTag? = dao.tagFor(keyOf(song))

    override suspend fun assign(song: Song, axis: String, valueId: Long?) {
        val key = keyOf(song)
        val existing = dao.tagFor(key)
        val updated =
            when (axis) {
                ZoneAxis.LANGUAGE ->
                    (existing ?: SongZoneTag(key, null, null)).copy(languageValueId = valueId)
                ZoneAxis.TYPE ->
                    (existing ?: SongZoneTag(key, null, null)).copy(typeValueId = valueId)
                else -> return
            }
        dao.putTag(updated)
    }

    override suspend fun frequencyOf(song: Song): FrequencyTier {
        val key = keyOf(song)
        return frequencyOfKey(key)
    }

    private suspend fun frequencyOfKey(key: String): FrequencyTier {
        val q = qualityDao.get(key) ?: return FrequencyTier.LOW_FREQUENT
        val net = q.positiveScore - q.negativeScore
        val plays = q.playCount
        val skips = q.skipCount
        val skipRate = if (plays + skips > 0) skips.toFloat() / (plays + skips) else 0f
        return when {
            // Heavily skipped and barely played -> effectively unlistenable.
            skipRate >= 0.8f && plays <= 1 -> FrequencyTier.NOT_LISTENABLE
            skipRate >= 0.5f -> FrequencyTier.SKIP_OFTEN
            net >= HIGH_FREQUENT_NET && plays >= HIGH_FREQUENT_PLAYS ->
                FrequencyTier.HIGH_FREQUENT
            else -> FrequencyTier.LOW_FREQUENT
        }
    }

    /**
     * Frequency coordinate on Z: continuous, per-song, from listening behavior.
     * r = play ratio in -1..1; shrunk toward 0 by sample size so a song with
     * little history stays near neutral center (never-played -> exactly 0, the
     * same neutral as a blank tag — the system doesn't exile the unknown, only
     * the known-disliked). Redemption is automatic: clean plays pull it back up.
     */
    private suspend fun frequencyPosition(key: String): Float {
        val q = qualityDao.get(key) ?: return 0f
        val plays = q.playCount
        val skips = q.skipCount
        val n = plays + skips
        if (n <= 0) return 0f
        val r = plays.toFloat() / n // 0..1
        val shrink = n.toFloat() / (n + FREQ_SHRINK_K)
        return ((2f * r) - 1f) * shrink // -1..1
    }

    override suspend fun zonePosition(songKey: String): ZonePoint {
        val tag = dao.tagFor(songKey)
        val lang = tag?.languageValueId?.let { dao.valueById(it)?.position } ?: 0f
        val type = tag?.typeValueId?.let { dao.valueById(it)?.position } ?: 0f
        val freq = frequencyPosition(songKey)
        return ZonePoint(lang, type, freq)
    }

    override suspend fun zonePositions(songKeys: List<String>): Map<String, ZonePoint> {
        if (songKeys.isEmpty()) return emptyMap()
        // Batch the tag lookups; resolve value positions through a small cache
        // so repeated value ids don't re-query.
        val tags = HashMap<String, SongZoneTag>()
        for (chunk in songKeys.chunked(BATCH)) {
            for (t in dao.tagsFor(chunk)) tags[t.songKey] = t
        }
        val posCache = HashMap<Long, Float>()
        suspend fun posOf(id: Long?): Float {
            if (id == null) return 0f
            return posCache.getOrPut(id) { dao.valueById(id)?.position ?: 0f }
        }
        val out = HashMap<String, ZonePoint>(songKeys.size)
        for (key in songKeys) {
            val tag = tags[key]
            out[key] =
                ZonePoint(posOf(tag?.languageValueId), posOf(tag?.typeValueId), frequencyPosition(key))
        }
        return out
    }

    override fun normalizedDistance(a: ZonePoint, b: ZonePoint): Float {
        val dl = a.language - b.language
        val dt = a.type - b.type
        val df = a.frequency - b.frequency
        val raw = kotlin.math.sqrt(dl * dl + dt * dt + df * df)
        return (raw / ZONE_DIAGONAL).coerceIn(0f, 1f)
    }

    override suspend fun clear() {
        dao.nukeTags()
        dao.nukeValues()
    }

    private companion object {
        const val HIGH_FREQUENT_NET = 3.0f
        const val HIGH_FREQUENT_PLAYS = 5
        const val FREQ_SHRINK_K = 5f
        const val BATCH = 500
        // Diagonal of the [-1,1]^3 cube: sqrt(2^2 + 2^2 + 2^2) = sqrt(12).
        val ZONE_DIAGONAL = kotlin.math.sqrt(12f)
    }
}

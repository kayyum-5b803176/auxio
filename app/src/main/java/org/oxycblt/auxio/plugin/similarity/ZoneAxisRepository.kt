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

    suspend fun clear()
}

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

    override suspend fun clear() {
        dao.nukeTags()
        dao.nukeValues()
    }

    private companion object {
        const val HIGH_FREQUENT_NET = 3.0f
        const val HIGH_FREQUENT_PLAYS = 5
    }
}

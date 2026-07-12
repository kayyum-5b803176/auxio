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

    /** Delete a value and un-assign it from all songs (and drop its relations). */
    suspend fun deleteValue(id: Long)

    /** How many songs currently use this value (for a delete confirmation). */
    suspend fun countUsers(id: Long): Int

    /** The current tag assignment for [song], resolved to its ChainKey. */
    suspend fun tagFor(song: Song): SongZoneTag?

    /** The tag assignment for an already-resolved ChainKey (ordering/learning path). */
    suspend fun tagForKey(songKey: String): SongZoneTag?

    /** Batch tag lookup for the ordering pass. */
    suspend fun tagsForKeys(songKeys: List<String>): List<SongZoneTag>

    /** Set (or clear, with null) the given axis value on [song]. */
    suspend fun assign(song: Song, axis: String, valueId: Long?)

    /** Frequency tier for [song], derived live from behavioral quality data. */
    suspend fun frequencyOf(song: Song): FrequencyTier

    /** Continuous per-song frequency signal in -1f..+1f (from play/skip history). */
    suspend fun frequencyOf(songKey: String): Float

    /**
     * The stored relative value between two axis values, -1f..+1f (positive =
     * similar/attract, negative = opposite/repel). Symmetric; unset pairs and
     * any pair involving a null id return 0f (neutral).
     */
    suspend fun relationBetween(valueIdA: Long?, valueIdB: Long?): Float

    /** Set (or clear at 0) the symmetric relative value between two values. */
    suspend fun setRelation(valueIdA: Long, valueIdB: Long, relation: Float)

    /** Every stored relation touching [valueId], as (otherValueId -> relation). */
    suspend fun relationsForValue(valueId: Long): Map<Long, Float>

    /** All stored relations as a lookup map keyed by canonical (low, high) pair. */
    suspend fun allRelations(): Map<Pair<Long, Long>, Float>

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
        dao.deleteRelationsFor(id)
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

    override suspend fun tagForKey(songKey: String): SongZoneTag? = dao.tagFor(songKey)

    override suspend fun tagsForKeys(songKeys: List<String>): List<SongZoneTag> {
        if (songKeys.isEmpty()) return emptyList()
        val out = ArrayList<SongZoneTag>()
        for (chunk in songKeys.chunked(BATCH)) out.addAll(dao.tagsFor(chunk))
        return out
    }

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

    override suspend fun frequencyOf(songKey: String): Float = frequencyPosition(songKey)

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
     * Continuous per-song frequency signal in -1..1 from listening behavior.
     * r = play ratio; shrunk toward 0 by sample size so a song with little
     * history stays near neutral center (never-played -> exactly 0). Used as a
     * within-ring ordering signal, never a gate.
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

    override suspend fun relationBetween(valueIdA: Long?, valueIdB: Long?): Float {
        if (valueIdA == null || valueIdB == null || valueIdA == valueIdB) return 0f
        val low = minOf(valueIdA, valueIdB)
        val high = maxOf(valueIdA, valueIdB)
        return dao.relation(low, high)?.relation ?: 0f
    }

    override suspend fun setRelation(valueIdA: Long, valueIdB: Long, relation: Float) {
        if (valueIdA == valueIdB) return
        val low = minOf(valueIdA, valueIdB)
        val high = maxOf(valueIdA, valueIdB)
        val clamped = relation.coerceIn(-1f, 1f)
        // Storing exactly 0 (neutral) is the same as having no row — keep the
        // table sparse by deleting instead.
        if (clamped == 0f) {
            dao.deleteRelation(low, high)
        } else {
            dao.putRelation(ZoneRelation(low, high, clamped))
        }
    }

    override suspend fun relationsForValue(valueId: Long): Map<Long, Float> {
        val out = HashMap<Long, Float>()
        for (r in dao.relationsFor(valueId)) {
            val other = if (r.valueIdLow == valueId) r.valueIdHigh else r.valueIdLow
            out[other] = r.relation
        }
        return out
    }

    override suspend fun allRelations(): Map<Pair<Long, Long>, Float> {
        val out = HashMap<Pair<Long, Long>, Float>()
        for (r in dao.allRelations()) out[r.valueIdLow to r.valueIdHigh] = r.relation
        return out
    }

    override suspend fun clear() {
        dao.nukeTags()
        dao.nukeRelations()
        dao.nukeValues()
    }

    private companion object {
        const val HIGH_FREQUENT_NET = 3.0f
        const val HIGH_FREQUENT_PLAYS = 5
        const val FREQ_SHRINK_K = 5f
        const val BATCH = 500
    }
}

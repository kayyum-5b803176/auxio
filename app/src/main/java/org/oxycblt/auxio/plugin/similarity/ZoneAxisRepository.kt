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

    /**
     * The EXPLICIT axis values for a song key (no inheritance). Returns
     * (languageValueId, typeValueId), either may be null.
     */
    suspend fun explicitTags(songKey: String): Pair<Long?, Long?>

    /**
     * Whether two song keys may be chained under Rule A using only EXPLICIT
     * tags — used at learning time. Incompatible iff some axis where BOTH have
     * an explicit value disagrees. Blank axes never conflict.
     */
    suspend fun explicitlyCompatible(aKey: String, bKey: String): Boolean

    /**
     * Effective tags (explicit + inherited via lineage walk-up) for a song key.
     * Explicit wins; inherited fills only blank axes. Used at ordering time.
     */
    suspend fun effectiveTags(songKey: String): Pair<Long?, Long?>

    /**
     * Whether [candidateKey] may follow [currentKey] under Rule A using EFFECTIVE
     * tags — used at ordering time.
     */
    suspend fun effectivelyCompatible(currentKey: String, candidateKey: String): Boolean

    /** Record/update a song's chain lineage (strongest/most-recent ancestor). */
    suspend fun recordLineage(songKey: String, ancestorKey: String, edgeStrength: Float)

    suspend fun clear()
}

class ZoneAxisRepositoryImpl
@Inject
constructor(
    private val dao: ZoneAxisDao,
    private val qualityDao: QualityDao,
    private val lineageDao: LineageDao,
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

    override suspend fun explicitTags(songKey: String): Pair<Long?, Long?> {
        val tag = dao.tagFor(songKey) ?: return null to null
        return tag.languageValueId to tag.typeValueId
    }

    override suspend fun explicitlyCompatible(aKey: String, bKey: String): Boolean {
        val (aLang, aType) = explicitTags(aKey)
        val (bLang, bType) = explicitTags(bKey)
        return axesAgree(aLang, bLang) && axesAgree(aType, bType)
    }

    override suspend fun effectiveTags(songKey: String): Pair<Long?, Long?> {
        val (explicitLang, explicitType) = explicitTags(songKey)
        // Both axes explicit -> no inheritance needed.
        if (explicitLang != null && explicitType != null) return explicitLang to explicitType

        // Walk lineage upward to fill blank axes, healing over deleted ancestors.
        var lang = explicitLang
        var type = explicitType
        var cursorKey = songKey
        val seen = HashSet<String>()
        var hops = 0
        while ((lang == null || type == null) && hops < MAX_LINEAGE_HOPS) {
            if (!seen.add(cursorKey)) break // cycle guard
            val lineage = lineageDao.get(cursorKey) ?: break
            var ancestorKey = lineage.ancestorKey
            // Walk-up over deleted ancestors: if the ancestor has no data of its
            // own (no tag AND no embedding), treat it as gone and follow ITS
            // lineage to the next surviving ancestor.
            var guard = 0
            while (guard < MAX_LINEAGE_HOPS && isMissing(ancestorKey)) {
                val next = lineageDao.get(ancestorKey) ?: break
                ancestorKey = next.ancestorKey
                guard++
            }
            if (isMissing(ancestorKey)) break
            val (ancLang, ancType) = explicitTags(ancestorKey)
            if (lang == null) lang = ancLang
            if (type == null) type = ancType
            cursorKey = ancestorKey
            hops++
        }
        return lang to type
    }

    override suspend fun effectivelyCompatible(currentKey: String, candidateKey: String): Boolean {
        val (curLang, curType) = effectiveTags(currentKey)
        val (candLang, candType) = effectiveTags(candidateKey)
        return axesAgree(curLang, candLang) && axesAgree(curType, candType)
    }

    override suspend fun recordLineage(songKey: String, ancestorKey: String, edgeStrength: Float) {
        if (songKey == ancestorKey) return
        val now = System.currentTimeMillis()
        val existing = lineageDao.get(songKey)
        // Keep the STRONGEST ancestor; most-recent breaks ties (>=).
        if (existing == null || edgeStrength >= existing.edgeStrength) {
            lineageDao.put(SongLineage(songKey, ancestorKey, edgeStrength, now))
        }
    }

    /** A key is "missing" if it has neither a tag nor an embedding row. */
    private suspend fun isMissing(key: String): Boolean {
        if (dao.tagFor(key) != null) return false
        return qualityDao.get(key) == null && lineageDao.get(key) == null
    }

    /** Two axis values agree unless BOTH are set and differ (Rule A). */
    private fun axesAgree(a: Long?, b: Long?): Boolean = a == null || b == null || a == b

    override suspend fun clear() {
        dao.nukeTags()
        dao.nukeValues()
        lineageDao.nuke()
    }

    private companion object {
        const val HIGH_FREQUENT_NET = 3.0f
        const val HIGH_FREQUENT_PLAYS = 5
        const val MAX_LINEAGE_HOPS = 32
    }
}

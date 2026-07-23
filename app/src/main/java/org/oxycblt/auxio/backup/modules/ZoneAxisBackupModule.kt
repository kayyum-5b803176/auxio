/*
 * Copyright (c) 2026 Auxio Project
 * ZoneAxisBackupModule.kt is part of Auxio.
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

package org.oxycblt.auxio.backup.modules

import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.backup.BackupModule
import org.oxycblt.auxio.backup.Conflict
import org.oxycblt.auxio.backup.ConflictResolution
import org.oxycblt.auxio.backup.MergeResult
import org.oxycblt.auxio.backup.MergeUtil.floatsConflict
import org.oxycblt.auxio.plugin.similarity.SongZoneTag
import org.oxycblt.auxio.plugin.similarity.ZoneAxisDao
import org.oxycblt.auxio.plugin.similarity.ZoneAxisValue
import org.oxycblt.auxio.plugin.similarity.ZoneRelation

/**
 * Backs up Zone Axis: the user-authored Language/Type value lists, each
 * song's tag assignment, and the sparse pairwise relation values between
 * axis values.
 *
 * Axis values are matched across devices by (axis, label) case-insensitively
 * — there is no other stable cross-device identity for a user-typed label,
 * since the numeric [ZoneAxisValue.id] is a local autoincrement. Song tags
 * are matched by [org.oxycblt.auxio.plugin.similarity.ChainKey], same as
 * Smart Chain, so a tag follows the recording across devices/libraries.
 *
 * This module raises real [Conflict]s (unlike Smart Chain, which can always
 * combine numerically) because a tag slot or a relation value is a single
 * discrete choice with no sound way to "average" two different answers.
 */
class ZoneAxisBackupModule @Inject constructor(private val dao: ZoneAxisDao) : BackupModule {
    override val id = "zone_axis"
    override val displayName = "Zone Axis"
    override val schemaVersion = 1

    override suspend fun hasData(): Boolean = dao.allValues().isNotEmpty()

    override suspend fun export(): JSONObject {
        val values = dao.allValues()
        val allTags = dao.allTags()
        val relations = dao.allRelations()

        return JSONObject().apply {
            put(
                "values",
                JSONArray().apply {
                    values.forEach { v ->
                        put(
                            JSONObject().apply {
                                put("localId", v.id)
                                put("axis", v.axis)
                                put("label", v.label)
                                put("position", v.position.toDouble())
                                put("createdAtMs", v.createdAtMs)
                            })
                    }
                })
            put(
                "tags",
                JSONArray().apply {
                    allTags.forEach { t ->
                        val languageLabel = t.languageValueId?.let { id -> values.find { it.id == id } }
                        val typeLabel = t.typeValueId?.let { id -> values.find { it.id == id } }
                        put(
                            JSONObject().apply {
                                put("songKey", t.songKey)
                                put("languageLabel", languageLabel?.label ?: JSONObject.NULL)
                                put("typeLabel", typeLabel?.label ?: JSONObject.NULL)
                            })
                    }
                })
            put(
                "relations",
                JSONArray().apply {
                    relations.forEach { r ->
                        val low = values.find { it.id == r.valueIdLow }
                        val high = values.find { it.id == r.valueIdHigh }
                        if (low != null && high != null) {
                            put(
                                JSONObject().apply {
                                    put("axis", low.axis)
                                    put("labelLow", low.label)
                                    put("labelHigh", high.label)
                                    put("relation", r.relation.toDouble())
                                })
                        }
                    }
                })
        }
    }

    override suspend fun merge(
        incoming: JSONObject,
        incomingSchemaVersion: Int,
        resolutions: Map<String, ConflictResolution>,
        apply: Boolean
    ): MergeResult {
        var added = 0
        var updated = 0
        var unchanged = 0
        val conflicts = mutableListOf<Conflict>()

        // ---- axis values: union by (axis, label) case-insensitively -----
        val currentValues = dao.allValues()
        // axis|lowercase(label) -> current value, for O(1) lookup while merging.
        val currentByKey = currentValues.associateBy { valueKey(it.axis, it.label) }
        // Track the resolved id for every (axis, label) after this pass, so
        // the tags/relations sections below can resolve labels to ids even
        // for values that were just newly inserted in this same run.
        val resolvedIds = HashMap<String, Long>()
        currentValues.forEach { resolvedIds[valueKey(it.axis, it.label)] = it.id }

        val incomingValues = incoming.optJSONArray("values") ?: JSONArray()
        for (i in 0 until incomingValues.length()) {
            val row = incomingValues.getJSONObject(i)
            val axis = row.getString("axis")
            val label = row.getString("label")
            val incomingPosition = row.getDouble("position").toFloat()
            val key = valueKey(axis, label)
            val existing = currentByKey[key]
            if (existing == null) {
                added++
                if (apply) {
                    val newId = dao.insertValue(ZoneAxisValue(axis = axis, label = label, position = incomingPosition, createdAtMs = row.getLong("createdAtMs")))
                    resolvedIds[key] = newId
                }
            } else if (floatsConflict(existing.position, incomingPosition)) {
                val conflictKey = "value_position:$key"
                val resolution = resolutions[conflictKey]
                if (resolution == null) {
                    conflicts.add(
                        Conflict(
                            conflictKey = conflictKey,
                            moduleId = id,
                            description = "Zone value \"$label\" ($axis) has a different position on each device",
                            currentValue = "%.2f".format(existing.position),
                            incomingValue = "%.2f".format(incomingPosition)))
                } else {
                    updated++
                    if (apply && resolution == ConflictResolution.USE_INCOMING) {
                        dao.updatePosition(existing.id, incomingPosition)
                    }
                }
            } else {
                unchanged++
            }
        }

        // ---- tags: only add where destination has no tag for that song ---
        // If a song is tagged on both sides identically, nothing to do. If
        // tagged differently, this is one of the two genuine conflict cases.
        val incomingTags = incoming.optJSONArray("tags") ?: JSONArray()
        val currentTagsByKey = dao.allTags().associateBy { it.songKey }
        for (i in 0 until incomingTags.length()) {
            val row = incomingTags.getJSONObject(i)
            val songKey = row.getString("songKey")
            val incomingLangLabel = if (row.isNull("languageLabel")) null else row.getString("languageLabel")
            val incomingTypeLabel = if (row.isNull("typeLabel")) null else row.getString("typeLabel")
            val incomingLangId =
                incomingLangLabel?.let { resolvedIds[valueKey(org.oxycblt.auxio.plugin.similarity.ZoneAxis.LANGUAGE, it)] }
            val incomingTypeId =
                incomingTypeLabel?.let { resolvedIds[valueKey(org.oxycblt.auxio.plugin.similarity.ZoneAxis.TYPE, it)] }

            val existing = currentTagsByKey[songKey]
            if (existing == null) {
                added++
                if (apply) {
                    dao.putTag(SongZoneTag(songKey = songKey, languageValueId = incomingLangId, typeValueId = incomingTypeId))
                }
                continue
            }

            var mergedLang = existing.languageValueId
            var mergedType = existing.typeValueId
            var changed = false

            if (existing.languageValueId == null && incomingLangId != null) {
                mergedLang = incomingLangId
                changed = true
            } else if (existing.languageValueId != null &&
                incomingLangId != null &&
                existing.languageValueId != incomingLangId) {
                val conflictKey = "tag_language:$songKey"
                val resolution = resolutions[conflictKey]
                if (resolution == null) {
                    conflicts.add(
                        Conflict(
                            conflictKey = conflictKey,
                            moduleId = id,
                            description = "A song is tagged with different Language values on each device",
                            currentValue = existing.languageValueId.toString(),
                            incomingValue = incomingLangId.toString()))
                } else if (resolution == ConflictResolution.USE_INCOMING) {
                    mergedLang = incomingLangId
                    changed = true
                }
            }

            if (existing.typeValueId == null && incomingTypeId != null) {
                mergedType = incomingTypeId
                changed = true
            } else if (existing.typeValueId != null &&
                incomingTypeId != null &&
                existing.typeValueId != incomingTypeId) {
                val conflictKey = "tag_type:$songKey"
                val resolution = resolutions[conflictKey]
                if (resolution == null) {
                    conflicts.add(
                        Conflict(
                            conflictKey = conflictKey,
                            moduleId = id,
                            description = "A song is tagged with different Type values on each device",
                            currentValue = existing.typeValueId.toString(),
                            incomingValue = incomingTypeId.toString()))
                } else if (resolution == ConflictResolution.USE_INCOMING) {
                    mergedType = incomingTypeId
                    changed = true
                }
            }

            if (changed) {
                updated++
                if (apply) dao.putTag(existing.copy(languageValueId = mergedLang, typeValueId = mergedType))
            } else {
                unchanged++
            }
        }

        // ---- relations: union; conflict only if both sides set a differing value ----
        val incomingRelations = incoming.optJSONArray("relations") ?: JSONArray()
        val currentRelations = dao.allRelations()
        for (i in 0 until incomingRelations.length()) {
            val row = incomingRelations.getJSONObject(i)
            val axis = row.getString("axis")
            val lowId = resolvedIds[valueKey(axis, row.getString("labelLow"))]
            val highId = resolvedIds[valueKey(axis, row.getString("labelHigh"))]
            if (lowId == null || highId == null) continue // referenced value wasn't found/created; skip safely
            val (canonLow, canonHigh) = if (lowId <= highId) lowId to highId else highId to lowId
            val incomingValue = row.getDouble("relation").toFloat()
            val existing = currentRelations.find { it.valueIdLow == canonLow && it.valueIdHigh == canonHigh }
            if (existing == null) {
                added++
                if (apply) dao.putRelation(ZoneRelation(canonLow, canonHigh, incomingValue))
            } else if (floatsConflict(existing.relation, incomingValue)) {
                val conflictKey = "relation:$canonLow:$canonHigh"
                val resolution = resolutions[conflictKey]
                if (resolution == null) {
                    conflicts.add(
                        Conflict(
                            conflictKey = conflictKey,
                            moduleId = id,
                            description = "A zone relation has a different value on each device",
                            currentValue = "%.2f".format(existing.relation),
                            incomingValue = "%.2f".format(incomingValue)))
                } else {
                    updated++
                    if (apply && resolution == ConflictResolution.USE_INCOMING) {
                        dao.putRelation(ZoneRelation(canonLow, canonHigh, incomingValue))
                    }
                }
            } else {
                unchanged++
            }
        }

        return MergeResult(added = added, updated = updated, unchanged = unchanged, conflicts = conflicts)
    }

    private fun valueKey(axis: String, label: String) = "$axis|${label.lowercase()}"
}

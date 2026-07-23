/*
 * Copyright (c) 2026 Auxio Project
 * FingerprintCacheBackupModule.kt is part of Auxio.
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
import org.oxycblt.auxio.backup.ConflictResolution
import org.oxycblt.auxio.backup.MergeResult
import org.oxycblt.auxio.backup.MergeUtil.toIntArray
import org.oxycblt.auxio.backup.MergeUtil.toJsonArray
import org.oxycblt.auxio.backup.MergeUtil.toFloatArray
import org.oxycblt.auxio.backup.MergeUtil.toJson
import org.oxycblt.auxio.plugin.similarity.FingerprintDao
import org.oxycblt.auxio.plugin.similarity.FingerprintEntity
import org.oxycblt.musikr.Music

/**
 * Backs up the cached acoustic fingerprints, so a fresh install (or a
 * device that just restored a backup) doesn't have to re-scan and
 * re-fingerprint the whole library before Smart Chain / duplicate
 * detection can use acoustic data again.
 *
 * This is purely a performance cache with no user decisions in it — a
 * missing or stale entry just costs a re-scan, never data loss — so the
 * merge rule is simple: per song [Music.UID], keep whichever side's entry
 * is newer by [FingerprintEntity.modifiedMs] (a newer mtime means a
 * different/updated file, so its analysis is the one worth keeping), and on
 * an exact tie prefer the higher [FingerprintEntity.algorithmVersion].
 * Because [Music.UID] for a song is derived from stable file metadata (see
 * `Music.UID.auxio(item) { ... }`), it stays meaningful across devices that
 * share the same library content — while a UID present on the importing
 * device isn't required for this to be safe: unmatched rows just sit ready
 * for a future song with that same UID.
 */
class FingerprintCacheBackupModule @Inject constructor(private val dao: FingerprintDao) : BackupModule {
    override val id = "fingerprint_cache"
    override val displayName = "Fingerprint cache"
    override val schemaVersion = 1

    override suspend fun hasData(): Boolean = dao.getAll().isNotEmpty()

    override suspend fun export(): JSONObject {
        val all = dao.getAll()
        return JSONObject().apply {
            put(
                "entries",
                JSONArray().apply {
                    all.forEach { e ->
                        put(
                            JSONObject().apply {
                                put("uid", e.uid.toString())
                                put("modifiedMs", e.modifiedMs)
                                put("algorithmVersion", e.algorithmVersion)
                                put("fingerprint", e.fingerprint.toJsonArray())
                                put("spectralProfile", e.spectralProfile.toJson())
                            })
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

        val entries = incoming.optJSONArray("entries") ?: JSONArray()
        val toUpsert = mutableListOf<FingerprintEntity>()

        for (i in 0 until entries.length()) {
            val row = entries.getJSONObject(i)
            val uid = Music.UID.fromString(row.getString("uid")) ?: continue
            val incomingModifiedMs = row.getLong("modifiedMs")
            val incomingAlgorithmVersion = row.getInt("algorithmVersion")
            val existing = dao.get(uid)

            val shouldReplace =
                existing == null ||
                    incomingModifiedMs > existing.modifiedMs ||
                    (incomingModifiedMs == existing.modifiedMs &&
                        incomingAlgorithmVersion > existing.algorithmVersion)

            when {
                existing == null -> added++
                shouldReplace -> updated++
                else -> unchanged++
            }

            if (shouldReplace) {
                toUpsert.add(
                    FingerprintEntity(
                        uid = uid,
                        modifiedMs = incomingModifiedMs,
                        algorithmVersion = incomingAlgorithmVersion,
                        fingerprint = row.getJSONArray("fingerprint").toIntArray(),
                        spectralProfile = row.getJSONArray("spectralProfile").toFloatArray()))
            }
        }

        if (apply && toUpsert.isNotEmpty()) dao.insertAll(toUpsert)

        return MergeResult(added = added, updated = updated, unchanged = unchanged)
    }
}

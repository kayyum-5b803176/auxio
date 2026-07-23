/*
 * Copyright (c) 2026 Auxio Project
 * SmartChainBackupModule.kt is part of Auxio.
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
import org.oxycblt.auxio.backup.MergeUtil.toFloatArray
import org.oxycblt.auxio.backup.MergeUtil.toJson
import org.oxycblt.auxio.backup.MergeUtil.weightedAverage
import org.oxycblt.auxio.plugin.similarity.ChainLogDao
import org.oxycblt.auxio.plugin.similarity.ChainLogEntry
import org.oxycblt.auxio.plugin.similarity.EmbeddingDao
import org.oxycblt.auxio.plugin.similarity.QualityDao
import org.oxycblt.auxio.plugin.similarity.LineageDao
import org.oxycblt.auxio.plugin.similarity.SongEmbedding
import org.oxycblt.auxio.plugin.similarity.SongLineage
import org.oxycblt.auxio.plugin.similarity.SongQuality
import org.oxycblt.auxio.plugin.similarity.TransitionDao
import org.oxycblt.auxio.plugin.similarity.TransitionEdge

/**
 * Backs up everything Smart Chain has learned: song embeddings (the latent
 * space vectors), per-song quality scores, chain lineage (for Zone Axis
 * inheritance), the directed transition graph, and the human-readable
 * learning log.
 *
 * All rows are keyed by [org.oxycblt.auxio.plugin.similarity.ChainKey],
 * which is derived from the song's acoustic fingerprint rather than any
 * device- or file-specific id — that's what makes merging two devices'
 * Smart Chain data meaningful: the same recording produces the same key
 * everywhere, so device-B recognizes device-A's rows as being about songs
 * it may also have. Rows whose key happens to be a device-local `uid:`
 * fallback (fingerprinting failed) merge with a plain union instead, since
 * there's no way to know if they refer to the same song.
 */
class SmartChainBackupModule
@Inject
constructor(
    private val embeddingDao: EmbeddingDao,
    private val qualityDao: QualityDao,
    private val lineageDao: LineageDao,
    private val transitionDao: TransitionDao,
    private val chainLogDao: ChainLogDao
) : BackupModule {
    override val id = "smart_chain"
    override val displayName = "Smart Chain"
    override val schemaVersion = 1

    override suspend fun hasData(): Boolean = embeddingDao.any()

    override suspend fun export(): JSONObject {
        val embeddings = embeddingDao.all()
        val qualities = qualityDao.getAll(embeddings.map { it.key })
        val qualityByKey = qualities.associateBy { it.key }
        val lineages = embeddings.mapNotNull { lineageDao.get(it.key) }
        val transitions = mutableListOf<TransitionEdge>()
        for (e in embeddings) transitions.addAll(transitionDao.outgoingFromNow(e.key))

        return JSONObject().apply {
            put(
                "embeddings",
                JSONArray().apply {
                    embeddings.forEach { emb ->
                        put(
                            JSONObject().apply {
                                put("key", emb.key)
                                put("vector", emb.vector.toJson())
                                put("observationCount", emb.observationCount)
                                put("lastUpdatedMs", emb.lastUpdatedMs)
                                put("acousticSeeded", emb.acousticSeeded)
                            })
                    }
                })
            put(
                "qualities",
                JSONArray().apply {
                    qualityByKey.values.forEach { q ->
                        put(
                            JSONObject().apply {
                                put("key", q.key)
                                put("positiveScore", q.positiveScore.toDouble())
                                put("negativeScore", q.negativeScore.toDouble())
                                put("playCount", q.playCount)
                                put("skipCount", q.skipCount)
                                put("likeCount", q.likeCount)
                                put("lastUpdatedMs", q.lastUpdatedMs)
                            })
                    }
                })
            put(
                "lineages",
                JSONArray().apply {
                    lineages.forEach { l ->
                        put(
                            JSONObject().apply {
                                put("songKey", l.songKey)
                                put("ancestorKey", l.ancestorKey)
                                put("edgeStrength", l.edgeStrength.toDouble())
                                put("updatedMs", l.updatedMs)
                            })
                    }
                })
            put(
                "transitions",
                JSONArray().apply {
                    transitions.forEach { t ->
                        put(
                            JSONObject().apply {
                                put("fromKey", t.fromKey)
                                put("toKey", t.toKey)
                                put("toName", t.toName)
                                put("plays", t.plays)
                                put("skips", t.skips)
                                put("updatedAtMs", t.updatedAtMs)
                            })
                    }
                })
            put(
                "logs",
                JSONArray().apply {
                    // Logs are informational history, not learned data; cap
                    // export at the same capacity the log itself keeps.
                    chainLogDao.recentSnapshot(LOG_EXPORT_CAP).forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("timestampMs", entry.timestampMs)
                                put("message", entry.message)
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
        // Smart Chain data always merges numerically (weighted-average
        // vectors, summed counters, keep-strongest lineage), so this module
        // never produces a conflict the user must resolve.

        // ---- embeddings: weighted-average merge, counts sum -------------
        val incomingEmbeddings = incoming.optJSONArray("embeddings") ?: JSONArray()
        for (i in 0 until incomingEmbeddings.length()) {
            val row = incomingEmbeddings.getJSONObject(i)
            val key = row.getString("key")
            val incomingVector = row.getJSONArray("vector").toFloatArray()
            val incomingCount = row.getInt("observationCount")
            val existing = embeddingDao.get(key)
            if (existing == null) {
                added++
                if (apply) {
                    embeddingDao.put(
                        SongEmbedding(
                            key = key,
                            vector = incomingVector,
                            observationCount = incomingCount,
                            lastUpdatedMs = row.getLong("lastUpdatedMs"),
                            acousticSeeded = row.optBoolean("acousticSeeded", false)))
                }
            } else {
                updated++
                if (apply) {
                    val merged =
                        weightedAverage(
                            existing.vector, existing.observationCount, incomingVector, incomingCount)
                    embeddingDao.put(
                        existing.copy(
                            vector = merged,
                            observationCount = existing.observationCount + incomingCount,
                            lastUpdatedMs = maxOf(existing.lastUpdatedMs, row.getLong("lastUpdatedMs")),
                            acousticSeeded =
                                existing.acousticSeeded || row.optBoolean("acousticSeeded", false)))
                }
            }
        }

        // ---- qualities: sum every counter, add scores --------------------
        val incomingQualities = incoming.optJSONArray("qualities") ?: JSONArray()
        for (i in 0 until incomingQualities.length()) {
            val row = incomingQualities.getJSONObject(i)
            val key = row.getString("key")
            val existing = qualityDao.get(key)
            if (existing == null) {
                added++
                if (apply) {
                    qualityDao.insert(
                        SongQuality(
                            key = key,
                            positiveScore = row.getDouble("positiveScore").toFloat(),
                            negativeScore = row.getDouble("negativeScore").toFloat(),
                            playCount = row.getInt("playCount"),
                            skipCount = row.getInt("skipCount"),
                            likeCount = row.getInt("likeCount"),
                            lastUpdatedMs = row.getLong("lastUpdatedMs")))
                }
            } else {
                updated++
                if (apply) {
                    qualityDao.fold(
                        key = key,
                        posDelta = row.getDouble("positiveScore").toFloat(),
                        negDelta = row.getDouble("negativeScore").toFloat(),
                        playInc = row.getInt("playCount"),
                        skipInc = row.getInt("skipCount"),
                        likeInc = row.getInt("likeCount"),
                        now = maxOf(existing.lastUpdatedMs, row.getLong("lastUpdatedMs")))
                }
            }
        }

        // ---- lineage: keep the strongest edge, newest breaks ties -------
        val incomingLineages = incoming.optJSONArray("lineages") ?: JSONArray()
        for (i in 0 until incomingLineages.length()) {
            val row = incomingLineages.getJSONObject(i)
            val songKey = row.getString("songKey")
            val incomingStrength = row.getDouble("edgeStrength").toFloat()
            val incomingUpdatedMs = row.getLong("updatedMs")
            val existing = lineageDao.get(songKey)
            val shouldReplace =
                existing == null ||
                    incomingStrength > existing.edgeStrength ||
                    (incomingStrength == existing.edgeStrength && incomingUpdatedMs > existing.updatedMs)
            if (existing == null) added++ else if (shouldReplace) updated++ else unchanged++
            if (apply && shouldReplace) {
                lineageDao.put(
                    SongLineage(
                        songKey = songKey,
                        ancestorKey = row.getString("ancestorKey"),
                        edgeStrength = incomingStrength,
                        updatedMs = incomingUpdatedMs))
            }
        }

        // ---- transitions: sum plays/skips per (from,to) edge -------------
        val incomingTransitions = incoming.optJSONArray("transitions") ?: JSONArray()
        for (i in 0 until incomingTransitions.length()) {
            val row = incomingTransitions.getJSONObject(i)
            val from = row.getString("fromKey")
            val to = row.getString("toKey")
            val existing = transitionDao.edge(from, to)
            if (existing == null) added++ else updated++
            if (apply) {
                transitionDao.upsertDelta(
                    from = from,
                    to = to,
                    toName = row.getString("toName"),
                    plays = row.getInt("plays"),
                    skips = row.getInt("skips"),
                    now = row.getLong("updatedAtMs"))
            }
        }

        // ---- logs: union by (timestamp, message), re-trim to capacity ---
        val incomingLogs = incoming.optJSONArray("logs") ?: JSONArray()
        if (apply && incomingLogs.length() > 0) {
            for (i in 0 until incomingLogs.length()) {
                val row = incomingLogs.getJSONObject(i)
                chainLogDao.insert(
                    ChainLogEntry(timestampMs = row.getLong("timestampMs"), message = row.getString("message")))
            }
            chainLogDao.trimTo(LOG_EXPORT_CAP)
        }

        return MergeResult(added = added, updated = updated, unchanged = unchanged)
    }

    private companion object {
        const val LOG_EXPORT_CAP = 100
    }
}

/*
 * Copyright (c) 2026 Auxio Project
 * FingerprintRepository.kt is part of Auxio.
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

/**
 * Mediates between the in-memory scan and the persistent [FingerprintDatabase].
 *
 * A cached entry is considered valid only if BOTH the file's modified time and
 * the fingerprint algorithm version still match. Anything else is a miss and
 * must be recomputed by the caller.
 */
interface FingerprintRepository {
    /**
     * Load a valid cached result for [song], or null on a miss (never
     * analyzed, file changed since, or algorithm changed).
     *
     * A cached result with an empty fingerprint is a legitimate cache HIT
     * (the file was analyzed but produced no usable fingerprint) — callers
     * should not retry it.
     */
    suspend fun getCached(song: Song): FingerprintResult?

    /**
     * Batched [getCached] for many songs at once. Returns a map keyed by the
     * song's UID string containing only valid cache HITS. Use this instead of
     * calling [getCached] in a loop — one-query-per-song was the dominant CPU
     * cost of computing a chain shuffle order over a large library.
     */
    suspend fun getCachedBatch(songs: List<Song>): Map<String, FingerprintResult>

    /** Persist a freshly computed result (empty fingerprint allowed). */
    suspend fun put(song: Song, result: FingerprintResult)

    /** Drop cache rows for songs no longer present. */
    suspend fun prune(liveSongs: Collection<Song>)

    /** Clear the entire fingerprint cache. */
    suspend fun clear()
}

class FingerprintRepositoryImpl
@Inject
constructor(private val dao: FingerprintDao) : FingerprintRepository {

    override suspend fun getCached(song: Song): FingerprintResult? {
        val entity = dao.get(song.uid) ?: return null
        val fresh =
            entity.modifiedMs == song.modifiedMs &&
                entity.algorithmVersion == AudioFingerprinterImpl.FINGERPRINT_ALGORITHM_VERSION
        return if (fresh) FingerprintResult(entity.fingerprint, entity.spectralProfile) else null
    }

    override suspend fun getCachedBatch(songs: List<Song>): Map<String, FingerprintResult> {
        if (songs.isEmpty()) return emptyMap()
        // Freshness (modifiedMs) is per-song, so index songs by uid first.
        val songByUid = HashMap<String, Song>(songs.size)
        for (s in songs) songByUid[s.uid.toString()] = s
        val out = HashMap<String, FingerprintResult>(songs.size)
        for (chunk in songs.map { it.uid }.distinct().chunked(BATCH)) {
            for (entity in dao.getAll(chunk)) {
                val uidStr = entity.uid.toString()
                val song = songByUid[uidStr] ?: continue
                val fresh =
                    entity.modifiedMs == song.modifiedMs &&
                        entity.algorithmVersion ==
                            AudioFingerprinterImpl.FINGERPRINT_ALGORITHM_VERSION
                if (fresh) {
                    out[uidStr] = FingerprintResult(entity.fingerprint, entity.spectralProfile)
                }
            }
        }
        return out
    }

    override suspend fun put(song: Song, result: FingerprintResult) {
        dao.insert(
            FingerprintEntity(
                uid = song.uid,
                modifiedMs = song.modifiedMs,
                algorithmVersion = AudioFingerprinterImpl.FINGERPRINT_ALGORITHM_VERSION,
                fingerprint = result.fingerprint,
                spectralProfile = result.spectralProfile))
    }

    override suspend fun prune(liveSongs: Collection<Song>) {
        dao.pruneMissing(liveSongs.map { it.uid })
    }

    override suspend fun clear() {
        dao.nuke()
    }

    private companion object {
        // Stay well under SQLite's default 999 bound-variable limit.
        const val BATCH = 500
    }
}

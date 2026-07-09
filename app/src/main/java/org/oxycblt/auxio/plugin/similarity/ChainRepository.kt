/*
 * Copyright (c) 2026 Auxio Project
 * ChainRepository.kt is part of Auxio.
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
 * Records and queries the behavioral chain. Resolves songs to fingerprint-based
 * keys (via the fingerprint cache + [ChainKey]) so all learning is owned by the
 * music, not the file.
 */
interface ChainRepository {
    /**
     * Learn a transition: [from] was playing, then [to] played and was listened
     * to for [listenedFraction] (0f = skipped instantly, 1f = played fully).
     * The link strength is weighted by that fraction, so good follows
     * accumulate strength and early-skipped follows barely register.
     */
    suspend fun recordTransition(from: Song, to: Song, listenedFraction: Float)

    /**
     * Proven followers of [song], strongest first — the pool manual play picks
     * from. Empty if nothing has been learned yet (caller falls back).
     */
    suspend fun provenFollowers(song: Song): List<FollowerSong>

    /** Clear all learned chain data. */
    suspend fun clear()

    /** A learned follower key with its strength (resolved to a real Song by the caller). */
    data class Follower(val toKey: String, val strength: Float)

    /** A follower already resolved to a concrete Song in the current library. */
    data class FollowerSong(val song: Song, val strength: Float)
}

class ChainRepositoryImpl
@Inject
constructor(
    private val dao: ChainDao,
    private val fingerprintRepository: FingerprintRepository,
    private val musicRepository: org.oxycblt.auxio.music.MusicRepository
) : ChainRepository {

    private suspend fun keyOf(song: Song): String? {
        val result = fingerprintRepository.getCached(song) ?: return null
        return ChainKey.of(result.fingerprint)
    }

    override suspend fun recordTransition(from: Song, to: Song, listenedFraction: Float) {
        val fromKey = keyOf(from) ?: return
        val toKey = keyOf(to) ?: return
        if (fromKey == toKey) return // don't chain a song to itself

        // Completion-weighted strength delta. An early skip contributes a small
        // NEGATIVE amount (mild discouragement), a full listen a full positive.
        val delta = (listenedFraction - SKIP_THRESHOLD) * STRENGTH_SCALE

        val updated = dao.reinforce(fromKey, toKey, delta)
        if (updated == 0) {
            dao.insert(
                ChainTransition(
                    fromKey = fromKey,
                    toKey = toKey,
                    strength = delta.coerceAtLeast(0f),
                    count = 1))
        }
    }

    override suspend fun provenFollowers(song: Song): List<ChainRepository.FollowerSong> {
        val fromKey = keyOf(song) ?: return emptyList()
        val followers = dao.followersOf(fromKey).filter { it.strength > 0f }
        if (followers.isEmpty()) return emptyList()

        // Resolve follower keys back to concrete songs in the CURRENT library.
        // A key may resolve to multiple files (duplicates); pick any present
        // one. Keys with no present file are silently dropped (the music was
        // removed) — its learning is retained in the DB for if it returns.
        val library = musicRepository.library ?: return emptyList()
        val byKey = mutableMapOf<String, Song>()
        for (s in library.songs) {
            val k = keyOf(s) ?: continue
            byKey.putIfAbsent(k, s)
        }
        return followers.mapNotNull { t ->
            byKey[t.toKey]?.let { ChainRepository.FollowerSong(it, t.strength) }
        }
    }

    override suspend fun clear() {
        dao.nuke()
    }

    private companion object {
        // Fraction below which a follow counts as "skipped" and yields a
        // negative strength contribution.
        const val SKIP_THRESHOLD = 0.25f
        const val STRENGTH_SCALE = 1.0f
    }
}

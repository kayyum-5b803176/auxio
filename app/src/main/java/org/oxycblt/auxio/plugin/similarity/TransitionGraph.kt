/*
 * Copyright (c) 2026 Auxio Project
 * TransitionGraph.kt is part of Auxio.
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

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A DIRECTED transition edge: how often, after playing [fromKey], the user went
 * on to genuinely listen to [toKey] ([plays]) versus skipped it ([skips]).
 * Unlike the symmetric vector, A->B is a separate row from B->A — this captures
 * "A leads to B often, B leads to A never", which cosine similarity cannot.
 * Isolated in its own table so the whole feature can be toggled/dropped cleanly.
 */
@Entity(primaryKeys = ["fromKey", "toKey"])
data class TransitionEdge(
    val fromKey: String,
    val toKey: String,
    val plays: Int,
    val skips: Int,
    val updatedAtMs: Long
)

@Dao
interface TransitionDao {
    @Query("SELECT * FROM TransitionEdge WHERE fromKey = :from AND toKey = :to LIMIT 1")
    suspend fun edge(from: String, to: String): TransitionEdge?

    @Query(
        "INSERT INTO TransitionEdge (fromKey, toKey, plays, skips, updatedAtMs) " +
            "VALUES (:from, :to, :plays, :skips, :now) " +
            "ON CONFLICT(fromKey, toKey) DO UPDATE SET " +
            "plays = plays + :plays, skips = skips + :skips, updatedAtMs = :now")
    suspend fun upsertDelta(from: String, to: String, plays: Int, skips: Int, now: Long)

    /** All outgoing edges from [from], strongest-first, as a live flow (for the log). */
    @Query(
        "SELECT * FROM TransitionEdge WHERE fromKey = :from " +
            "ORDER BY (CAST(plays AS REAL) / (plays + skips + 1)) DESC, plays DESC")
    fun outgoingFrom(from: String): Flow<List<TransitionEdge>>

    /** All outgoing edges from [from] (non-flow, for the ordering pass). */
    @Query("SELECT * FROM TransitionEdge WHERE fromKey = :from")
    suspend fun outgoingFromNow(from: String): List<TransitionEdge>

    @Query("DELETE FROM TransitionEdge WHERE fromKey = :key OR toKey = :key")
    suspend fun deleteFor(key: String)

    @Query("DELETE FROM TransitionEdge") suspend fun nuke()
}

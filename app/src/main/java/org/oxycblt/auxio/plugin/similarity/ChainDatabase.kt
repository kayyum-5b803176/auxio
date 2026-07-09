/*
 * Copyright (c) 2026 Auxio Project
 * ChainDatabase.kt is part of Auxio.
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
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update

/**
 * Persists the behavioral "chain": learned song→song transitions built purely
 * from real listening. No library scanning, no acoustic comparison — a
 * transition is recorded when one song plays after another, weighted by how
 * much of the following song was actually listened to.
 *
 * Both endpoints are FINGERPRINT keys (see ChainKey), not file URIs/UIDs, so a
 * link survives duplicate-deletion and format changes: the *music* owns the
 * link, not the file. Two files of the same recording share one chain node.
 */
@Database(entities = [ChainTransition::class], version = 1, exportSchema = false)
abstract class ChainDatabase : RoomDatabase() {
    abstract fun chainDao(): ChainDao
}

@Dao
interface ChainDao {
    /**
     * All learned followers of [fromKey], strongest first. These are the
     * "proven followers" manual play chooses among.
     */
    @Query(
        "SELECT * FROM ChainTransition WHERE fromKey = :fromKey ORDER BY strength DESC")
    suspend fun followersOf(fromKey: String): List<ChainTransition>

    @Query(
        "SELECT * FROM ChainTransition WHERE fromKey = :fromKey AND toKey = :toKey LIMIT 1")
    suspend fun get(fromKey: String, toKey: String): ChainTransition?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(t: ChainTransition)

    @Update suspend fun update(t: ChainTransition)

    /** Reinforce an existing link or create it, adding [delta] to its strength. */
    @Query(
        "UPDATE ChainTransition SET strength = strength + :delta, count = count + 1 " +
            "WHERE fromKey = :fromKey AND toKey = :toKey")
    suspend fun reinforce(fromKey: String, toKey: String, delta: Float): Int

    @Query("DELETE FROM ChainTransition") suspend fun nuke()
}

/**
 * One learned transition edge: after music [fromKey], music [toKey] was played.
 *
 * @param strength Accumulated, completion-weighted link strength. Higher = a
 *   more-proven follow. Grows when the follow is listened to fully, barely
 *   moves (or is added negatively) when the follow is skipped early.
 * @param count How many times this transition occurred (for diagnostics/decay).
 */
@Entity(primaryKeys = ["fromKey", "toKey"])
data class ChainTransition(
    val fromKey: String,
    val toKey: String,
    val strength: Float,
    val count: Int
)

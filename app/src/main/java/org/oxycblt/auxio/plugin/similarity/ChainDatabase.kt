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
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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
@Database(
    entities = [ChainTransition::class, ChainLogEntry::class, ChainSongScore::class],
    version = 4,
    exportSchema = false)
abstract class ChainDatabase : RoomDatabase() {
    abstract fun chainDao(): ChainDao

    abstract fun chainLogDao(): ChainLogDao

    abstract fun chainNodeDao(): ChainNodeDao
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

    @Query("DELETE FROM ChainTransition") suspend fun nuke()
}

/**
 * One learned transition edge: after music [fromKey], music [toKey] was played.
 *
 * @param strength Time-decayed, completion-weighted link strength, clamped to
 *   [ChainRepositoryImpl] bounds. Grows on good follows, shrinks fast on skips,
 *   and decays toward zero as it ages so RECENT behavior dominates.
 * @param count How many times this transition occurred (for diagnostics).
 * @param lastUpdatedMs When the edge last changed — the anchor for time decay.
 */
@Entity(primaryKeys = ["fromKey", "toKey"])
data class ChainTransition(
    val fromKey: String,
    val toKey: String,
    val strength: Float,
    val count: Int,
    val lastUpdatedMs: Long
)

/**
 * Persisted log of recent Smart Chain learning events, shown on the Logs page.
 * Kept to the most recent [ChainLog.CAPACITY] rows and survives app restarts.
 */
@Dao
interface ChainLogDao {
    /** Newest first, capped — the exact list the Logs page renders. */
    @Query("SELECT * FROM ChainLogEntry ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ChainLogEntry>>

    @Insert suspend fun insert(entry: ChainLogEntry)

    /**
     * Trim to the newest [keep] rows after an insert, so the table can't grow
     * without bound. Ordered by the same key the UI query uses.
     */
    @Query(
        "DELETE FROM ChainLogEntry WHERE id NOT IN " +
            "(SELECT id FROM ChainLogEntry ORDER BY timestampMs DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM ChainLogEntry") suspend fun nuke()
}

/** One persisted log line. [id] autogenerates; ordering uses timestamp then id. */
@Entity
data class ChainLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val message: String
)

/**
 * Per-SONG (node) scoring, independent of what played before it — "is this song
 * good on its own", as opposed to [ChainTransition] which is "does B follow A
 * well". Keyed on the song's [ChainKey].
 */
@Dao
interface ChainNodeDao {
    @Query("SELECT * FROM ChainSongScore WHERE key = :key LIMIT 1")
    suspend fun get(key: String): ChainSongScore?

    /** Batch fetch for ordering: node scores for every key in [keys]. */
    @Query("SELECT * FROM ChainSongScore WHERE key IN (:keys)")
    suspend fun getAll(keys: List<String>): List<ChainSongScore>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(score: ChainSongScore)

    /**
     * Fold a play observation into a song's node score: add [posDelta] to its
     * positive score and [negDelta] to its negative score, bump play/skip/like
     * counters, and stamp the time. Returns rows affected (0 if the row doesn't
     * exist yet — caller then inserts).
     */
    @Query(
        "UPDATE ChainSongScore SET " +
            "positiveScore = positiveScore + :posDelta, " +
            "negativeScore = negativeScore + :negDelta, " +
            "playCount = playCount + :playInc, " +
            "skipCount = skipCount + :skipInc, " +
            "jumpBackCount = jumpBackCount + :likeInc, " +
            "lastUpdatedMs = :now " +
            "WHERE key = :key")
    suspend fun fold(
        key: String,
        posDelta: Float,
        negDelta: Float,
        playInc: Int,
        skipInc: Int,
        likeInc: Int,
        now: Long
    ): Int

    @Query("DELETE FROM ChainSongScore") suspend fun nuke()
}

/**
 * A song's standalone score.
 *
 * @param positiveScore Accumulated liking (full plays, jump-backs).
 * @param negativeScore Accumulated rejection (fast skips).
 * @param playCount How many times the song was played through meaningfully.
 * @param skipCount How many times it was skipped early.
 * @param jumpBackCount How many times the user jumped back to replay it.
 */
@Entity
data class ChainSongScore(
    @PrimaryKey val key: String,
    val positiveScore: Float,
    val negativeScore: Float,
    val playCount: Int,
    val skipCount: Int,
    val jumpBackCount: Int,
    val lastUpdatedMs: Long
)

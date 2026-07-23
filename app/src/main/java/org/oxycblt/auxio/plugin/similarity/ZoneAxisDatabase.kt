/*
 * Copyright (c) 2026 Auxio Project
 * ZoneAxisDatabase.kt is part of Auxio.
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
 * Zone Axis storage.
 *
 * Two user-defined tag lists — Language and Type — that the user CRUDs freely,
 * plus one assignment row per song mapping it to (at most) one value on each
 * axis. Song rows are keyed by [ChainKey] (fingerprint-derived, uid: fallback),
 * exactly like the rest of Smart Chain — so a tag follows the recording, and
 * deleting a duplicate FILE never orphans the tag.
 *
 * Frequency is intentionally NOT stored here: it's derived live from the
 * behavioral SongQuality score, never hand-assigned.
 */
@Database(
    entities = [ZoneAxisValue::class, SongZoneTag::class, ZoneRelation::class],
    version = 3,
    exportSchema = false)
abstract class ZoneAxisDatabase : RoomDatabase() {
    abstract fun zoneAxisDao(): ZoneAxisDao
}

/** Axis identifiers. Stored as strings for forward-compatibility if more axes are added. */
object ZoneAxis {
    const val LANGUAGE = "Language"
    const val TYPE = "Type"
}

@Dao
interface ZoneAxisDao {
    // ---- axis value lists (CRUD) ----------------------------------------

    @Query("SELECT * FROM ZoneAxisValue WHERE axis = :axis ORDER BY label COLLATE NOCASE ASC")
    fun valuesFor(axis: String): Flow<List<ZoneAxisValue>>

    @Query("SELECT * FROM ZoneAxisValue WHERE axis = :axis ORDER BY label COLLATE NOCASE ASC")
    suspend fun valuesForNow(axis: String): List<ZoneAxisValue>

    @Query("SELECT * FROM ZoneAxisValue") suspend fun allValues(): List<ZoneAxisValue>

    @Query("SELECT * FROM ZoneAxisValue WHERE id = :id LIMIT 1")
    suspend fun valueById(id: Long): ZoneAxisValue?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertValue(value: ZoneAxisValue): Long

    @Update suspend fun updateValue(value: ZoneAxisValue)

    @Query("UPDATE ZoneAxisValue SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Float)

    @Query("DELETE FROM ZoneAxisValue WHERE id = :id") suspend fun deleteValue(id: Long)

    // ---- per-song assignments -------------------------------------------

    @Query("SELECT * FROM SongZoneTag WHERE songKey = :songKey LIMIT 1")
    suspend fun tagFor(songKey: String): SongZoneTag?

    @Query("SELECT * FROM SongZoneTag WHERE songKey = :songKey LIMIT 1")
    fun tagForFlow(songKey: String): Flow<SongZoneTag?>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTag(tag: SongZoneTag)

    @Query("SELECT * FROM SongZoneTag WHERE songKey IN (:keys)")
    suspend fun tagsFor(keys: List<String>): List<SongZoneTag>

    /** Every tag row — used by the backup module's export/merge passes. */
    @Query("SELECT * FROM SongZoneTag") suspend fun allTags(): List<SongZoneTag>

    /**
     * When an axis value is deleted, un-assign it from every song that used it,
     * per axis. Kept as two explicit queries (one per column) so the column is
     * a compile-time constant, not string-built.
     */
    @Query("UPDATE SongZoneTag SET languageValueId = NULL WHERE languageValueId = :valueId")
    suspend fun clearLanguageValue(valueId: Long)

    @Query("UPDATE SongZoneTag SET typeValueId = NULL WHERE typeValueId = :valueId")
    suspend fun clearTypeValue(valueId: Long)

    /** How many songs currently reference this value (for delete confirmation). */
    @Query("SELECT COUNT(*) FROM SongZoneTag WHERE languageValueId = :valueId")
    suspend fun countLanguageUsers(valueId: Long): Int

    @Query("SELECT COUNT(*) FROM SongZoneTag WHERE typeValueId = :valueId")
    suspend fun countTypeUsers(valueId: Long): Int

    @Query("DELETE FROM ZoneAxisValue") suspend fun nukeValues()

    @Query("DELETE FROM SongZoneTag") suspend fun nukeTags()

    // ---- relations (sparse pairwise relative values) --------------------

    @Query(
        "SELECT * FROM ZoneRelation WHERE valueIdLow = :low AND valueIdHigh = :high LIMIT 1")
    suspend fun relation(low: Long, high: Long): ZoneRelation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRelation(relation: ZoneRelation)

    @Query("DELETE FROM ZoneRelation WHERE valueIdLow = :low AND valueIdHigh = :high")
    suspend fun deleteRelation(low: Long, high: Long)

    /** Every relation touching [valueId] (either side) — for a value's editor page. */
    @Query(
        "SELECT * FROM ZoneRelation WHERE valueIdLow = :valueId OR valueIdHigh = :valueId")
    suspend fun relationsFor(valueId: Long): List<ZoneRelation>

    /** All relations (batched read for the ordering pass). */
    @Query("SELECT * FROM ZoneRelation") suspend fun allRelations(): List<ZoneRelation>

    /** Remove every relation referencing a value that's being deleted. */
    @Query("DELETE FROM ZoneRelation WHERE valueIdLow = :valueId OR valueIdHigh = :valueId")
    suspend fun deleteRelationsFor(valueId: Long)

    @Query("DELETE FROM ZoneRelation") suspend fun nukeRelations()
}

/**
 * One user-defined value on an axis, e.g. axis="Language" label="Hindi".
 *
 * Identity is the numeric [id], not [label], so renaming a value updates every
 * song tagged with it automatically (they reference the id).
 */
@Entity
data class ZoneAxisValue(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val axis: String,
    val label: String,
    /**
     * Position of this value on its axis, -1f..+1f, default 0f (neutral center).
     * Two values' positions define their distance on that axis; a song's
     * (languagePos, typePos, frequencyPos) is its point in the 3D zone-space.
     */
    val position: Float = 0f,
    val createdAtMs: Long
)

/**
 * A song's zone assignment — at most one Language value and one Type value.
 * Null means "untagged on that axis". Keyed by [ChainKey] so it survives
 * duplicate-file deletion.
 */
@Entity
data class SongZoneTag(
    @PrimaryKey val songKey: String,
    val languageValueId: Long?,
    val typeValueId: Long?
)

/**
 * A sparse, symmetric pairwise relationship between two values on the SAME axis,
 * -1f..+1f (positive = similar/attract, negative = opposite/repel). Only pairs
 * the user has explicitly set exist as rows; any unset pair defaults to 0f
 * (neutral). The pair is stored canonically with [valueIdLow] < [valueIdHigh]
 * so (A,B) and (B,A) map to a single row — the relationship is symmetric.
 */
@Entity(primaryKeys = ["valueIdLow", "valueIdHigh"])
data class ZoneRelation(
    val valueIdLow: Long,
    val valueIdHigh: Long,
    val relation: Float
)

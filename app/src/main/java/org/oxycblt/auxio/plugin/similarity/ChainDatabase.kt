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
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/**
 * Smart Chain storage — a song-EMBEDDING model, not a pairwise-edge model.
 *
 * Every song has a small vector ([SongEmbedding]) in a shared latent space.
 * "How similar are two songs" is just the distance between their vectors, so it
 * works for ANY pair — observed or not — is symmetric by construction, and
 * generalizes (if E is near B and B is near A, E tends to be near A). Learning
 * nudges vectors together (a good follow) or apart (a skip); explicit user
 * feedback is the same nudge with a bigger step.
 *
 * [SongQuality] is the standalone per-song score (liked / often skipped), used
 * as a tie-breaker on top of similarity.
 *
 * Keys are [ChainKey]s (fingerprint-derived, with a uid: fallback), so the
 * model is owned by the music, not the file.
 */
@Database(
    entities =
        [SongEmbedding::class, SongQuality::class, ChainLogEntry::class, SongLineage::class,
            TransitionEdge::class],
    version = 7,
    exportSchema = false)
@TypeConverters(VectorConverter::class)
abstract class ChainDatabase : RoomDatabase() {
    abstract fun embeddingDao(): EmbeddingDao

    abstract fun qualityDao(): QualityDao

    abstract fun chainLogDao(): ChainLogDao

    abstract fun lineageDao(): LineageDao

    abstract fun transitionDao(): TransitionDao
}

// ---------------------------------------------------------------------------
// Embeddings
// ---------------------------------------------------------------------------

@Dao
interface EmbeddingDao {
    @Query("SELECT * FROM SongEmbedding WHERE key = :key LIMIT 1")
    suspend fun get(key: String): SongEmbedding?

    /** Batch load for ordering — all embeddings for the given keys. */
    @Query("SELECT * FROM SongEmbedding WHERE key IN (:keys)")
    suspend fun getAll(keys: List<String>): List<SongEmbedding>

    /** Every stored embedding — used by the visualizer's "all songs" scope. */
    @Query("SELECT * FROM SongEmbedding")
    suspend fun all(): List<SongEmbedding>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(embedding: SongEmbedding)

    @Query("DELETE FROM SongEmbedding") suspend fun nuke()
}

/**
 * A song's position in the latent space.
 *
 * @param vector The latent coordinates (length [ChainRepositoryImpl.DIMENSIONS]).
 * @param observationCount How many times this vector has been updated. Drives a
 *   self-adjusting learning rate: young vectors move fast, established ones move
 *   slowly (a soft, proportional "don't trust n=1").
 * @param lastUpdatedMs For lazy realignment toward the content anchor over time.
 */
@Entity
data class SongEmbedding(
    @PrimaryKey val key: String,
    val vector: FloatArray,
    val observationCount: Int,
    val lastUpdatedMs: Long
) {
    // Room data class with an array member: override equals/hashCode by key
    // (identity is the key; the array is payload).
    override fun equals(other: Any?) = other is SongEmbedding && other.key == key

    override fun hashCode() = key.hashCode()
}

/** Stores a FloatArray as raw little-endian bytes for Room. */
class VectorConverter {
    @TypeConverter
    fun fromBytes(bytes: ByteArray?): FloatArray {
        if (bytes == null || bytes.isEmpty()) return FloatArray(0)
        val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    @TypeConverter
    fun toBytes(vector: FloatArray?): ByteArray {
        if (vector == null || vector.isEmpty()) return ByteArray(0)
        val bb = java.nio.ByteBuffer.allocate(vector.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(vector)
        return bb.array()
    }
}

// ---------------------------------------------------------------------------
// Per-song quality (standalone standing)
// ---------------------------------------------------------------------------

@Dao
interface QualityDao {
    @Query("SELECT * FROM SongQuality WHERE key = :key LIMIT 1")
    suspend fun get(key: String): SongQuality?

    @Query("SELECT * FROM SongQuality WHERE key IN (:keys)")
    suspend fun getAll(keys: List<String>): List<SongQuality>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(score: SongQuality)

    @Query(
        "UPDATE SongQuality SET " +
            "positiveScore = positiveScore + :posDelta, " +
            "negativeScore = negativeScore + :negDelta, " +
            "playCount = playCount + :playInc, " +
            "skipCount = skipCount + :skipInc, " +
            "likeCount = likeCount + :likeInc, " +
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

    @Query("DELETE FROM SongQuality") suspend fun nuke()
}

@Entity
data class SongQuality(
    @PrimaryKey val key: String,
    val positiveScore: Float,
    val negativeScore: Float,
    val playCount: Int,
    val skipCount: Int,
    val likeCount: Int,
    val lastUpdatedMs: Long
)

// ---------------------------------------------------------------------------
// Log (unchanged; drives the existing Logs page)
// ---------------------------------------------------------------------------

@Dao
interface ChainLogDao {
    @Query("SELECT * FROM ChainLogEntry ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ChainLogEntry>>

    @Insert suspend fun insert(entry: ChainLogEntry)

    @Query(
        "DELETE FROM ChainLogEntry WHERE id NOT IN " +
            "(SELECT id FROM ChainLogEntry ORDER BY timestampMs DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM ChainLogEntry") suspend fun nuke()
}

@Entity
data class ChainLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val message: String
)

// ---------------------------------------------------------------------------
// Lineage (for Zone Axis inheritance)
// ---------------------------------------------------------------------------

/**
 * Per-song chain lineage: the strongest/most-recent TAGGED-or-not ancestor a
 * song was chained from. Used by Zone Axis to compute inherited tags — an
 * unassigned song borrows its ancestor's effective tags.
 *
 * Only the single best ancestor is kept per song (strongest edge; most-recent
 * breaks ties). Persisted so inheritance survives restarts.
 */
@Dao
interface LineageDao {
    @Query("SELECT * FROM SongLineage WHERE songKey = :songKey LIMIT 1")
    suspend fun get(songKey: String): SongLineage?

    @Query("SELECT * FROM SongLineage WHERE songKey IN (:keys)")
    suspend fun getAll(keys: List<String>): List<SongLineage>

    /** All rows whose ancestor is [ancestorKey] — for ripple recompute + walk-up. */
    @Query("SELECT * FROM SongLineage WHERE ancestorKey = :ancestorKey")
    suspend fun childrenOf(ancestorKey: String): List<SongLineage>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(lineage: SongLineage)

    @Query("DELETE FROM SongLineage WHERE songKey = :songKey") suspend fun delete(songKey: String)

    @Query("DELETE FROM SongLineage") suspend fun nuke()
}

/**
 * @param songKey The song this lineage belongs to.
 * @param ancestorKey The song it most-strongly/recently chained from.
 * @param edgeStrength Similarity at the time the link formed (for "strongest").
 * @param updatedMs When recorded (for "most-recent" tie-break).
 */
@Entity
data class SongLineage(
    @PrimaryKey val songKey: String,
    val ancestorKey: String,
    val edgeStrength: Float,
    val updatedMs: Long
)

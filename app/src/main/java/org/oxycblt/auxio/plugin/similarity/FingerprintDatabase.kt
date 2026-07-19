/*
 * Copyright (c) 2026 Auxio Project
 * FingerprintDatabase.kt is part of Auxio.
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.oxycblt.musikr.Music

/**
 * Persists computed acoustic fingerprints so a song is only ever analyzed
 * once. Subsequent duplicate scans read cached fingerprints instead of
 * decoding + FFT-ing every file again, turning a multi-minute rescan into a
 * near-instant one.
 *
 * Invalidation is handled by storing each song's [Music.Song.modifiedMs]
 * alongside its fingerprint: if the file changes on disk (re-tagged,
 * re-encoded, replaced), its cached entry is stale and gets recomputed.
 * The [AudioFingerprinterImpl.FINGERPRINT_ALGORITHM_VERSION] is also stored,
 * so bumping the fingerprint algorithm transparently invalidates all old
 * entries without a manual DB migration.
 */
@Database(entities = [FingerprintEntity::class], version = 3, exportSchema = false)
@TypeConverters(Music.UID.TypeConverters::class, FingerprintConverters::class)
abstract class FingerprintDatabase : RoomDatabase() {
    abstract fun fingerprintDao(): FingerprintDao
}

@Dao
interface FingerprintDao {
    /** Fetch all cached fingerprints. Scans join these against the live library in memory. */
    @Query("SELECT * FROM FingerprintEntity") suspend fun getAll(): List<FingerprintEntity>

    /** Fetch a single cached fingerprint by song UID, or null if not yet analyzed. */
    @Query("SELECT * FROM FingerprintEntity WHERE uid = :uid")
    suspend fun get(uid: Music.UID): FingerprintEntity?

    /** Batched lookup for the shuffle-ordering pass (chunk to stay under
     * SQLite's bound-variable limit). */
    @Query("SELECT * FROM FingerprintEntity WHERE uid IN (:uids)")
    suspend fun getAll(uids: List<Music.UID>): List<FingerprintEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FingerprintEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<FingerprintEntity>)

    /** Remove cached entries whose songs are no longer in the library. */
    @Query("DELETE FROM FingerprintEntity WHERE uid NOT IN (:liveUids)")
    suspend fun pruneMissing(liveUids: List<Music.UID>)

    /** Drop the entire cache (exposed for a potential "clear cache" affordance). */
    @Query("DELETE FROM FingerprintEntity") suspend fun nuke()
}

/**
 * One cached fingerprint.
 *
 * @param uid Stable song identity — survives path changes, unlike a MediaStore id.
 * @param modifiedMs File mtime captured at analysis time; mismatch = stale.
 * @param algorithmVersion Fingerprint algorithm version at analysis time; mismatch = stale.
 * @param fingerprint The packed fingerprint. Empty array is a valid, meaningful
 *   value: it records "this file was analyzed but produced no usable
 *   fingerprint" (e.g. too short / undecodable), so we don't wastefully retry
 *   an unfingerprintable file on every single scan.
 */
@Entity
data class FingerprintEntity(
    @PrimaryKey val uid: Music.UID,
    val modifiedMs: Long,
    val algorithmVersion: Int,
    val fingerprint: IntArray,
    /** Per-band spectral profile for quality analysis (see AudioFingerprinter). */
    val spectralProfile: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FingerprintEntity) return false
        return uid == other.uid &&
            modifiedMs == other.modifiedMs &&
            algorithmVersion == other.algorithmVersion &&
            fingerprint.contentEquals(other.fingerprint) &&
            spectralProfile.contentEquals(other.spectralProfile)
    }

    override fun hashCode(): Int {
        var result = uid.hashCode()
        result = 31 * result + modifiedMs.hashCode()
        result = 31 * result + algorithmVersion
        result = 31 * result + fingerprint.contentHashCode()
        result = 31 * result + spectralProfile.contentHashCode()
        return result
    }
}

/** Stores int-array and float-array payloads as compact little-endian BLOBs. */
class FingerprintConverters {
    @TypeConverter
    fun fromIntArray(value: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asIntBuffer().put(value)
        return buffer.array()
    }

    @TypeConverter
    fun toIntArray(bytes: ByteArray): IntArray {
        val intBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val out = IntArray(intBuffer.remaining())
        intBuffer.get(out)
        return out
    }

    @TypeConverter
    fun fromFloatArray(value: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(value)
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray): FloatArray {
        val floatBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(floatBuffer.remaining())
        floatBuffer.get(out)
        return out
    }
}

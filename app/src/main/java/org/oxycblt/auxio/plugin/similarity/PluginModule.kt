/*
 * Copyright (c) 2026 Auxio Project
 * PluginModule.kt is part of Auxio.
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

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * v5 -> v6: adds the SongLineage table (Zone Axis inheritance). Purely additive;
 * existing embedding/quality/log data is preserved.
 */
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `SongLineage` (" +
                    "`songKey` TEXT NOT NULL, " +
                    "`ancestorKey` TEXT NOT NULL, " +
                    "`edgeStrength` REAL NOT NULL, " +
                    "`updatedMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`songKey`))")
        }
    }

/** Chain DB v6 -> v7: adds the directed TransitionEdge graph table. Additive. */
/** Chain DB v7 -> v8: adds acousticSeeded flag to SongEmbedding (default 0). */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `SongEmbedding` ADD COLUMN `acousticSeeded` " +
                    "INTEGER NOT NULL DEFAULT 0")
        }
    }

val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `TransitionEdge` (" +
                    "`fromKey` TEXT NOT NULL, " +
                    "`toKey` TEXT NOT NULL, " +
                    "`toName` TEXT NOT NULL, " +
                    "`plays` INTEGER NOT NULL, " +
                    "`skips` INTEGER NOT NULL, " +
                    "`updatedAtMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`fromKey`, `toKey`))")
        }
    }

/**
 * Zone DB v1 -> v2: adds the `position` column to ZoneAxisValue (continuous
 * zone-space). Additive; defaults to 0 (neutral center) for all existing values.
 */
val MIGRATION_ZONE_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `ZoneAxisValue` ADD COLUMN `position` REAL NOT NULL DEFAULT 0")
        }
    }

/**
 * Zone DB v2 -> v3: adds the ZoneRelation table (sparse pairwise relative
 * values). Purely additive; existing values/tags/positions are preserved.
 */
val MIGRATION_ZONE_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ZoneRelation` (" +
                    "`valueIdLow` INTEGER NOT NULL, " +
                    "`valueIdHigh` INTEGER NOT NULL, " +
                    "`relation` REAL NOT NULL, " +
                    "PRIMARY KEY(`valueIdLow`, `valueIdHigh`))")
        }
    }

@Module
@InstallIn(SingletonComponent::class)
interface PluginModule {
    @Binds fun pluginSettings(impl: PluginSettingsImpl): PluginSettings

    @Binds fun audioFingerprinter(impl: AudioFingerprinterImpl): AudioFingerprinter

    @Binds fun acousticFeatures(impl: AcousticFeaturesImpl): AcousticFeatures

    @Binds fun songDeleter(impl: SongDeleterImpl): SongDeleter

    @Binds fun fingerprintRepository(impl: FingerprintRepositoryImpl): FingerprintRepository

    @Binds fun chainRepository(impl: ChainRepositoryImpl): ChainRepository

    @Binds fun zoneAxisRepository(impl: ZoneAxisRepositoryImpl): ZoneAxisRepository
}

@Module
@InstallIn(SingletonComponent::class)
class PluginRoomModule {
    @Singleton
    @Provides
    fun fingerprintDatabase(@ApplicationContext context: Context) =
        Room.databaseBuilder(
                context.applicationContext,
                FingerprintDatabase::class.java,
                "fingerprint_cache.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun fingerprintDao(database: FingerprintDatabase) = database.fingerprintDao()

    @Singleton
    @Provides
    fun chainDatabase(@ApplicationContext context: Context) =
        Room.databaseBuilder(
                // NOTE: new filename ("chain_vectors.db") for the embedding-model
                // rewrite. The previous pairwise-edge DB ("chain_cache.db") used
                // an incompatible schema; a one-time fresh start is unavoidable
                // when replacing the entire algorithm. The old file is simply
                // left orphaned (Android clears it on uninstall/clear-data).
                context.applicationContext, ChainDatabase::class.java, "chain_vectors.db")
            // POLICY: learned data must never be silently wiped by an app update.
            // Only downgrades (sideloading an OLDER build over a newer DB) may
            // reset, since backward migration is impossible. Any FUTURE schema
            // change to ChainDatabase must ship an explicit Migration(from, to)
            // via .addMigrations(...) — never a blanket destructive fallback for
            // upgrades, and never another filename change (that orphans data).
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun embeddingDao(database: ChainDatabase) = database.embeddingDao()

    @Provides fun chainLogDao(database: ChainDatabase) = database.chainLogDao()

    @Provides fun qualityDao(database: ChainDatabase) = database.qualityDao()

    @Provides fun lineageDao(database: ChainDatabase) = database.lineageDao()

    @Provides fun transitionDao(database: ChainDatabase) = database.transitionDao()

    @Singleton
    @Provides
    fun zoneAxisDatabase(@ApplicationContext context: Context) =
        Room.databaseBuilder(
                context.applicationContext, ZoneAxisDatabase::class.java, "zone_axis.db")
            // Same policy as ChainDatabase: user-authored tags must never be
            // silently wiped on upgrade. Future schema changes ship an explicit
            // Migration; only downgrades may reset.
            .addMigrations(MIGRATION_ZONE_1_2, MIGRATION_ZONE_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun zoneAxisDao(database: ZoneAxisDatabase) = database.zoneAxisDao()
}

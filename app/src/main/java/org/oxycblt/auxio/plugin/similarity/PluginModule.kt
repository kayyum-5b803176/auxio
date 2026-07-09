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
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface PluginModule {
    @Binds fun pluginSettings(impl: PluginSettingsImpl): PluginSettings

    @Binds fun audioFingerprinter(impl: AudioFingerprinterImpl): AudioFingerprinter

    @Binds fun songDeleter(impl: SongDeleterImpl): SongDeleter

    @Binds fun fingerprintRepository(impl: FingerprintRepositoryImpl): FingerprintRepository

    @Binds fun chainRepository(impl: ChainRepositoryImpl): ChainRepository
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
                context.applicationContext, ChainDatabase::class.java, "chain_cache.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun chainDao(database: ChainDatabase) = database.chainDao()

    @Provides fun chainLogDao(database: ChainDatabase) = database.chainLogDao()

    @Provides fun chainNodeDao(database: ChainDatabase) = database.chainNodeDao()
}

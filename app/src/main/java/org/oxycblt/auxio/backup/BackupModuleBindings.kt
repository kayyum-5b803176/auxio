/*
 * Copyright (c) 2026 Auxio Project
 * BackupModuleBindings.kt is part of Auxio.
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

package org.oxycblt.auxio.backup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import org.oxycblt.auxio.backup.modules.FingerprintCacheBackupModule
import org.oxycblt.auxio.backup.modules.PlaylistsBackupModule
import org.oxycblt.auxio.backup.modules.PluginPreferencesBackupModule
import org.oxycblt.auxio.backup.modules.SmartChainBackupModule
import org.oxycblt.auxio.backup.modules.ZoneAxisBackupModule

/**
 * Registers every [BackupModule] into the multibound `Set<BackupModule>`
 * that [BackupCoordinator] iterates. THIS is the one place a new feature
 * plugs itself into the backup system: write a new [BackupModule], add one
 * `@Binds @IntoSet` line here, and export/import/merge/UI all pick it up
 * automatically. No other file changes.
 */
@Module
@InstallIn(SingletonComponent::class)
interface BackupModuleBindings {
    @Binds @IntoSet fun smartChain(impl: SmartChainBackupModule): BackupModule

    @Binds @IntoSet fun zoneAxis(impl: ZoneAxisBackupModule): BackupModule

    @Binds @IntoSet fun fingerprintCache(impl: FingerprintCacheBackupModule): BackupModule

    @Binds @IntoSet fun pluginPreferences(impl: PluginPreferencesBackupModule): BackupModule

    @Binds @IntoSet fun playlists(impl: PlaylistsBackupModule): BackupModule
}

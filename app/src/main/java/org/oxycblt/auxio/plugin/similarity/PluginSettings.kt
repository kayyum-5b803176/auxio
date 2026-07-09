/*
 * Copyright (c) 2026 Auxio Project
 * PluginSettings.kt is part of Auxio.
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
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings

/**
 * User configuration for optional plugin features.
 *
 * Plugins are strictly opt-in: while a plugin's flag is false, no plugin code
 * paths should run anywhere in the app, no background work should be
 * scheduled, and no plugin state should be created. Disabling must return the
 * app to exactly its stock behavior.
 */
interface PluginSettings : Settings<PluginSettings.Listener> {
    /** Whether the acoustic similarity detection (duplicate finder) plugin is enabled. */
    val similarityDetectionEnabled: Boolean

    /**
     * User-defined "priority" folder names (case-insensitive), e.g. "export",
     * "import". A song is prioritized if any segment of its file path matches
     * one of these names. Prioritized files are preferred as the "keep" in a
     * duplicate group, and deleting a file inside one requires an extra
     * confirmation.
     *
     * This is intentionally a general folder-name feature (not tied to
     * similarity detection specifically) so future plugins can reuse it.
     */
    var priorityFolderNames: List<String>

    /**
     * Whether the Smart Chain plugin is enabled — learns song→song transitions
     * from listening and drives "what plays next". Strictly opt-in; when false,
     * no playback observation or chain storage happens and queueing is stock.
     */
    val smartChainEnabled: Boolean

    interface Listener {
        /** Called when [similarityDetectionEnabled] changes. */
        fun onSimilarityDetectionChanged() {}

        /** Called when [priorityFolderNames] changes. */
        fun onPriorityFoldersChanged() {}

        /** Called when [smartChainEnabled] changes. */
        fun onSmartChainChanged() {}
    }
}

class PluginSettingsImpl @Inject constructor(@ApplicationContext private val context: Context) :
    Settings.Impl<PluginSettings.Listener>(context), PluginSettings {

    override val similarityDetectionEnabled: Boolean
        get() =
            sharedPreferences.getBoolean(
                getString(R.string.set_key_similarity_detection), false)

    override var priorityFolderNames: List<String>
        get() =
            sharedPreferences
                .getStringSet(getString(R.string.set_key_priority_folders), emptySet())
                .orEmpty()
                // Stored as a set; present sorted for a stable UI order.
                .sorted()
        set(value) {
            sharedPreferences.edit {
                // Normalize: trim, drop blanks, de-duplicate case-insensitively.
                val cleaned =
                    value
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinctBy { it.lowercase() }
                        .toSet()
                putStringSet(getString(R.string.set_key_priority_folders), cleaned)
            }
        }

    override val smartChainEnabled: Boolean
        get() =
            sharedPreferences.getBoolean(getString(R.string.set_key_smart_chain), false)

    override fun onSettingChanged(key: String, listener: PluginSettings.Listener) {
        when (key) {
            getString(R.string.set_key_similarity_detection) ->
                listener.onSimilarityDetectionChanged()
            getString(R.string.set_key_priority_folders) ->
                listener.onPriorityFoldersChanged()
            getString(R.string.set_key_smart_chain) -> listener.onSmartChainChanged()
        }
    }
}

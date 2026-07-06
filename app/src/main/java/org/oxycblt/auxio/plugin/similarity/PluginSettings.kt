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

    interface Listener {
        /** Called when [similarityDetectionEnabled] changes. */
        fun onSimilarityDetectionChanged() {}
    }
}

class PluginSettingsImpl @Inject constructor(@ApplicationContext private val context: Context) :
    Settings.Impl<PluginSettings.Listener>(context), PluginSettings {

    override val similarityDetectionEnabled: Boolean
        get() =
            sharedPreferences.getBoolean(
                getString(R.string.set_key_similarity_detection), false)

    override fun onSettingChanged(key: String, listener: PluginSettings.Listener) {
        if (key == getString(R.string.set_key_similarity_detection)) {
            listener.onSimilarityDetectionChanged()
        }
    }
}

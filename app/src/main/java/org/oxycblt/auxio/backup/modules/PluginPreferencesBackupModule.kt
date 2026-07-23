/*
 * Copyright (c) 2026 Auxio Project
 * PluginPreferencesBackupModule.kt is part of Auxio.
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

package org.oxycblt.auxio.backup.modules

import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.backup.BackupModule
import org.oxycblt.auxio.backup.MergeResult
import org.oxycblt.auxio.backup.ConflictResolution
import org.oxycblt.auxio.backup.MergeUtil.unionPreservingCase
import org.oxycblt.auxio.plugin.similarity.PluginSettings
import org.oxycblt.auxio.plugin.similarity.PluginSettingsWriter

/**
 * Backs up the plugin feature toggles and the priority-folder list — the
 * persistent, deliberately-chosen settings that unlocked and shaped a
 * device's learned data.
 *
 * Deliberately EXCLUDED: the queue-order sliders and the queue-order
 * type/language filter selection. Those are live, frequently-adjusted dials
 * (closer to a volume knob than a decision) and are treated the same way
 * this backup system treats playback/queue state — device-local, not
 * something a backup should move between devices.
 *
 * Feature toggles use an OR-merge: if either device had a feature turned on,
 * it ends up on after import. This mirrors the "nothing is lost" goal — a
 * toggle being on is what allowed the learned data merged by the other
 * modules to exist in the first place, so turning it back off after
 * importing that data would hide it again from the destination device.
 * Priority folder names are unioned as a set, case-insensitively.
 *
 * There is nothing to genuinely disagree about here (a boolean OR and a set
 * union always have one right answer), so this module never raises a
 * [org.oxycblt.auxio.backup.Conflict].
 */
class PluginPreferencesBackupModule
@Inject
constructor(private val pluginSettings: PluginSettings) : BackupModule {
    override val id = "plugin_preferences"
    override val displayName = "Plugin settings"
    override val schemaVersion = 1

    override suspend fun hasData(): Boolean = true // always meaningful to include; cheap to write

    override suspend fun export(): JSONObject =
        JSONObject().apply {
            put("similarityDetectionEnabled", pluginSettings.similarityDetectionEnabled)
            put("smartChainEnabled", pluginSettings.smartChainEnabled)
            put("zoneAxisEnabled", pluginSettings.zoneAxisEnabled)
            put("transitionGraphEnabled", pluginSettings.transitionGraphEnabled)
            put(
                "priorityFolderNames",
                JSONArray().apply { pluginSettings.priorityFolderNames.forEach { put(it) } })
        }

    override suspend fun merge(
        incoming: JSONObject,
        incomingSchemaVersion: Int,
        resolutions: Map<String, ConflictResolution>,
        apply: Boolean
    ): MergeResult {
        var updated = 0
        var unchanged = 0

        // Booleans are read-only on PluginSettings for the toggle flags
        // themselves (they're driven by the plugin preference screen, not
        // written directly). Backup uses the underlying settings write path
        // via the same interface implementation, so this cast is safe within
        // this module and mirrors how PluginPreferenceFragment flips them.
        val incomingFolders = incoming.optJSONArray("priorityFolderNames") ?: JSONArray()
        val incomingFolderList = (0 until incomingFolders.length()).map { incomingFolders.getString(it) }
        val mergedFolders = unionPreservingCase(pluginSettings.priorityFolderNames, incomingFolderList)
        if (mergedFolders.toSet() != pluginSettings.priorityFolderNames.toSet()) {
            updated++
            if (apply) pluginSettings.priorityFolderNames = mergedFolders
        } else {
            unchanged++
        }

        // Toggle flags: OR-merge. PluginSettings exposes these as read-only
        // vals (they're written by the settings UI through SharedPreferences
        // directly); the writable setter lives on PluginSettingsWriter so
        // this module doesn't need to know about SharedPreferences keys.
        if (apply) {
            val writer = pluginSettings as? PluginSettingsWriter
            writer?.setSimilarityDetectionEnabled(
                pluginSettings.similarityDetectionEnabled || incoming.optBoolean("similarityDetectionEnabled"))
            writer?.setSmartChainEnabled(
                pluginSettings.smartChainEnabled || incoming.optBoolean("smartChainEnabled"))
            writer?.setZoneAxisEnabled(pluginSettings.zoneAxisEnabled || incoming.optBoolean("zoneAxisEnabled"))
            writer?.setTransitionGraphEnabled(
                pluginSettings.transitionGraphEnabled || incoming.optBoolean("transitionGraphEnabled"))
        }
        val anyToggleChanged =
            (incoming.optBoolean("similarityDetectionEnabled") && !pluginSettings.similarityDetectionEnabled) ||
                (incoming.optBoolean("smartChainEnabled") && !pluginSettings.smartChainEnabled) ||
                (incoming.optBoolean("zoneAxisEnabled") && !pluginSettings.zoneAxisEnabled) ||
                (incoming.optBoolean("transitionGraphEnabled") && !pluginSettings.transitionGraphEnabled)
        if (anyToggleChanged) updated++ else unchanged++

        return MergeResult(added = 0, updated = updated, unchanged = unchanged)
    }
}

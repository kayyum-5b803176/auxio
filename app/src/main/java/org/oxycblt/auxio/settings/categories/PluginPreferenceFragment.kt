/*
 * Copyright (c) 2026 Auxio Project
 * PluginPreferenceFragment.kt is part of Auxio.
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

package org.oxycblt.auxio.settings.categories

import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.navigateSafe

/**
 * "Plugins" settings. Hosts opt-in toggles for experimental features. Each
 * plugin must be a strict no-op while its toggle is off — no background
 * work, no state, no change to stock behavior.
 *
 * Hosts the Similarity Detection plugin toggle and the shared "Priority
 * folders" configuration (a general folder-name feature future plugins can
 * reuse). The similarity toggle itself needs no change handler here because
 * its only entry point (the "Find duplicates" preference) re-reads the flag
 * every time the root settings screen resumes.
 */
@AndroidEntryPoint
class PluginPreferenceFragment : BasePreferenceFragment(R.xml.preferences_plugin) {
    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        when (preference.key) {
            getString(R.string.set_key_priority_folders) -> {
                findNavController()
                    .navigateSafe(
                        PluginPreferenceFragmentDirections.priorityFoldersSettings())
            }
            getString(R.string.set_key_zone_axis_manage) -> {
                findNavController()
                    .navigateSafe(PluginPreferenceFragmentDirections.zoneAxisSettings())
            }
        }
    }
}

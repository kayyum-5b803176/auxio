/*
 * Copyright (c) 2021 Auxio Project
 * RootPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.plugin.similarity.PluginSettings
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.navigateSafe
import timber.log.Timber as L

/**
 * The [PreferenceFragmentCompat] that displays the root settings list.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class RootPreferenceFragment : BasePreferenceFragment(R.xml.preferences_root) {
    private val musicModel: MusicViewModel by activityViewModels()
    @Inject lateinit var pluginSettings: PluginSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = MaterialFadeThrough()
        returnTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onResume() {
        super.onResume()
        // The duplicates entry mirrors the Similarity Detection plugin flag.
        // Re-checked on every resume so toggling the plugin and navigating
        // back reflects immediately. While the plugin is disabled the entry
        // is invisible and no plugin code runs at all.
        findPreference<Preference>(getString(R.string.set_key_find_duplicates))?.isVisible =
            pluginSettings.similarityDetectionEnabled
        // The acoustic scan seeds the Smart Chain, so it's shown only while Smart
        // Chain is enabled (re-checked each resume, same as duplicates above).
        findPreference<Preference>(getString(R.string.set_key_acoustic_scan))?.isVisible =
            pluginSettings.smartChainEnabled
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        when (preference.key) {
            getString(R.string.set_key_music_dirs) -> {
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicLocationsSettings())
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        // Hook generic preferences to their specified preferences
        // TODO: These seem like good things to put into a side navigation view, if I choose to
        //  do one.
        when (preference.key) {
            getString(R.string.set_key_ui) -> {
                L.d("Navigating to UI preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.uiPreferences())
            }
            getString(R.string.set_key_personalize) -> {
                L.d("Navigating to personalization preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.personalizePreferences())
            }
            getString(R.string.set_key_music) -> {
                L.d("Navigating to music preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicPreferences())
            }
            getString(R.string.set_key_audio) -> {
                L.d("Navigating to audio preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.audioPeferences())
            }
            getString(R.string.set_key_plugins) -> {
                L.d("Navigating to plugin preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.pluginPreferences())
            }
            getString(R.string.set_key_find_duplicates) -> {
                L.d("Navigating to duplicates screen")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.findDuplicates())
            }
            getString(R.string.set_key_acoustic_scan) -> {
                L.d("Navigating to acoustic scan screen")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.acousticScan())
            }
            getString(R.string.set_key_reindex) -> musicModel.refresh()
            getString(R.string.set_key_rescan) -> musicModel.rescan()
            else -> return super.onPreferenceTreeClick(preference)
        }

        return true
    }
}

/*
 * Copyright (c) 2026 Auxio Project
 * ZoneAxisViewModel.kt is part of Auxio.
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.oxycblt.musikr.Song

/**
 * Backs the two zone dropdowns (Language, Type) shown on the now-playing panel.
 *
 * Exposes the live value lists for each axis (so the dropdowns always reflect
 * the current CRUD state) and the current song's assignment, and writes back
 * when the user picks a value. Strictly a no-op surface when the Zone Axis
 * plugin is disabled — the fragment hides the row in that case.
 */
@HiltViewModel
class ZoneAxisViewModel
@Inject
constructor(
    private val zoneAxisRepository: ZoneAxisRepository,
    private val pluginSettings: PluginSettings
) : ViewModel() {

    val enabled: Boolean
        get() = pluginSettings.zoneAxisEnabled

    /** Live Language values for the dropdown. */
    val languageValues: StateFlow<List<ZoneAxisValue>> =
        zoneAxisRepository
            .values(ZoneAxis.LANGUAGE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live Type values for the dropdown. */
    val typeValues: StateFlow<List<ZoneAxisValue>> =
        zoneAxisRepository
            .values(ZoneAxis.TYPE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The current song's assignment (null = untagged / no song). */
    private val _currentTag = MutableStateFlow<SongZoneTag?>(null)
    val currentTag: StateFlow<SongZoneTag?> = _currentTag

    /** The frequency tier of the current song (derived, read-only badge). */
    private val _currentFrequency = MutableStateFlow<FrequencyTier?>(null)
    val currentFrequency: StateFlow<FrequencyTier?> = _currentFrequency

    private var currentSong: Song? = null

    /** Called by the fragment whenever the now-playing song changes. */
    fun onSongChanged(song: Song?) {
        currentSong = song
        if (song == null || !pluginSettings.zoneAxisEnabled) {
            _currentTag.value = null
            _currentFrequency.value = null
            return
        }
        viewModelScope.launch {
            _currentTag.value = zoneAxisRepository.tagFor(song)
            _currentFrequency.value = zoneAxisRepository.frequencyOf(song)
        }
    }

    /** Assign (or clear with null) a Language value to the current song. */
    fun assignLanguage(valueId: Long?) = assign(ZoneAxis.LANGUAGE, valueId)

    /** Assign (or clear with null) a Type value to the current song. */
    fun assignType(valueId: Long?) = assign(ZoneAxis.TYPE, valueId)

    private fun assign(axis: String, valueId: Long?) {
        val song = currentSong ?: return
        viewModelScope.launch {
            zoneAxisRepository.assign(song, axis, valueId)
            _currentTag.value = zoneAxisRepository.tagFor(song)
        }
    }

    // ---- CRUD screen support --------------------------------------------

    /** Add a value to an axis (Language/Type). */
    fun addValue(axis: String, label: String) {
        viewModelScope.launch { zoneAxisRepository.addValue(axis, label) }
    }

    /** Rename an existing value. */
    fun renameValue(id: Long, newLabel: String) {
        viewModelScope.launch { zoneAxisRepository.renameValue(id, newLabel) }
    }

    fun setPosition(id: Long, position: Float) {
        viewModelScope.launch { zoneAxisRepository.setPosition(id, position) }
    }

    /** Delete a value (un-assigns it from all songs). */
    fun deleteValue(id: Long) {
        viewModelScope.launch { zoneAxisRepository.deleteValue(id) }
    }

    /** How many songs use a value — for the delete confirmation dialog. */
    suspend fun countUsers(id: Long): Int = zoneAxisRepository.countUsers(id)
}

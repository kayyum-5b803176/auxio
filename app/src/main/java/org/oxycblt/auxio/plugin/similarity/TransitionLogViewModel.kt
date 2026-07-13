/*
 * Copyright (c) 2026 Auxio Project
 * TransitionLogViewModel.kt is part of Auxio.
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import org.oxycblt.auxio.playback.state.PlaybackStateManager

/**
 * Backs the transition-graph log: the outgoing transitions from the CURRENTLY
 * playing song only (a filter on the graph). Refreshes when the current song
 * changes.
 */
@HiltViewModel
class TransitionLogViewModel
@Inject
constructor(
    private val chainRepository: ChainRepository,
    private val playbackManager: PlaybackStateManager
) : ViewModel(), PlaybackStateManager.Listener {

    private val _rows = MutableStateFlow<List<TransitionRow>>(emptyList())
    val rows: StateFlow<List<TransitionRow>>
        get() = _rows

    /** The current song, so the fragment can render its title (needs a Context). */
    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?>
        get() = _current

    init {
        playbackManager.addListener(this)
        refresh()
    }

    override fun onCleared() {
        playbackManager.removeListener(this)
    }

    override fun onIndexMoved(index: Int) = refresh()

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean
    ) = refresh()

    private fun refresh() {
        val song = playbackManager.currentSong
        _current.value = song
        if (song == null) {
            _rows.value = emptyList()
            return
        }
        viewModelScope.launch { _rows.value = chainRepository.transitionsForCurrent(song) }
    }
}

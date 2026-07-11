/*
 * Copyright (c) 2026 Auxio Project
 * ZoneVisualizerViewModel.kt is part of Auxio.
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
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.musikr.Song

/**
 * Backs the 24-dimensional parallel-coordinates visualizer. Resolves songs to
 * their stored embedding vectors and exposes a plottable model. No dimensional
 * reduction — every one of the [DIMENSIONS] axes is a real vector component.
 */
@HiltViewModel
class ZoneVisualizerViewModel
@Inject
constructor(
    private val embeddingDao: EmbeddingDao,
    private val zoneAxisDao: ZoneAxisDao,
    private val fingerprintRepository: FingerprintRepository,
    private val musicRepository: MusicRepository,
    private val playbackManager: PlaybackStateManager
) : ViewModel() {

    /** One plottable line: a song, its 24-vector, tag state, stable color, and role. */
    data class Plot(
        val song: Song,
        val key: String,
        val vector: FloatArray,
        val tagged: Boolean,
        val colorIndex: Int
    )

    data class Model(
        val plots: List<Plot>,
        val currentKey: String?,
        val dimensions: Int
    )

    enum class Scope {
        QUEUE,
        NEAREST,
        ALL
    }

    private val _model = MutableStateFlow(Model(emptyList(), null, DIMENSIONS))
    val model: StateFlow<Model> = _model

    private val _scope = MutableStateFlow(Scope.QUEUE)
    val scope: StateFlow<Scope> = _scope

    /**
     * The single song currently focused (tapped on the graph OR in the legend).
     * Null = show every line at normal weight. Non-null = that line is
     * highlighted and every other line is dimmed — the "filter" behavior.
     */
    private val _focusedKey = MutableStateFlow<String?>(null)
    val focusedKey: StateFlow<String?> = _focusedKey

    /** Distance from the focused song to the currently playing one, if both exist. */
    private val _distanceToCurrent = MutableStateFlow<Float?>(null)
    val distanceToCurrent: StateFlow<Float?> = _distanceToCurrent

    init {
        load(Scope.QUEUE)
    }

    fun setScope(scope: Scope) {
        if (_scope.value == scope) return
        _scope.value = scope
        load(scope)
    }

    private suspend fun keyOf(song: Song): String {
        val result = fingerprintRepository.getCached(song)
        val fpKey = result?.let { ChainKey.of(it.fingerprint) }
        return fpKey ?: ("uid:" + song.uid.toString())
    }

    private fun load(scope: Scope) {
        viewModelScope.launch {
            val currentSong = playbackManager.currentSong
            val currentKey = currentSong?.let { keyOf(it) }

            // Choose the candidate songs by scope.
            val songs: List<Song> =
                when (scope) {
                    Scope.QUEUE -> playbackManager.queue
                    Scope.ALL -> musicRepository.library?.songs?.toList() ?: emptyList()
                    Scope.NEAREST -> playbackManager.queue // filtered to nearest below
                }

            // Resolve keys once.
            val keyBySong = HashMap<Song, String>()
            for (s in songs) keyBySong[s] = keyOf(s)

            // Load embeddings for these keys (batched); for ALL, one shot.
            val wantedKeys = keyBySong.values.distinct()
            val embByKey = HashMap<String, FloatArray>()
            if (scope == Scope.ALL) {
                for (e in embeddingDao.all()) embByKey[e.key] = e.vector
            } else {
                for (chunk in wantedKeys.chunked(BATCH)) {
                    for (e in embeddingDao.getAll(chunk)) embByKey[e.key] = e.vector
                }
            }

            // Tag state (batched).
            val taggedKeys = HashSet<String>()
            for (chunk in wantedKeys.chunked(BATCH)) {
                for (t in zoneAxisDao.tagsFor(chunk)) {
                    if (t.languageValueId != null || t.typeValueId != null) taggedKeys.add(t.songKey)
                }
            }

            var plots =
                songs.mapNotNull { s ->
                    val k = keyBySong.getValue(s)
                    val v = embByKey[k] ?: return@mapNotNull null
                    Plot(s, k, v, k in taggedKeys, 0)
                }

            // NEAREST: keep the current song + its 20 closest by cosine.
            if (scope == Scope.NEAREST && currentKey != null) {
                val curVec = embByKey[currentKey]
                if (curVec != null) {
                    plots =
                        plots
                            .sortedByDescending { cosine(curVec, it.vector) }
                            .take(NEAREST_COUNT + 1)
                }
            }

            // Assign a stable color index by final position, so the graph line
            // and its legend row always share the same color.
            plots = plots.mapIndexed { index, p -> p.copy(colorIndex = index) }

            _model.value = Model(plots, currentKey, DIMENSIONS)
            // Dropped songs (scope change) invalidate any stale focus.
            if (_focusedKey.value != null && plots.none { it.key == _focusedKey.value }) {
                _focusedKey.value = null
            }
            recomputeDistance()
        }
    }

    /** Tap on a line or a legend row: toggle it as the focused/filtered song. */
    fun setFocused(key: String) {
        _focusedKey.value = if (_focusedKey.value == key) null else key
        recomputeDistance()
    }

    fun clearFocus() {
        _focusedKey.value = null
        _distanceToCurrent.value = null
    }

    private fun recomputeDistance() {
        val focused = _focusedKey.value
        val currentKey = _model.value.currentKey
        if (focused == null || currentKey == null || focused == currentKey) {
            _distanceToCurrent.value = null
            return
        }
        val plots = _model.value.plots
        val a = plots.firstOrNull { it.key == focused }?.vector
        val b = plots.firstOrNull { it.key == currentKey }?.vector
        _distanceToCurrent.value = if (a != null && b != null) 1f - cosine(a, b) else null
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    private companion object {
        const val BATCH = 500
        const val NEAREST_COUNT = 20
        const val DIMENSIONS = 24
    }
}

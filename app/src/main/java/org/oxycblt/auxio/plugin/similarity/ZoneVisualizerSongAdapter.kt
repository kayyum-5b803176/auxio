/*
 * Copyright (c) 2026 Auxio Project
 * ZoneVisualizerSongAdapter.kt is part of Auxio.
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

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemZoneVizSongBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames

/**
 * Legend list for the Zone Axis Visualizer: one row per plotted song, its color
 * swatch matching its line on the graph. Tapping a row toggles it as the
 * focused/filtered song — mirrors tapping the line directly on the canvas.
 */
class ZoneVisualizerSongAdapter(private val onRowTapped: (String) -> Unit) :
    ListAdapter<ZoneVisualizerViewModel.Plot, ZoneVisualizerSongAdapter.ViewHolder>(DIFFER) {

    private var focusedKey: String? = null
    private var currentKey: String? = null

    /** Update which row is focused without re-diffing the whole list. */
    fun setFocused(key: String?) {
        val old = focusedKey
        focusedKey = key
        currentList.forEachIndexed { index, plot ->
            if (plot.key == old || plot.key == key) notifyItemChanged(index)
        }
    }

    /** Update which row is the currently playing song. */
    fun setCurrentKey(key: String?) {
        val old = currentKey
        currentKey = key
        currentList.forEachIndexed { index, plot ->
            if (plot.key == old || plot.key == key) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemZoneVizSongBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val plot = getItem(position)
        holder.bind(plot, plot.key == focusedKey, plot.key == currentKey)
    }

    inner class ViewHolder(private val binding: ItemZoneVizSongBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(plot: ZoneVisualizerViewModel.Plot, focused: Boolean, isCurrent: Boolean) {
            val context = binding.root.context
            val color = ParallelCoordinatesView.paletteColor(plot.colorIndex)

            val swatch = GradientDrawable()
            swatch.shape = GradientDrawable.OVAL
            swatch.setColor(color)
            binding.zoneVizSongSwatch.background = swatch

            binding.zoneVizSongName.text = plot.song.name.resolve(context)
            val artists = plot.song.artists.resolveNames(context)
            binding.zoneVizSongSubtitle.text =
                if (artists.isNotEmpty()) artists else plot.song.album.name.resolve(context)
            binding.zoneVizSongSubtitle.isVisible = binding.zoneVizSongSubtitle.text.isNotEmpty()
            binding.zoneVizSongPlaying.isVisible = isCurrent

            binding.zoneVizSongName.setTypeface(null, if (focused) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            binding.root.alpha = if (focusedKey != null && !focused) 0.5f else 1f

            binding.root.setOnClickListener { onRowTapped(plot.key) }
        }
    }

    private companion object {
        val DIFFER =
            object : DiffUtil.ItemCallback<ZoneVisualizerViewModel.Plot>() {
                override fun areItemsTheSame(
                    oldItem: ZoneVisualizerViewModel.Plot,
                    newItem: ZoneVisualizerViewModel.Plot
                ) = oldItem.key == newItem.key

                override fun areContentsTheSame(
                    oldItem: ZoneVisualizerViewModel.Plot,
                    newItem: ZoneVisualizerViewModel.Plot
                ) =
                    // Compare stable fields only — vector is a FloatArray, which
                    // uses reference equality in a data class's generated
                    // equals(), so a plain oldItem == newItem would always
                    // report "changed" even when nothing meaningful differs.
                    oldItem.colorIndex == newItem.colorIndex && oldItem.tagged == newItem.tagged
            }
    }
}

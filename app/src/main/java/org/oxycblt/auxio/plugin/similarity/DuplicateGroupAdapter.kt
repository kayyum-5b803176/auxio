/*
 * Copyright (c) 2026 Auxio Project
 * DuplicateGroupAdapter.kt is part of Auxio.
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

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemDuplicateGroupBinding
import org.oxycblt.auxio.databinding.ItemDuplicateSongBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Song

/**
 * Displays [DuplicateFinder.DuplicateGroup]s as cards, each listing its songs
 * with the metadata a user needs to choose which copy to keep: format,
 * bitrate, sample rate, file size, and full path. Songs are pre-sorted
 * highest-quality-first by [DuplicateFinder]; the first row gets a
 * "highest quality" hint.
 */
class DuplicateGroupAdapter(private val listener: Listener) :
    ListAdapter<DuplicateFinder.DuplicateGroup, DuplicateGroupAdapter.GroupViewHolder>(DIFFER) {

    interface Listener {
        fun onDeleteRequested(song: Song)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        GroupViewHolder(
            ItemDuplicateGroupBinding.inflate(
                LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position), listener)
    }

    class GroupViewHolder(private val binding: ItemDuplicateGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(group: DuplicateFinder.DuplicateGroup, listener: Listener) {
            val context = binding.root.context
            binding.duplicateGroupTitle.text =
                context.getString(
                    R.string.dup_group_title, (group.minSimilarity * 100).toInt())

            binding.duplicateGroupSongs.removeAllViews()
            val inflater = LayoutInflater.from(context)
            group.songs.forEachIndexed { index, song ->
                val songBinding =
                    ItemDuplicateSongBinding.inflate(
                        inflater, binding.duplicateGroupSongs, true)
                songBinding.duplicateSongTitle.text = song.name.resolve(context)
                songBinding.duplicateSongKeepHint.isVisible = index == 0 && group.songs.size > 1
                songBinding.duplicateSongDetails.text =
                    listOf(
                            song.format.resolve(context),
                            context.getString(R.string.fmt_bitrate, song.bitrateKbps),
                            context.getString(R.string.fmt_sample_rate, song.sampleRateHz),
                            Formatter.formatFileSize(context, song.size))
                        .joinToString(" • ")
                songBinding.duplicateSongPath.text = song.path.resolve(context)
                songBinding.duplicateSongDelete.setOnClickListener {
                    listener.onDeleteRequested(song)
                }
            }
        }
    }

    private companion object {
        val DIFFER =
            object : DiffUtil.ItemCallback<DuplicateFinder.DuplicateGroup>() {
                override fun areItemsTheSame(
                    oldItem: DuplicateFinder.DuplicateGroup,
                    newItem: DuplicateFinder.DuplicateGroup
                ) = oldItem.songs.firstOrNull()?.uid == newItem.songs.firstOrNull()?.uid

                override fun areContentsTheSame(
                    oldItem: DuplicateFinder.DuplicateGroup,
                    newItem: DuplicateFinder.DuplicateGroup
                ) = oldItem == newItem
            }
    }
}

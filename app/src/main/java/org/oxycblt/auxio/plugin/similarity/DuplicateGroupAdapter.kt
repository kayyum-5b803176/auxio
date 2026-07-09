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

import android.content.Context
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

/** Resolve a structured [QualityAnalyzer.Reason] to a localized string. */
private fun resolveReason(context: Context, reason: QualityAnalyzer.Reason): String =
    when (reason) {
        is QualityAnalyzer.Reason.RealLossless ->
            context.getString(R.string.dup_reason_real_lossless, reason.formatName)
        is QualityAnalyzer.Reason.MostDetail ->
            context.getString(R.string.dup_reason_most_detail, reason.formatName)
        is QualityAnalyzer.Reason.FakeLossless ->
            context.getString(
                R.string.dup_reason_fake_lossless, reason.formatName, reason.approxKHz)
        is QualityAnalyzer.Reason.LessDetail ->
            context.getString(
                R.string.dup_reason_less_detail, reason.formatName, reason.approxKHz)
        is QualityAnalyzer.Reason.EquivalentKept ->
            context.getString(R.string.dup_reason_equivalent_kept, reason.formatName)
        is QualityAnalyzer.Reason.EquivalentDuplicate ->
            context.getString(R.string.dup_reason_equivalent_duplicate, reason.formatName)
        is QualityAnalyzer.Reason.Unanalyzable ->
            context.getString(R.string.dup_reason_unanalyzable, reason.formatName)
        is QualityAnalyzer.Reason.Single ->
            context.getString(R.string.dup_reason_single, reason.formatName)
    }

/**
 * Displays [DuplicateFinder.DuplicateGroup]s as cards, each listing its songs
 * with the metadata a user needs to choose which copy to keep: format,
 * bitrate, sample rate, file size, and full path. Songs are pre-sorted
 * highest-quality-first by [DuplicateFinder]; the first row gets a
 * "highest quality" hint.
 */
class DuplicateGroupAdapter(private val listener: Listener) :
    ListAdapter<DuplicatesViewModel.RankedGroup, DuplicateGroupAdapter.GroupViewHolder>(DIFFER) {

    interface Listener {
        fun onDeleteRequested(song: Song, prioritized: Boolean)
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

        fun bind(group: DuplicatesViewModel.RankedGroup, listener: Listener) {
            val context = binding.root.context
            binding.duplicateGroupTitle.text =
                context.getString(
                    R.string.dup_group_title, (group.minSimilarity * 100).toInt())

            // Warning shown when a priority-folder file was kept over a
            // higher-quality copy elsewhere, so the user can override manually.
            binding.duplicateGroupWarning.isVisible = group.keptLowerQualityWarning

            binding.duplicateGroupSongs.removeAllViews()
            val inflater = LayoutInflater.from(context)
            group.ranked.forEach { ranked ->
                val song = ranked.song
                val songBinding =
                    ItemDuplicateSongBinding.inflate(
                        inflater, binding.duplicateGroupSongs, true)
                songBinding.duplicateSongTitle.text = song.name.resolve(context)
                songBinding.duplicateSongKeepHint.isVisible = ranked.isRecommendedKeep
                // The quality reason — WHY this file was ranked where it was,
                // e.g. "Fake lossless — FLAC cut off ~16 kHz". This is the
                // explanation the user asked to see. Resolved from the
                // structured Reason here (QualityAnalyzer has no Context).
                songBinding.duplicateSongReason.text = resolveReason(context, ranked.reason)
                songBinding.duplicateSongDetails.text =
                    listOf(
                            song.format.resolve(context),
                            context.getString(R.string.fmt_bitrate, song.bitrateKbps),
                            context.getString(R.string.fmt_sample_rate, song.sampleRateHz),
                            Formatter.formatFileSize(context, song.size))
                        .joinToString(" • ")
                songBinding.duplicateSongPath.text = song.path.resolve(context)
                songBinding.duplicateSongDelete.setOnClickListener {
                    listener.onDeleteRequested(song, ranked.prioritized)
                }
            }
        }
    }

    private companion object {
        val DIFFER =
            object : DiffUtil.ItemCallback<DuplicatesViewModel.RankedGroup>() {
                override fun areItemsTheSame(
                    oldItem: DuplicatesViewModel.RankedGroup,
                    newItem: DuplicatesViewModel.RankedGroup
                ) =
                    oldItem.ranked.firstOrNull()?.song?.uid ==
                        newItem.ranked.firstOrNull()?.song?.uid

                override fun areContentsTheSame(
                    oldItem: DuplicatesViewModel.RankedGroup,
                    newItem: DuplicatesViewModel.RankedGroup
                ) = oldItem == newItem
            }
    }
}

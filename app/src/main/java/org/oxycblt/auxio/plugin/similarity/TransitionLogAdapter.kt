/*
 * Copyright (c) 2026 Auxio Project
 * TransitionLogAdapter.kt is part of Auxio.
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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemTransitionLogBinding

/** Read-only list of outgoing transitions from the current song, strongest-first. */
class TransitionLogAdapter :
    ListAdapter<TransitionRow, TransitionLogAdapter.ViewHolder>(DIFFER) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemTransitionLogBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemTransitionLogBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: TransitionRow) {
            binding.transitionTo.text = row.toName
            binding.transitionCounts.text =
                binding.root.context.getString(
                    org.oxycblt.auxio.R.string.fmt_transition_row,
                    row.plays.toString(),
                    row.skips.toString())
            binding.transitionStrength.text = "${(row.strength * 100).toInt()}%"
        }
    }

    private companion object {
        val DIFFER =
            object : DiffUtil.ItemCallback<TransitionRow>() {
                override fun areItemsTheSame(oldItem: TransitionRow, newItem: TransitionRow) =
                    oldItem.toName == newItem.toName

                override fun areContentsTheSame(oldItem: TransitionRow, newItem: TransitionRow) =
                    oldItem == newItem
            }
    }
}

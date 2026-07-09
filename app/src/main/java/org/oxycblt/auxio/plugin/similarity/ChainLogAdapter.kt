/*
 * Copyright (c) 2026 Auxio Project
 * ChainLogAdapter.kt is part of Auxio.
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
import org.oxycblt.auxio.databinding.ItemChainLogBinding

/** Renders the Smart Chain log entries (newest first). */
class ChainLogAdapter :
    ListAdapter<ChainLog.Entry, ChainLogAdapter.LogViewHolder>(DIFFER) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        LogViewHolder(
            ItemChainLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(private val binding: ItemChainLogBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: ChainLog.Entry) {
            binding.chainLogTime.text = entry.formattedTime()
            binding.chainLogMessage.text = entry.message
        }
    }

    private companion object {
        val DIFFER =
            object : DiffUtil.ItemCallback<ChainLog.Entry>() {
                override fun areItemsTheSame(oldItem: ChainLog.Entry, newItem: ChainLog.Entry) =
                    oldItem.timestampMs == newItem.timestampMs &&
                        oldItem.message == newItem.message

                override fun areContentsTheSame(
                    oldItem: ChainLog.Entry,
                    newItem: ChainLog.Entry
                ) = oldItem == newItem
            }
    }
}

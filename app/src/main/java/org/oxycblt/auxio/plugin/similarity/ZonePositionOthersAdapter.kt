/*
 * Copyright (c) 2026 Auxio Project
 * ZonePositionOthersAdapter.kt is part of Auxio.
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
import kotlin.math.abs
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemZonePosOtherBinding

/**
 * Read-only list of the OTHER values on the same axis as the value being edited,
 * each showing its own position and its raw single-axis gap from the edited
 * value. Sorted nearest-first. Rebuilt whenever the edited value's slider moves.
 */
class ZonePositionOthersAdapter :
    ListAdapter<ZonePositionOthersAdapter.Item, ZonePositionOthersAdapter.ViewHolder>(DIFFER) {

    /** An other-value row: its label, its own position, and the gap to compare against. */
    data class Item(val id: Long, val label: String, val position: Float, val gap: Float)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemZonePosOtherBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemZonePosOtherBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Item) {
            binding.zonePosOtherLabel.text = item.label
            binding.zonePosOtherPosition.text = "%+.2f".format(item.position)
            binding.zonePosOtherGap.text =
                binding.root.context.getString(R.string.lbl_zone_pos_gap, "%.2f".format(item.gap))
        }
    }

    /**
     * Build a nearest-first list of [others] compared against [selfPosition].
     * Gap is the raw single-axis absolute difference (0..2).
     */
    fun submitAgainst(selfPosition: Float, others: List<ZoneAxisValue>) {
        submitList(
            others
                .map { Item(it.id, it.label, it.position, abs(it.position - selfPosition)) }
                .sortedBy { it.gap })
    }

    private companion object {
        val DIFFER =
            object : DiffUtil.ItemCallback<Item>() {
                override fun areItemsTheSame(oldItem: Item, newItem: Item) =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
            }
    }
}

/*
 * Copyright (c) 2026 Auxio Project
 * ZoneAxisAdapter.kt is part of Auxio.
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
import org.oxycblt.auxio.databinding.ItemZoneHeaderBinding
import org.oxycblt.auxio.databinding.ItemZoneValueBinding

/**
 * Renders the Zone Axis manage screen as a flat, sectioned list: a header row
 * per axis (with an add button) followed by that axis's value rows (each with
 * rename-on-tap and a delete button).
 */
class ZoneAxisAdapter(
    private val onAdd: (axis: String) -> Unit,
    private val onRename: (value: ZoneAxisValue) -> Unit,
    private val onDelete: (value: ZoneAxisValue) -> Unit
) : ListAdapter<ZoneAxisAdapter.Row, RecyclerView.ViewHolder>(DIFFER) {

    /** A flat row: either a section header or a value. */
    sealed interface Row {
        data class Header(val axis: String) : Row

        data class Value(val value: ZoneAxisValue) : Row
    }

    override fun getItemViewType(position: Int) =
        when (getItem(position)) {
            is Row.Header -> TYPE_HEADER
            is Row.Value -> TYPE_VALUE
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemZoneHeaderBinding.inflate(inflater, parent, false))
        } else {
            ValueViewHolder(ItemZoneValueBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row.axis)
            is Row.Value -> (holder as ValueViewHolder).bind(row.value)
        }
    }

    inner class HeaderViewHolder(private val binding: ItemZoneHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(axis: String) {
            binding.zoneHeaderTitle.text = axis
            binding.zoneHeaderAdd.setOnClickListener { onAdd(axis) }
        }
    }

    inner class ValueViewHolder(private val binding: ItemZoneValueBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(value: ZoneAxisValue) {
            binding.zoneValueLabel.text = value.label
            binding.root.setOnClickListener { onRename(value) }
            binding.zoneValueDelete.setOnClickListener { onDelete(value) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_VALUE = 1

        val DIFFER =
            object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(oldItem: Row, newItem: Row) =
                    when {
                        oldItem is Row.Header && newItem is Row.Header ->
                            oldItem.axis == newItem.axis
                        oldItem is Row.Value && newItem is Row.Value ->
                            oldItem.value.id == newItem.value.id
                        else -> false
                    }

                override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
            }
    }
}

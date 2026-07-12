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
import com.google.android.material.slider.Slider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemZonePosOtherBinding

/**
 * Editable list of the OTHER values on the same axis as the value being edited.
 * Each row has its own slider setting the symmetric relative value between that
 * value and the one being edited (-1..+1, positive = similar, negative =
 * opposite). Commits on release via [onRelationSet].
 */
class ZonePositionOthersAdapter(
    private val onRelationSet: (otherValueId: Long, relation: Float) -> Unit
) : ListAdapter<ZonePositionOthersAdapter.Item, ZonePositionOthersAdapter.ViewHolder>(DIFFER) {

    /** An other-value row: its id, label, and the currently-stored relation. */
    data class Item(val id: Long, val label: String, val relation: Float)

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
            binding.zonePosOtherValue.text = "%+.2f".format(item.relation)
            // Clear any previous listeners before setting the value, so recycled
            // rows don't fire the old row's callback when we programmatically
            // set the slider position.
            binding.zonePosOtherSlider.clearOnChangeListeners()
            binding.zonePosOtherSlider.clearOnSliderTouchListeners()
            binding.zonePosOtherSlider.value = item.relation.coerceIn(-1f, 1f)
            binding.zonePosOtherSlider.addOnChangeListener { _, value, _ ->
                binding.zonePosOtherValue.text = "%+.2f".format(value)
            }
            binding.zonePosOtherSlider.addOnSliderTouchListener(
                object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {}

                    override fun onStopTrackingTouch(slider: Slider) {
                        onRelationSet(item.id, slider.value)
                    }
                })
        }
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

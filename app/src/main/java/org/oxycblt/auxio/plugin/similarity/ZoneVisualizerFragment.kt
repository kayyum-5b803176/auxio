/*
 * Copyright (c) 2026 Auxio Project
 * ZoneVisualizerFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentZoneVisualizerBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getAttrColorCompat

/**
 * The Zone Axis Visualizer: a parallel-coordinates plot of every song's real
 * 24-dimensional Smart Chain vector. Reached from the overview overflow menu.
 */
@AndroidEntryPoint
class ZoneVisualizerFragment : ViewBindingFragment<FragmentZoneVisualizerBinding>() {
    private val vizModel: ZoneVisualizerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentZoneVisualizerBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentZoneVisualizerBinding,
        savedInstanceState: Bundle?
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.zoneVizToolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        val ctx = requireContext()
        binding.zoneVizCanvas.setThemeColors(
            axis = ctx.getAttrColorCompat(com.google.android.material.R.attr.colorOutlineVariant)
                .defaultColor,
            untagged =
                androidx.core.graphics.ColorUtils.setAlphaComponent(
                    ctx.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
                        .defaultColor,
                    0x55),
            tagged =
                androidx.core.graphics.ColorUtils.setAlphaComponent(
                    ctx.getAttrColorCompat(com.google.android.material.R.attr.colorTertiary)
                        .defaultColor,
                    0xAA),
            current = ctx.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor,
            search = ctx.getAttrColorCompat(androidx.appcompat.R.attr.colorError)
                .defaultColor,
            selected = ctx.getAttrColorCompat(com.google.android.material.R.attr.colorTertiary)
                .defaultColor)

        binding.zoneVizCanvas.onLineTapped = { key -> vizModel.toggleSelection(key) }

        binding.zoneVizReset.setOnClickListener { binding.zoneVizCanvas.resetView() }

        // Scope chips.
        binding.zoneVizScopeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val scope =
                when (id) {
                    R.id.zone_viz_scope_queue -> ZoneVisualizerViewModel.Scope.QUEUE
                    R.id.zone_viz_scope_nearest -> ZoneVisualizerViewModel.Scope.NEAREST
                    R.id.zone_viz_scope_all -> ZoneVisualizerViewModel.Scope.ALL
                    else -> ZoneVisualizerViewModel.Scope.QUEUE
                }
            vizModel.setScope(scope)
        }

        // Search: highlight the first song whose name contains the query.
        binding.zoneVizSearch.doAfterTextChanged { text ->
            val query = text?.toString()?.trim().orEmpty()
            if (query.isEmpty()) {
                binding.zoneVizCanvas.setSearch(null)
                return@doAfterTextChanged
            }
            val match =
                vizModel.model.value.plots.firstOrNull {
                    it.song.name.resolve(ctx).contains(query, ignoreCase = true)
                }
            binding.zoneVizCanvas.setSearch(match?.key)
        }

        collectImmediately(vizModel.model) { model ->
            binding.zoneVizCanvas.submit(model)
            binding.zoneVizEmpty.isVisible = model.plots.isEmpty()
        }
        collectImmediately(vizModel.selection) { selection ->
            binding.zoneVizCanvas.setSelection(selection.toSet())
        }
        collectImmediately(vizModel.distance) { distance ->
            if (distance == null) {
                binding.zoneVizDistance.isVisible = false
            } else {
                binding.zoneVizDistance.isVisible = true
                binding.zoneVizDistance.text =
                    getString(R.string.lbl_zone_viz_distance, "%.3f".format(distance))
            }
        }
    }
}

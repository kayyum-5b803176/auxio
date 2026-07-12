/*
 * Copyright (c) 2026 Auxio Project
 * ZoneValuePositionFragment.kt is part of Auxio.
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentZoneValuePositionBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately

/**
 * Per-tag position editor. Edits ONE value's position on its axis; the other
 * values on the same axis are shown read-only for reference (nearest-first by
 * raw single-axis gap). Rename/Delete live in the toolbar overflow.
 */
@AndroidEntryPoint
class ZoneValuePositionFragment : ViewBindingFragment<FragmentZoneValuePositionBinding>() {
    private val zoneModel: ZoneAxisViewModel by viewModels()
    private val args: ZoneValuePositionFragmentArgs by navArgs()
    private val othersAdapter =
        ZonePositionOthersAdapter { otherId, relation ->
            zoneModel.setRelation(args.valueId, otherId, relation)
        }

    /** Latch: true once the edited value has resolved at least once, so we only
     * auto-leave on a genuine delete, not during initial staggered flow load. */
    private var seenValue = false

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentZoneValuePositionBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentZoneValuePositionBinding,
        savedInstanceState: Bundle?
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.zonePosToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.zonePosOthers.adapter = othersAdapter

        binding.zonePosToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename -> {
                    showRenameDialog()
                    true
                }
                R.id.action_delete -> {
                    showDeleteDialog()
                    true
                }
                else -> false
            }
        }

        // Observe both axes' values; find our value + its same-axis siblings.
        collectImmediately(zoneModel.languageValues, zoneModel.typeValues) { languages, types ->
            val self =
                languages.firstOrNull { it.id == args.valueId }
                    ?: types.firstOrNull { it.id == args.valueId }
            if (self != null) seenValue = true
            if (self == null) {
                // Only leave if we had ALREADY resolved the value once and it
                // then disappeared (genuine delete). Avoids the false-positive
                // during initial load where the axis flows start at emptyList().
                if (seenValue) findNavController().navigateUp()
                return@collectImmediately
            }
            binding.zonePosToolbar.title =
                getString(R.string.fmt_zone_pos_title, self.label, self.axis)

            val siblings =
                (if (self.axis == ZoneAxis.LANGUAGE) languages else types).filter {
                    it.id != self.id
                }
            binding.zonePosOthers.isVisible = siblings.isNotEmpty()

            // Load stored relations, then build one editable row per sibling
            // (unset pairs show 0 = neutral).
            viewLifecycleOwner.lifecycleScope.launch {
                val relations = zoneModel.relationsForValue(self.id)
                othersAdapter.submitList(
                    siblings.map {
                        ZonePositionOthersAdapter.Item(it.id, it.label, relations[it.id] ?: 0f)
                    })
            }
        }
    }

    private fun showRenameDialog() {
        val languages = zoneModel.languageValues.value
        val types = zoneModel.typeValues.value
        val self =
            languages.firstOrNull { it.id == args.valueId }
                ?: types.firstOrNull { it.id == args.valueId } ?: return
        val layout = TextInputLayout(requireContext())
        val input = TextInputEditText(requireContext())
        input.setText(self.label)
        layout.addView(input)
        val pad = resources.getDimensionPixelSize(R.dimen.spacing_medium)
        layout.setPadding(pad, pad, pad, 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_zone_rename)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                zoneModel.renameValue(args.valueId, input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog() {
        val languages = zoneModel.languageValues.value
        val types = zoneModel.typeValues.value
        val self =
            languages.firstOrNull { it.id == args.valueId }
                ?: types.firstOrNull { it.id == args.valueId } ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val count = zoneModel.countUsers(args.valueId)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lbl_zone_delete)
                .setMessage(getString(R.string.lbl_zone_delete_confirm, self.label, count))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    zoneModel.deleteValue(args.valueId)
                    findNavController().navigateUp()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}

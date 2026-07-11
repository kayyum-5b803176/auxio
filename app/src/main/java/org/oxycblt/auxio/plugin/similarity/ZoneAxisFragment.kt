/*
 * Copyright (c) 2026 Auxio Project
 * ZoneAxisFragment.kt is part of Auxio.
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
import android.widget.EditText
import android.widget.FrameLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentZoneAxisBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe

/**
 * The Zone Axis management screen: CRUD over the user-defined Language and Type
 * value lists. Reached from Settings → Plugins → Manage zone axes.
 */
@AndroidEntryPoint
class ZoneAxisFragment : ViewBindingFragment<FragmentZoneAxisBinding>() {
    private val zoneModel: ZoneAxisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentZoneAxisBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentZoneAxisBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.zoneAxisToolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        val adapter =
            ZoneAxisAdapter(
                onAdd = { axis -> showAddDialog(axis) },
                onEditPosition = { value ->
                    findNavController()
                        .navigateSafe(
                            ZoneAxisFragmentDirections.editZonePosition(value.id))
                },
                onDelete = { value -> showDeleteDialog(value) })
        binding.zoneAxisRecycler.adapter = adapter

        // Build one flat, sectioned list from both axes' value flows.
        collectImmediately(zoneModel.languageValues, zoneModel.typeValues) { languages, types ->
            val rows =
                buildList {
                    add(ZoneAxisAdapter.Row.Header(ZoneAxis.LANGUAGE))
                    languages.forEach { add(ZoneAxisAdapter.Row.Value(it)) }
                    add(ZoneAxisAdapter.Row.Header(ZoneAxis.TYPE))
                    types.forEach { add(ZoneAxisAdapter.Row.Value(it)) }
                }
            adapter.submitList(rows)
        }
    }

    private fun textEntryDialog(
        titleText: String,
        prefill: String,
        onConfirm: (String) -> Unit
    ) {
        val context = requireContext()
        val input =
            EditText(context).apply {
                hint = getString(R.string.lbl_zone_value_hint)
                setText(prefill)
                setSingleLine()
            }
        // Pad the input so it isn't flush against the dialog edges.
        val container =
            FrameLayout(context).apply {
                val pad = resources.getDimensionPixelSize(R.dimen.spacing_large)
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        MaterialAlertDialogBuilder(context)
            .setTitle(titleText)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) onConfirm(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddDialog(axis: String) {
        textEntryDialog(getString(R.string.lbl_zone_add_title, axis), prefill = "") { text ->
            zoneModel.addValue(axis, text)
        }
    }

    private fun showRenameDialog(value: ZoneAxisValue) {
        textEntryDialog(getString(R.string.lbl_zone_rename_title), prefill = value.label) { text ->
            zoneModel.renameValue(value.id, text)
        }
    }

    private fun showDeleteDialog(value: ZoneAxisValue) {
        // Look up how many songs are affected before confirming.
        viewLifecycleOwner.lifecycleScope.launch {
            val count = zoneModel.countUsers(value.id)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lbl_zone_delete)
                .setMessage(getString(R.string.lbl_zone_delete_confirm, value.label, count))
                .setPositiveButton(R.string.lbl_zone_delete) { _, _ ->
                    zoneModel.deleteValue(value.id)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}

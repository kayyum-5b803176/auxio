/*
 * Copyright (c) 2026 Auxio Project
 * PriorityFoldersDialog.kt is part of Auxio.
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
import androidx.appcompat.app.AlertDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogPriorityFoldersBinding
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment

/**
 * Lets the user edit the list of "priority" folder names, one per line.
 *
 * Kept intentionally simple (a single multi-line text field rather than an
 * add/remove row list): folder names are short and few, and free-text entry
 * with one-name-per-line is the least fiddly way to manage them. Names are
 * normalized (trimmed, blanks dropped, de-duplicated case-insensitively) by
 * PluginSettings on save.
 */
@AndroidEntryPoint
class PriorityFoldersDialog : ViewBindingMaterialDialogFragment<DialogPriorityFoldersBinding>() {
    @Inject lateinit var pluginSettings: PluginSettings

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogPriorityFoldersBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.set_priority_folders)
            .setPositiveButton(R.string.lbl_ok) { _, _ ->
                val text = requireBinding().priorityFoldersInput.text?.toString().orEmpty()
                // One folder name per line; PluginSettings normalizes further.
                pluginSettings.priorityFolderNames =
                    text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            }
            .setNegativeButton(android.R.string.cancel, null)
    }

    override fun onBindingCreated(
        binding: DialogPriorityFoldersBinding,
        savedInstanceState: Bundle?
    ) {
        if (savedInstanceState == null) {
            binding.priorityFoldersInput.setText(
                pluginSettings.priorityFolderNames.joinToString("\n"))
        }
    }
}

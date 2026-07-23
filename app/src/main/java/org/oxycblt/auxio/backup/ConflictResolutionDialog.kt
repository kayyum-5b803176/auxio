/*
 * Copyright (c) 2026 Auxio Project
 * ConflictResolutionDialog.kt is part of Auxio.
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

package org.oxycblt.auxio.backup

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.R

/**
 * Presents every merge conflict from an import dry run in ONE batched
 * screen, each with a "keep mine" / "use backup's" choice, defaulting to
 * keeping the current device's value (the safe, no-surprise default). When
 * the user confirms, it hands the resolutions back to [BackupFragment] to
 * apply the import.
 *
 * Conflicts are passed as parallel string arrays in the fragment arguments
 * rather than via a Parcelable [Conflict], keeping the data-layer types free
 * of Android/UI concerns. Only display strings and the stable conflict key
 * are needed here, which are all plain strings.
 */
class ConflictResolutionDialog : DialogFragment() {
    private lateinit var keys: Array<String>
    private lateinit var groups: MutableList<RadioGroup>

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val uri = Uri.parse(args.getString(ARG_URI))
        keys = args.getStringArray(ARG_KEYS) ?: emptyArray()
        val descriptions = args.getStringArray(ARG_DESCRIPTIONS) ?: emptyArray()
        val currents = args.getStringArray(ARG_CURRENTS) ?: emptyArray()
        val incomings = args.getStringArray(ARG_INCOMINGS) ?: emptyArray()

        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), dp(8))
            }

        groups = mutableListOf()
        for (i in keys.indices) {
            container.addView(
                TextView(context).apply {
                    text = descriptions.getOrElse(i) { "" }
                    setPadding(0, dp(12), 0, dp(4))
                    textSize = 15f
                })
            val group =
                RadioGroup(context).apply {
                    val keepButton =
                        RadioButton(context).apply {
                            id = View_KEEP
                            text = getString(R.string.fmt_backup_conflict_keep, currents.getOrElse(i) { "" })
                        }
                    val useButton =
                        RadioButton(context).apply {
                            id = View_USE
                            text =
                                getString(R.string.fmt_backup_conflict_use, incomings.getOrElse(i) { "" })
                        }
                    addView(keepButton)
                    addView(useButton)
                    // Default: keep current (no surprises).
                    check(View_KEEP)
                }
            groups.add(group)
            container.addView(group)
        }

        val scroll =
            ScrollView(context).apply {
                addView(
                    container,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_backup_conflicts)
            .setMessage(R.string.set_backup_conflicts_desc)
            .setView(scroll)
            .setPositiveButton(R.string.lbl_backup_apply) { _, _ ->
                val resolutions = HashMap<String, ConflictResolution>()
                for (i in keys.indices) {
                    resolutions[keys[i]] =
                        if (groups[i].checkedRadioButtonId == View_USE) ConflictResolution.USE_INCOMING
                        else ConflictResolution.KEEP_CURRENT
                }
                (parentFragment as? BackupFragment)?.onConflictsResolved(uri, resolutions)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Cancelling abandons the import; the dry run wrote nothing, so
                // no state needs undoing. The screen's import state is reset by
                // the fragment when it next resumes / the user acts again.
                (parentFragment as? BackupFragment)?.onImportCancelled()
            }
            .create()
    }

    companion object {
        const val TAG = "backup_conflict_resolution"

        private const val View_KEEP = 1
        private const val View_USE = 2

        private const val ARG_URI = "uri"
        private const val ARG_KEYS = "keys"
        private const val ARG_DESCRIPTIONS = "descriptions"
        private const val ARG_CURRENTS = "currents"
        private const val ARG_INCOMINGS = "incomings"

        fun newInstance(uri: Uri, conflicts: List<Conflict>): ConflictResolutionDialog =
            ConflictResolutionDialog().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_URI, uri.toString())
                        putStringArray(ARG_KEYS, conflicts.map { it.conflictKey }.toTypedArray())
                        putStringArray(ARG_DESCRIPTIONS, conflicts.map { it.description }.toTypedArray())
                        putStringArray(ARG_CURRENTS, conflicts.map { it.currentValue }.toTypedArray())
                        putStringArray(ARG_INCOMINGS, conflicts.map { it.incomingValue }.toTypedArray())
                    }
            }
    }
}

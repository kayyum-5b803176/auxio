/*
 * Copyright (c) 2026 Auxio Project
 * BackupFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.util.Date
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.util.collectImmediately
import timber.log.Timber as L

/**
 * The top-level Backup settings screen. Offers a single, reliable
 * import/export of all app data (Smart Chain, Zone Axis, acoustic scan
 * cache, priority folders, plugin toggles, and playlists) as one archive.
 *
 * Import is a merge, never a replace: existing data is kept and the backup's
 * data is folded in on top, so importing another device's backup adds that
 * device's learning to this one without losing anything already here.
 */
@AndroidEntryPoint
class BackupFragment : BasePreferenceFragment(R.xml.preferences_backup) {
    private val backupModel: BackupViewModel by viewModels()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
            if (uri != null) backupModel.export(uri) else L.d("Export cancelled")
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) backupModel.beginImport(uri) else L.d("Import cancelled")
        }

    override fun onSetupPreference(preference: Preference) {
        when (preference.key) {
            getString(R.string.set_key_backup_export) -> {
                preference.setOnPreferenceClickListener {
                    exportLauncher.launch(defaultBackupName())
                    true
                }
            }
            getString(R.string.set_key_backup_import) -> {
                preference.setOnPreferenceClickListener {
                    // Accept our own mime plus generic zip/octet-stream, since
                    // providers label .auxbak inconsistently.
                    importLauncher.launch(arrayOf(BACKUP_MIME, "application/zip", "application/octet-stream", "*/*"))
                    true
                }
            }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectImmediately(backupModel.exportState, ::handleExportState)
        collectImmediately(backupModel.importState, ::handleImportState)
    }

    private fun handleExportState(state: BackupViewModel.ExportState) {
        when (state) {
            is BackupViewModel.ExportState.Done -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.lbl_backup_export_done)
                    .setMessage(getString(R.string.fmt_backup_export_done, state.moduleCount))
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener { backupModel.consumeExportState() }
                    .show()
            }
            is BackupViewModel.ExportState.Error -> {
                showError(state.message) { backupModel.consumeExportState() }
            }
            else -> {}
        }
    }

    private fun handleImportState(state: BackupViewModel.ImportState) {
        when (state) {
            is BackupViewModel.ImportState.NeedsResolution -> {
                ConflictResolutionDialog.newInstance(state.uri, state.plan.pendingConflicts)
                    .show(childFragmentManager, ConflictResolutionDialog.TAG)
            }
            is BackupViewModel.ImportState.Done -> {
                showImportSummary(state.plan)
                backupModel.consumeImportState()
            }
            is BackupViewModel.ImportState.Error -> {
                showError(state.message) { backupModel.consumeImportState() }
            }
            else -> {}
        }
    }

    private fun showImportSummary(plan: ImportPlan) {
        val lines = StringBuilder()
        for (outcome in plan.outcomes) {
            when (outcome) {
                is ModuleImportOutcome.Applied -> {
                    val r = outcome.result
                    lines.append(
                        getString(
                            R.string.fmt_backup_module_applied,
                            outcome.displayName,
                            r.added,
                            r.updated))
                }
                is ModuleImportOutcome.NotPresent ->
                    lines.append(getString(R.string.fmt_backup_module_absent, outcome.displayName))
                is ModuleImportOutcome.AlreadyApplied ->
                    lines.append(getString(R.string.fmt_backup_module_already, outcome.displayName))
                is ModuleImportOutcome.SkippedUnsupported ->
                    lines.append(
                        getString(R.string.fmt_backup_module_skipped, outcome.displayName))
            }
            lines.append('\n')
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_backup_import_done)
            .setMessage(lines.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showError(message: String, onDismiss: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_backup_error)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    private fun defaultBackupName(): String {
        val stamp = DateFormat.format("yyyy-MM-dd", Date()).toString()
        return "auxio-backup-$stamp.$BACKUP_EXTENSION"
    }

    /** Called by [ConflictResolutionDialog] once the user has chosen every resolution. */
    fun onConflictsResolved(uri: android.net.Uri, resolutions: Map<String, ConflictResolution>) {
        backupModel.applyImport(uri, resolutions)
    }

    /** Called by [ConflictResolutionDialog] when the user cancels the import. */
    fun onImportCancelled() {
        backupModel.consumeImportState()
    }

    private companion object {
        const val BACKUP_MIME = "application/vnd.auxio.backup+zip"
        const val BACKUP_EXTENSION = "auxbak"
    }
}

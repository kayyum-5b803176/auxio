/*
 * Copyright (c) 2026 Auxio Project
 * BackupViewModel.kt is part of Auxio.
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

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber as L

/**
 * Drives the Backup screen: exporting a backup archive to a user-chosen
 * location, and importing one via a safe two-phase flow (dry run to detect
 * conflicts, then apply once the user has resolved them).
 */
@HiltViewModel
class BackupViewModel
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val coordinator: BackupCoordinator
) : ViewModel() {

    /** State of an in-progress or finished export. */
    sealed interface ExportState {
        data object Idle : ExportState

        data object Running : ExportState

        data class Done(val moduleCount: Int) : ExportState

        data class Error(val message: String) : ExportState
    }

    /** State of the import flow. */
    sealed interface ImportState {
        data object Idle : ImportState

        data object Running : ImportState

        /** Dry run finished and found conflicts the user must resolve before applying. */
        data class NeedsResolution(val uri: Uri, val plan: ImportPlan) : ImportState

        /** Import finished (either had no conflicts, or they were resolved). */
        data class Done(val plan: ImportPlan) : ImportState

        data class Error(val message: String) : ImportState
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    /** Write a backup archive containing every module with data to [uri]. */
    fun export(uri: Uri) {
        _exportState.value = ExportState.Running
        viewModelScope.launch {
            try {
                val count =
                    withContext(Dispatchers.IO) {
                        val modules = coordinator.modulesWithData()
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            coordinator.export(out, modules)
                        } ?: error("Could not open the selected file for writing")
                        modules.size
                    }
                _exportState.value = ExportState.Done(count)
            } catch (e: Exception) {
                L.e("Export failed: $e")
                _exportState.value = ExportState.Error(e.message ?: "Export failed")
            }
        }
    }

    /**
     * Phase 1: read [uri] and compute what an import would do without
     * changing anything. If there are no conflicts, immediately proceeds to
     * apply; otherwise surfaces the conflicts for the user to resolve.
     */
    fun beginImport(uri: Uri) {
        _importState.value = ImportState.Running
        viewModelScope.launch {
            try {
                val plan =
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            coordinator.dryRun(input)
                        } ?: error("Could not open the selected file for reading")
                    }
                if (plan.hasConflicts) {
                    _importState.value = ImportState.NeedsResolution(uri, plan)
                } else {
                    applyImport(uri, emptyMap())
                }
            } catch (e: BackupFormatException) {
                _importState.value = ImportState.Error(e.message ?: "Not a valid backup file")
            } catch (e: Exception) {
                L.e("Import dry run failed: $e")
                _importState.value = ImportState.Error(e.message ?: "Import failed")
            }
        }
    }

    /**
     * Phase 2: actually write the merged data using the user's [resolutions]
     * (empty when there were no conflicts). Re-reads the file so the applied
     * pass sees exactly what the dry run did.
     */
    fun applyImport(uri: Uri, resolutions: Map<String, ConflictResolution>) {
        _importState.value = ImportState.Running
        viewModelScope.launch {
            try {
                val plan =
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            coordinator.applyPlan(input, resolutions)
                        } ?: error("Could not re-open the selected file")
                    }
                _importState.value = ImportState.Done(plan)
            } catch (e: Exception) {
                L.e("Import apply failed: $e")
                _importState.value = ImportState.Error(e.message ?: "Import failed")
            }
        }
    }

    /** Reset transient state after the user acknowledges a result/error. */
    fun consumeExportState() {
        _exportState.value = ExportState.Idle
    }

    fun consumeImportState() {
        _importState.value = ImportState.Idle
    }
}

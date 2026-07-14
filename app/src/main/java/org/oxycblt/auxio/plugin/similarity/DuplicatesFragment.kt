/*
 * Copyright (c) 2026 Auxio Project
 * DuplicatesFragment.kt is part of Auxio.
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

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentDuplicatesBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Song

/**
 * Scans the library for acoustically duplicate songs and lets the user pick
 * which copies to delete, showing side-by-side quality metadata for each.
 *
 * Reached only via Settings > Library > Find duplicates, which is only
 * visible while the Similarity Detection plugin is enabled.
 */
@AndroidEntryPoint
class DuplicatesFragment : ViewBindingFragment<FragmentDuplicatesBinding>() {
    private val duplicatesModel: DuplicatesViewModel by viewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private var pendingConsentSong: Song? = null
    private lateinit var consentLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        consentLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                result ->
                val song = pendingConsentSong
                pendingConsentSong = null
                if (result.resultCode == Activity.RESULT_OK && song != null) {
                    // The system performed the deletion after user consent.
                    duplicatesModel.onConsentDeleteFinished(song)
                }
            }
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentDuplicatesBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentDuplicatesBinding,
        savedInstanceState: Bundle?
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.duplicatesToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        val adapter =
            DuplicateGroupAdapter(
                object : DuplicateGroupAdapter.Listener {
                    override fun onDeleteRequested(song: Song, prioritized: Boolean) =
                        confirmDelete(song, prioritized)
                })
        binding.duplicatesRecycler.adapter = adapter

        collectImmediately(duplicatesModel.scanState) { state ->
            when (state) {
                is DuplicatesViewModel.ScanState.Idle -> {}
                is DuplicatesViewModel.ScanState.Scanning -> {
                    binding.duplicatesProgressContainer.isVisible = true
                    binding.duplicatesRecycler.isVisible = false
                    binding.duplicatesEmpty.isVisible = false
                    binding.duplicatesProgress.max = state.total
                    binding.duplicatesProgress.progress = state.done
                    binding.duplicatesProgressText.text =
                        getString(R.string.dup_scan_progress, state.done, state.total)
                }
                is DuplicatesViewModel.ScanState.Results -> {
                    binding.duplicatesProgressContainer.isVisible = false
                    val empty = state.groups.isEmpty()
                    binding.duplicatesEmpty.isVisible = empty
                    binding.duplicatesRecycler.isVisible = !empty
                    adapter.submitList(state.groups)
                }
            }
        }

        collectImmediately(duplicatesModel.processingLog) { log ->
            binding.duplicatesLog.text = log.joinToString("\n")
        }

        collectImmediately(duplicatesModel.deleteEvent) { event ->
            when (event) {
                null -> {}
                is DuplicatesViewModel.DeleteEvent.Success -> {
                    Snackbar.make(binding.root, R.string.dup_deleted, Snackbar.LENGTH_SHORT)
                        .show()
                    // Keep the app's library in sync with the file system.
                    musicModel.refresh()
                    duplicatesModel.consumeDeleteEvent()
                }
                is DuplicatesViewModel.DeleteEvent.NeedsConsent -> {
                    pendingConsentSong = event.song
                    consentLauncher.launch(
                        IntentSenderRequest.Builder(event.pendingIntent.intentSender).build())
                    duplicatesModel.consumeDeleteEvent()
                }
                is DuplicatesViewModel.DeleteEvent.Failure -> {
                    Snackbar.make(
                            binding.root, R.string.dup_delete_failed, Snackbar.LENGTH_LONG)
                        .show()
                    duplicatesModel.consumeDeleteEvent()
                }
            }
        }

        // Start the background fingerprint service (survives leaving the screen /
        // screen-off) unless a scan is already running or results are showing.
        // The ViewModel observes the service and groups from cache when it's done.
        if (!duplicatesModel.isScanRunning &&
            duplicatesModel.scanState.value is DuplicatesViewModel.ScanState.Idle) {
            requestThenScan()
        }
    }

    private fun requestThenScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        DuplicateScanService.start(requireContext())
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            DuplicateScanService.start(requireContext())
        }

    private fun confirmDelete(song: Song, prioritized: Boolean) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dup_delete_confirm_title)
            .setMessage(
                getString(
                    R.string.dup_delete_confirm_desc, song.name.resolve(requireContext())))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.dup_delete) { _, _ ->
                if (prioritized) {
                    // This file is inside a priority folder — require a second,
                    // explicit confirmation before deleting it.
                    confirmDeletePriority(song)
                } else {
                    duplicatesModel.delete(song)
                }
            }
            .show()
    }

    private fun confirmDeletePriority(song: Song) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dup_delete_priority_title)
            .setMessage(
                getString(
                    R.string.dup_delete_priority_desc, song.name.resolve(requireContext())))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.dup_delete_priority_confirm) { _, _ ->
                duplicatesModel.delete(song)
            }
            .show()
    }
}

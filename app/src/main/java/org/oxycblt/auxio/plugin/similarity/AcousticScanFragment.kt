/*
 * Copyright (c) 2026 Auxio Project
 * AcousticScanFragment.kt is part of Auxio.
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentAcousticScanBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately

/**
 * Observes the acoustic-scan foreground service and lets the user start/stop it.
 * The work runs in the service, so it continues if the user leaves this screen
 * or the screen turns off. Opening this page does NOT auto-start a scan; the user
 * taps the button. If a scan is already running (started earlier this session),
 * the page shows its live progress immediately.
 */
@AndroidEntryPoint
class AcousticScanFragment : ViewBindingFragment<FragmentAcousticScanBinding>() {
    private val scanModel: AcousticScanViewModel by viewModels()

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Regardless of grant result, proceed to start the scan. If denied,
            // the scan still runs; only its notification won't be shown.
            startScan()
        }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentAcousticScanBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentAcousticScanBinding,
        savedInstanceState: Bundle?
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.acousticToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.acousticRescan.setOnClickListener { requestThenScan() }

        collectImmediately(scanModel.state) { state ->
            when (state) {
                is ScanProgress.State.Idle -> {
                    binding.acousticProgressContainer.isVisible = true
                    binding.acousticProgress.isVisible = false
                    binding.acousticProgressText.text =
                        getString(R.string.set_acoustic_scan_desc)
                    binding.acousticLog.text = ""
                    binding.acousticRescan.isVisible = true
                    binding.acousticRescan.text = getString(R.string.lbl_acoustic_scan_start)
                }
                is ScanProgress.State.Running -> {
                    binding.acousticProgressContainer.isVisible = true
                    binding.acousticProgress.isVisible = true
                    binding.acousticProgress.max = state.total
                    binding.acousticProgress.progress = state.done
                    binding.acousticProgressText.text =
                        getString(R.string.fmt_acoustic_progress, state.done, state.total)
                    binding.acousticLog.text = state.log.joinToString("\n")
                    binding.acousticRescan.isVisible = false
                }
                is ScanProgress.State.Done -> {
                    binding.acousticProgressContainer.isVisible = true
                    binding.acousticProgress.isVisible = false
                    binding.acousticProgressText.text =
                        if (state.stopped)
                            getString(R.string.lbl_acoustic_stopped)
                        else
                            getString(
                                R.string.fmt_acoustic_done,
                                state.processed,
                                state.cached,
                                state.failed,
                                state.total)
                    binding.acousticLog.text = state.log.joinToString("\n")
                    binding.acousticRescan.isVisible = true
                    binding.acousticRescan.text = getString(R.string.lbl_acoustic_rescan)
                }
            }
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
        startScan()
    }

    private fun startScan() {
        AcousticScanService.start(requireContext())
    }
}

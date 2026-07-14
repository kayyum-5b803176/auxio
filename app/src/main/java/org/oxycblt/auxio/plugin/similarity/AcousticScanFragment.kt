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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentAcousticScanBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately

/**
 * Proactive whole-library acoustic seeding, with progress + per-file log
 * (mirrors the Find Duplicates screen). Grounds every song's embedding in how it
 * sounds without waiting for organic playback.
 */
@AndroidEntryPoint
class AcousticScanFragment : ViewBindingFragment<FragmentAcousticScanBinding>() {
    private val scanModel: AcousticScanViewModel by viewModels()

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
        binding.acousticRescan.setOnClickListener { scanModel.rescan() }

        collectImmediately(scanModel.scanState) { state ->
            when (state) {
                is AcousticScanViewModel.ScanState.Idle -> {}
                is AcousticScanViewModel.ScanState.Scanning -> {
                    binding.acousticProgress.isVisible = true
                    binding.acousticProgress.max = state.total
                    binding.acousticProgress.progress = state.done
                    binding.acousticStatus.text =
                        getString(R.string.fmt_acoustic_progress, state.done, state.total)
                    binding.acousticCurrent.text = state.currentFile ?: ""
                    binding.acousticRescan.isVisible = false
                }
                is AcousticScanViewModel.ScanState.Results -> {
                    binding.acousticProgress.isVisible = false
                    binding.acousticCurrent.text = ""
                    binding.acousticStatus.text =
                        getString(
                            R.string.fmt_acoustic_done, state.seeded, state.failed, state.total)
                    binding.acousticRescan.isVisible = true
                }
            }
        }

        collectImmediately(scanModel.processingLog) { log ->
            binding.acousticLog.text = log.joinToString("\n")
        }

        scanModel.scanIfNeeded()
    }
}

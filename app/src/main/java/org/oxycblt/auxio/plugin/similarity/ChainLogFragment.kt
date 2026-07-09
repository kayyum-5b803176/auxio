/*
 * Copyright (c) 2026 Auxio Project
 * ChainLogFragment.kt is part of Auxio.
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
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentChainLogBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately

/**
 * Shows the most recent Smart Chain learning events (up to 100), so the user
 * can watch the chain update as they listen. Read-only view over the in-memory
 * [ChainLog].
 */
@AndroidEntryPoint
class ChainLogFragment : ViewBindingFragment<FragmentChainLogBinding>() {
    @Inject lateinit var chainLog: ChainLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentChainLogBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentChainLogBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.chainLogToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.chainLogToolbar.inflateMenu(R.menu.chain_log)
        binding.chainLogToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_logs) {
                chainLog.clear()
                true
            } else {
                false
            }
        }

        val adapter = ChainLogAdapter()
        binding.chainLogRecycler.adapter = adapter

        collectImmediately(chainLog.entries) { entries ->
            binding.chainLogEmpty.isVisible = entries.isEmpty()
            binding.chainLogRecycler.isVisible = entries.isNotEmpty()
            adapter.submitList(entries)
        }
    }
}

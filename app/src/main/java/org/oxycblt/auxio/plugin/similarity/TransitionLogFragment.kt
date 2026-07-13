/*
 * Copyright (c) 2026 Auxio Project
 * TransitionLogFragment.kt is part of Auxio.
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
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentTransitionLogBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately

/**
 * Shows the directed transition graph FILTERED to the currently-playing song:
 * which songs the user tends to play (or skip) after it, strongest-first.
 */
@AndroidEntryPoint
class TransitionLogFragment : ViewBindingFragment<FragmentTransitionLogBinding>() {
    private val transitionModel: TransitionLogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentTransitionLogBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentTransitionLogBinding,
        savedInstanceState: Bundle?
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.transitionLogToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        val adapter = TransitionLogAdapter()
        binding.transitionLogRecycler.adapter = adapter

        collectImmediately(transitionModel.current) { song ->
            // Title reflects the song being filtered on.
            binding.transitionLogToolbar.subtitle =
                song?.name?.resolve(requireContext())
        }

        collectImmediately(transitionModel.rows) { rows ->
            binding.transitionLogEmpty.isVisible = rows.isEmpty()
            binding.transitionLogRecycler.isVisible = rows.isNotEmpty()
            adapter.submitList(rows)
        }
    }
}

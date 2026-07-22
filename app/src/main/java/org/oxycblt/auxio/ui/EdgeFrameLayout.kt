/*
 * Copyright (c) 2021 Auxio Project
 * EdgeFrameLayout.kt is part of Auxio.
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
 
package org.oxycblt.auxio.ui

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.core.view.updatePadding
import org.oxycblt.auxio.util.systemBarInsetsCompat

/**
 * A [FrameLayout] that automatically applies bottom insets.
 *
 * Insets are seeded synchronously in [onAttachedToWindow] from whatever the window already knows
 * (via [rootWindowInsets]), in addition to being applied reactively in [onApplyWindowInsets].
 * Relying on [onApplyWindowInsets] alone left a window - sometimes a full frame or more,
 * especially for views created lazily (e.g. a ViewPager2 page) - where this view had zero bottom
 * padding before the real dispatch arrived. That's invisible for most content, but for anything
 * vertically centered inside this layout (e.g. an empty-state placeholder), it meant the content
 * rendered lower than its final position for a moment, then visibly jumped upward once the real
 * inset value landed and the content recentered around the now-smaller available height.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class EdgeFrameLayout
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0) :
    FrameLayout(context, attrs, defStyleAttr) {
    init {
        clipToPadding = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Seed padding immediately from whatever insets the window already knows, rather than
        // waiting for onApplyWindowInsets to be dispatched to this specific view - see the class
        // doc for why that matters. Falls through to the normal (slightly delayed) path below if
        // the window doesn't have any insets to report yet, which is no worse than before.
        rootWindowInsets?.let { onApplyWindowInsets(it) }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
        return insets
    }
}

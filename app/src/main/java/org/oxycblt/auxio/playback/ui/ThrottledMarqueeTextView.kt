/*
 * Copyright (c) 2026 Auxio Project
 * ThrottledMarqueeTextView.kt is part of Auxio.
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

package org.oxycblt.auxio.playback.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A drop-in replacement for TextView's built-in marquee that renders at a
 * THROTTLED frame rate instead of every display vsync.
 *
 * Why: Perfetto traces showed that while the framework marquee scrolls, the
 * app produces a frame on EVERY vsync (60-120/s) for the whole scroll
 * duration, and each frame costs several ms of main-thread + RenderThread
 * CPU — the marquee alone accounted for the bulk of ~99% foreground CPU
 * during playback. The framework marquee's frame rate is not configurable.
 *
 * This view scrolls the text itself via [scrollTo] on a shared ~25fps ticker:
 *  - ~2.5-5x fewer frames than the framework marquee on 60-120Hz displays.
 *  - All instances step on ONE shared tick, so three scrolling labels
 *    coalesce into one frame instead of three independent invalidations.
 *  - A finite repeat count, then the view goes fully idle (zero redraws)
 *    with a trailing ellipsis until the next song/panel event.
 *
 * API-compatible with how the playback fragments drove the old marquee:
 * scrolling is started/stopped by [setSelected], exactly like the framework
 * marquee, so the existing isSelected-based gating (playing + visible) keeps
 * working unchanged. Requires android:singleLine="true" (set by the styles).
 */
class ThrottledMarqueeTextView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    AppCompatTextView(context, attrs, defStyleAttr) {

    private enum class Phase {
        START_HOLD,
        SCROLLING,
        END_HOLD
    }

    private var active = false
    private var scrollModeEnabled = false
    private var phase = Phase.START_HOLD
    private var holdTicksLeft = 0
    private var repeatsLeft = 0
    private var offsetPx = 0f
    private var scrollRangePx = 0f
    private var rtl = false
    private val stepPx = SPEED_DP_PER_SEC * context.resources.displayMetrics.density * (FRAME_MS / 1000f)

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        if (selected) {
            maybeStart()
        } else {
            stop()
        }
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        // New text: reset and (if still selected) restart after re-layout.
        stop()
        if (isSelected) {
            post { maybeStart() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isSelected && !active) {
            maybeStart()
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun maybeStart() {
        if (active || !isAttachedToWindow || width == 0) return
        // Scrolling needs the FULL text laid out on one unbounded line:
        // horizontallyScrolling=true (some styles only set maxLines=1, which
        // does not enable it) and no ellipsis. The idle state is the reverse.
        // Both changes rebuild the layout, so apply them and re-enter once
        // the new layout has settled.
        if (!scrollModeEnabled || ellipsize != null) {
            scrollModeEnabled = true
            setHorizontallyScrolling(true)
            ellipsize = null
            post { maybeStart() }
            return
        }
        val layout = layout ?: return
        val avail = (width - compoundPaddingLeft - compoundPaddingRight).toFloat()
        scrollRangePx = layout.getLineWidth(0) - avail + EDGE_GAP_PX
        if (scrollRangePx <= EDGE_GAP_PX) {
            // Fits — nothing to scroll; restore the (unused) ellipsis and idle.
            finishIdle()
            return
        }
        rtl = layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
        active = true
        phase = Phase.START_HOLD
        holdTicksLeft = (START_HOLD_MS / FRAME_MS).toInt()
        repeatsLeft = REPEATS
        offsetPx = 0f
        applyScroll()
        Ticker.add(this)
    }

    private fun stop() {
        if (active) {
            Ticker.remove(this)
            active = false
        }
        offsetPx = 0f
        scrollTo(0, 0)
        if (scrollModeEnabled) {
            scrollModeEnabled = false
            setHorizontallyScrolling(false)
            ellipsize = TextUtils.TruncateAt.END
        } else if (ellipsize == null) {
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun finishIdle() {
        // Natural completion: rest at position 0 with an ellipsis, zero
        // further redraws until the next song / selection event.
        stop()
    }

    /** One shared-ticker step. Returns false when this view is done. */
    internal fun tick(): Boolean {
        if (!active) return false
        when (phase) {
            Phase.START_HOLD -> {
                if (--holdTicksLeft <= 0) phase = Phase.SCROLLING
            }
            Phase.SCROLLING -> {
                offsetPx += stepPx
                if (offsetPx >= scrollRangePx) {
                    offsetPx = scrollRangePx
                    phase = Phase.END_HOLD
                    holdTicksLeft = (END_HOLD_MS / FRAME_MS).toInt()
                }
                applyScroll()
            }
            Phase.END_HOLD -> {
                if (--holdTicksLeft <= 0) {
                    repeatsLeft--
                    if (repeatsLeft <= 0) {
                        finishIdle()
                        return false
                    }
                    offsetPx = 0f
                    applyScroll()
                    phase = Phase.START_HOLD
                    holdTicksLeft = (START_HOLD_MS / FRAME_MS).toInt()
                }
            }
        }
        return active
    }

    private fun applyScroll() {
        val x = (if (rtl) -offsetPx else offsetPx).toInt()
        if (scrollX != x) {
            scrollTo(x, 0)
        }
    }

    /**
     * One shared timer for every scrolling label on screen. All active
     * marquees advance on the same tick, so simultaneous labels (e.g. the
     * panel's song/artist/album) coalesce into a single frame per step
     * instead of three unaligned invalidations.
     */
    private object Ticker {
        private val handler = Handler(Looper.getMainLooper())
        private val views = ArrayList<ThrottledMarqueeTextView>(4)
        private var running = false
        private val step =
            object : Runnable {
                override fun run() {
                    var i = 0
                    while (i < views.size) {
                        if (!views[i].tick()) {
                            views.removeAt(i)
                        } else {
                            i++
                        }
                    }
                    if (views.isEmpty()) {
                        running = false
                    } else {
                        handler.postDelayed(this, FRAME_MS)
                    }
                }
            }

        fun add(view: ThrottledMarqueeTextView) {
            if (view !in views) views.add(view)
            if (!running) {
                running = true
                handler.postDelayed(step, FRAME_MS)
            }
        }

        fun remove(view: ThrottledMarqueeTextView) {
            views.remove(view)
            if (views.isEmpty() && running) {
                handler.removeCallbacks(step)
                running = false
            }
        }
    }

    private companion object {
        // ~25fps: visually smooth for a slow text crawl, but 2.5-5x fewer
        // frames than the framework marquee (which renders at display vsync).
        const val FRAME_MS = 40L
        // Crawl speed. The framework default is ~30dp/s; keep it similar.
        const val SPEED_DP_PER_SEC = 32f
        // Full scroll cycles per activation, then fully idle.
        const val REPEATS = 3
        const val START_HOLD_MS = 1200L
        const val END_HOLD_MS = 900L
        // Extra px scrolled past the end so the last glyph fully clears.
        const val EDGE_GAP_PX = 8f
    }
}

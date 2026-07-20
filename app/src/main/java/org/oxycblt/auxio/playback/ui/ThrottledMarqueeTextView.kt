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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.TextUtils
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.ceil
import kotlin.math.min

/**
 * A cheap, smooth replacement for TextView's built-in marquee.
 *
 * WHY (from Perfetto traces of the stock marquee): the framework marquee
 * invalidates the TextView on EVERY display vsync, and each of those frames
 * re-records the text draw (glyph runs) on the main thread and re-renders it
 * on the RenderThread — ~11ms of CPU per frame on the profiled device, i.e.
 * ~80% of a core for as long as anything scrolls. A first attempt that merely
 * throttled scrollTo() to 25fps was still expensive per frame AND looked
 * juddery, because Handler-timed ticks aren't vsync-aligned.
 *
 * HOW this version fixes both:
 *  - The full (un-ellipsized) text line is rendered ONCE into a bitmap when a
 *    scroll cycle starts.
 *  - A vsync-driven [ValueAnimator] then animates a plain float offset at the
 *    display's native rate, and onDraw is a single drawBitmap() — the display
 *    list re-record is one op instead of a full text draw. Per-frame cost on
 *    both threads collapses to near-zero, while motion is perfectly smooth
 *    (linear, vsync-aligned).
 *  - After [REPEATS] full passes the view goes completely idle (zero redraws,
 *    END ellipsis) and the bitmap is released, until the next song/selection.
 *
 * Driven by [setSelected] exactly like the framework marquee, so the existing
 * playing+visibility gating in the playback fragments works unchanged.
 */
class ThrottledMarqueeTextView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    AppCompatTextView(context, attrs, defStyleAttr) {

    private var active = false
    private var scrollModeEnabled = false
    // Whether THIS text has already done its reveal scroll. Prevents a
    // play/pause toggle or a sheet visibility change on the SAME song from
    // re-triggering the scroll (which would restart the per-frame redraw window
    // every time playback is paused/resumed). Reset when the text changes.
    private var revealedForCurrentText = false
    private var rtl = false
    private var offsetPx = 0f
    private var scrollRangePx = 0f
    private var repeatsLeft = 0
    private var lineBitmap: Bitmap? = null
    private var animator: ValueAnimator? = null
    private val speedPxPerSec = SPEED_DP_PER_SEC * context.resources.displayMetrics.density

    init {
        if (!SCROLLING_ENABLED) {
            // Fully static: plain single-line TextView with an end ellipsis, no
            // horizontal scrolling, no animator, no per-frame invalidation. This
            // is the zero-CPU marquee mode — long titles are truncated with "…".
            ellipsize = TextUtils.TruncateAt.END
            setHorizontallyScrolling(false)
        }
    }

    override fun setSelected(selected: Boolean) {
        // When scrolling is disabled, selection is a no-op for marquee purposes
        // (still forward to super for normal selection semantics, but never
        // start the scroll animator).
        super.setSelected(selected)
        if (!SCROLLING_ENABLED) return
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
        if (!SCROLLING_ENABLED) return
        // New text (new song): allow one fresh reveal.
        revealedForCurrentText = false
        stop()
        if (isSelected) {
            post { maybeStart() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!SCROLLING_ENABLED) return
        if (active) {
            // Geometry changed under a running scroll; restart cleanly.
            stop()
        }
        if (isSelected) {
            post { maybeStart() }
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = lineBitmap
        if (!active || bitmap == null) {
            super.onDraw(canvas)
            return
        }
        // One textured blit per frame; content was rendered once at start.
        val availW = width - compoundPaddingLeft - compoundPaddingRight
        val x =
            if (rtl) {
                // RTL: right edge anchored, reveal leftwards.
                compoundPaddingLeft + (availW - bitmap.width) + offsetPx
            } else {
                compoundPaddingLeft - offsetPx
            }
        val save = canvas.save()
        canvas.clipRect(
            compoundPaddingLeft,
            0,
            width - compoundPaddingRight,
            height)
        canvas.drawBitmap(bitmap, x, extendedPaddingTop.toFloat(), null)
        canvas.restoreToCount(save)
    }

    private fun maybeStart() {
        // In continuous-loop mode (REPEATS<=0) the reveal-once guard must not
        // block re-arming; it only applies to finite reveal mode.
        val revealGuard = REPEATS > 0 && revealedForCurrentText
        if (active || revealGuard || !isAttachedToWindow || width == 0) return
        // Measuring the overflow needs the FULL text laid out on one unbounded
        // line: horizontallyScrolling=true (some styles only set maxLines=1,
        // which doesn't enable it) and no ellipsis. Both rebuild the layout,
        // so apply and re-enter once it settles.
        if (!scrollModeEnabled || ellipsize != null) {
            scrollModeEnabled = true
            setHorizontallyScrolling(true)
            ellipsize = null
            post { maybeStart() }
            return
        }
        val layout = layout ?: return
        if (layout.lineCount == 0) {
            finishIdle()
            return
        }
        val availW = (width - compoundPaddingLeft - compoundPaddingRight).toFloat()
        val lineW = layout.getLineWidth(0)
        scrollRangePx = lineW - availW + EDGE_GAP_PX
        if (scrollRangePx <= EDGE_GAP_PX || availW <= 0f) {
            // Fits — nothing to scroll; count as revealed so we don't retry.
            revealedForCurrentText = true
            finishIdle()
            return
        }
        rtl = layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
        val bitmap = renderLine(layout, lineW) ?: run {
            finishIdle()
            return
        }
        lineBitmap = bitmap
        // If the bitmap had to be capped (GPU texture limit), don't scroll
        // past what was actually rendered.
        scrollRangePx = min(scrollRangePx, bitmap.width - availW + EDGE_GAP_PX)
        active = true
        revealedForCurrentText = true
        repeatsLeft = REPEATS
        offsetPx = 0f
        invalidate()
        startPass()
    }

    /** Render the single full-width text line into an offscreen bitmap once. */
    private fun renderLine(layout: Layout, lineW: Float): Bitmap? {
        val w = min(ceil(lineW).toInt() + 1, MAX_BITMAP_WIDTH_PX)
        val h = layout.height.coerceAtLeast(1)
        if (w <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(bitmap)
            // TextView normally sets these on the shared TextPaint inside its
            // own onDraw; replicate so the layout draws with the right color
            // and state (Layout.draw uses the paint it was built with).
            paint.color = currentTextColor
            paint.drawableState = drawableState
            layout.draw(canvas)
            bitmap
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun startPass() {
        animator?.cancel()
        animator =
            ValueAnimator.ofFloat(0f, scrollRangePx).apply {
                interpolator = LinearInterpolator()
                duration = (scrollRangePx / speedPxPerSec * 1000f).toLong().coerceAtLeast(1L)
                startDelay = START_HOLD_MS
                addUpdateListener {
                    offsetPx = it.animatedValue as Float
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var canceled = false

                        override fun onAnimationCancel(animation: Animator) {
                            canceled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (canceled || !active) return
                            // REPEATS <= 0 means loop forever (continuous
                            // marquee): always schedule another pass. Otherwise
                            // count down and rest after the last pass.
                            val loopForever = REPEATS <= 0
                            if (!loopForever) repeatsLeft--
                            if (loopForever || repeatsLeft > 0) {
                                // Hold at the end, then run the next pass.
                                postDelayed(
                                    {
                                        if (active) {
                                            offsetPx = 0f
                                            invalidate()
                                            startPass()
                                        }
                                    },
                                    END_HOLD_MS)
                            } else {
                                postDelayed({ if (active) finishIdle() }, END_HOLD_MS)
                            }
                        }
                    })
                start()
            }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        active = false
        offsetPx = 0f
        lineBitmap?.recycle()
        lineBitmap = null
        if (scrollModeEnabled) {
            scrollModeEnabled = false
            setHorizontallyScrolling(false)
            ellipsize = TextUtils.TruncateAt.END
        } else if (ellipsize == null) {
            ellipsize = TextUtils.TruncateAt.END
        }
        if (isAttachedToWindow) {
            invalidate()
        }
    }

    /** Natural completion: rest with an ellipsis, zero further redraws. */
    private fun finishIdle() = stop()

    private companion object {
        // Marquee scrolling inherently redraws every frame WHILE it scrolls
        // (smooth motion = new pixels each vsync), which on the player page was
        // the ~15% "player page only" CPU bump. We can't make scrolling itself
        // free, so we bound it: scroll the full title ONCE, a bit faster, with
        // short holds, then go completely idle (zero redraws, END ellipsis)
        // until the next song. This reveals the whole name after a track change
        // but keeps the per-frame-redraw window to a few seconds instead of
        // continuous. (Set REPEATS higher / SPEED lower to trade CPU for more
        // scrolling; set REPEATS=0-equivalent by using a static ellipsis view.)
        // MASTER SWITCH. false = static end-ellipsis title, zero per-frame cost.
        const val SCROLLING_ENABLED = false

        const val SPEED_DP_PER_SEC = 32f
        // Number of scroll passes before resting. 0 or negative = loop FOREVER
        // (continuous marquee).
        const val REPEATS = 0
        const val START_HOLD_MS = 1500L
        const val END_HOLD_MS = 1000L
        // Extra px scrolled past the end so the last glyph fully clears.
        const val EDGE_GAP_PX = 8f
        // Stay under common GPU max texture sizes; absurdly long titles simply
        // scroll through the first ~4k px.
        const val MAX_BITMAP_WIDTH_PX = 4096
    }
}

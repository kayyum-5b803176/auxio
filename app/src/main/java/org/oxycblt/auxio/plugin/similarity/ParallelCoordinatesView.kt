/*
 * Copyright (c) 2026 Auxio Project
 * ParallelCoordinatesView.kt is part of Auxio.
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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

/**
 * Renders songs as a parallel-coordinates plot across all 24 vector dimensions —
 * the real high-dimensional data, no reduction. Each song is a polyline with a
 * dot at every axis; the x position of each vertex is its axis, the y is that
 * axis's value, auto-scaled per axis from the data actually being shown (the
 * stored vectors are unit-normalized, so a fixed -1..1 range would squash every
 * line into an unreadable clump).
 *
 * Every song gets a stable color (by [ZoneVisualizerViewModel.Plot.colorIndex])
 * shared with its legend row. Tapping a line (or its legend row, via
 * [setFocused]) highlights that song and dims every other line — a filter.
 */
class ParallelCoordinatesView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    View(context, attrs, defStyle) {

    private var plots: List<ZoneVisualizerViewModel.Plot> = emptyList()
    private var currentKey: String? = null
    private var dimensions: Int = 24
    private var focusedKey: String? = null

    // Per-axis observed min/max across the current plots, for auto-scaling.
    private var axisMin: FloatArray = FloatArray(0)
    private var axisMax: FloatArray = FloatArray(0)

    /** Called when the user taps near a line; passes that song's key. */
    var onLineTapped: ((String) -> Unit)? = null

    // View transform (pan + zoom), applied on top of the base layout.
    private var scaleFactor = 1f
    private var panX = 0f
    private var panY = 0f

    private var colorAxis = Color.parseColor("#40808080")

    fun setThemeColors(axis: Int) {
        colorAxis = axis
        invalidate()
    }

    private val axisPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
    private val linePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val dotPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun submit(model: ZoneVisualizerViewModel.Model) {
        plots = model.plots
        currentKey = model.currentKey
        dimensions = model.dimensions.coerceAtLeast(1)
        computeAxisRanges()
        invalidate()
    }

    fun setFocused(key: String?) {
        focusedKey = key
        invalidate()
    }

    fun resetView() {
        scaleFactor = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    /** Per-axis min/max across all plotted vectors, so lines actually spread out. */
    private fun computeAxisRanges() {
        val d = dimensions
        val min = FloatArray(d) { Float.MAX_VALUE }
        val max = FloatArray(d) { -Float.MAX_VALUE }
        for (plot in plots) {
            val n = minOf(plot.vector.size, d)
            for (i in 0 until n) {
                val v = plot.vector[i]
                if (v < min[i]) min[i] = v
                if (v > max[i]) max[i] = v
            }
        }
        // Guard against a degenerate (flat) axis with no observed spread.
        for (i in 0 until d) {
            if (min[i] > max[i]) {
                min[i] = -0.3f
                max[i] = 0.3f
            } else if (max[i] - min[i] < 1e-4f) {
                min[i] -= 0.05f
                max[i] += 0.05f
            }
        }
        axisMin = min
        axisMax = max
    }

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 8f)
                    invalidate()
                    return true
                }
            })

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    panX += dx
                    panY += dy
                    if (abs(event.x - downX) > TAP_SLOP || abs(event.y - downY) > TAP_SLOP) {
                        moved = true
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) handleTap(event.x, event.y)
            }
        }
        return true
    }

    /** Find the nearest line to the tap and report it. */
    private fun handleTap(px: Float, py: Float) {
        if (plots.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        var bestKey: String? = null
        var bestDist = Float.MAX_VALUE
        for (plot in plots) {
            val d = distanceToPolyline(plot.vector, px, py, w, h)
            if (d < bestDist) {
                bestDist = d
                bestKey = plot.key
            }
        }
        if (bestKey != null && bestDist <= TAP_HIT_DISTANCE) {
            onLineTapped?.invoke(bestKey)
        }
    }

    private fun xForAxis(i: Int, w: Float): Float {
        val usable = w - 2 * MARGIN_X
        val step = if (dimensions > 1) usable / (dimensions - 1) else 0f
        return (MARGIN_X + i * step) * scaleFactor + panX
    }

    private fun yForValue(v: Float, i: Int, h: Float): Float {
        val usable = h - 2 * MARGIN_Y
        val lo = axisMin.getOrElse(i) { -0.3f }
        val hi = axisMax.getOrElse(i) { 0.3f }
        val norm = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return (MARGIN_Y + (1f - norm) * usable) * scaleFactor + panY
    }

    private fun distanceToPolyline(
        vec: FloatArray,
        px: Float,
        py: Float,
        w: Float,
        h: Float
    ): Float {
        var best = Float.MAX_VALUE
        val n = minOf(vec.size, dimensions)
        for (i in 0 until n - 1) {
            val x1 = xForAxis(i, w)
            val y1 = yForValue(vec[i], i, h)
            val x2 = xForAxis(i + 1, w)
            val y2 = yForValue(vec[i + 1], i + 1, h)
            best = minOf(best, pointToSegment(px, py, x1, y1, x2, y2))
        }
        return best
    }

    private fun pointToSegment(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0f && dy == 0f) return kotlin.math.hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val projX = x1 + t * dx
        val projY = y1 + t * dy
        return kotlin.math.hypot(px - projX, py - projY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (plots.isEmpty()) return

        // Axes.
        axisPaint.color = colorAxis
        for (i in 0 until dimensions) {
            val x = xForAxis(i, w)
            canvas.drawLine(
                x, yForValue(axisMax[i], i, h), x, yForValue(axisMin[i], i, h), axisPaint)
        }

        val focused = focusedKey
        // Draw order: everyone else first (dimmed if something is focused),
        // then the focused/current lines on top so they're never occluded.
        val onTop = ArrayList<ZoneVisualizerViewModel.Plot>()
        for (plot in plots) {
            val isFocused = plot.key == focused
            val isCurrent = plot.key == currentKey
            if (isFocused || isCurrent) {
                onTop.add(plot)
                continue
            }
            val base = paletteColor(plot.colorIndex)
            val alpha = if (focused != null) DIM_ALPHA else NORMAL_ALPHA
            drawPlot(canvas, plot, w, h, ColorUtils.setAlphaComponent(base, alpha), 2f, dotR = 3f)
        }
        for (plot in onTop) {
            val base = paletteColor(plot.colorIndex)
            val isCurrent = plot.key == currentKey
            val isFocused = plot.key == focused
            // A line that's "current" but something ELSE is focused still dims,
            // so the current song doesn't defeat the filter.
            val dimmed = focused != null && !isFocused && !isCurrent
            val alpha = if (dimmed) DIM_ALPHA else NORMAL_ALPHA
            val strokeWidth = if (isFocused || isCurrent) 4f else 2f
            val dotR = if (isFocused || isCurrent) 5f else 3f
            drawPlot(canvas, plot, w, h, ColorUtils.setAlphaComponent(base, alpha), strokeWidth, dotR)
        }
    }

    private fun drawPlot(
        canvas: Canvas,
        plot: ZoneVisualizerViewModel.Plot,
        w: Float,
        h: Float,
        color: Int,
        strokeWidth: Float,
        dotR: Float
    ) {
        val vec = plot.vector
        val n = minOf(vec.size, dimensions)
        if (n < 1) return
        linePaint.color = color
        linePaint.strokeWidth = strokeWidth
        dotPaint.color = color

        var prevX = xForAxis(0, w)
        var prevY = yForValue(vec[0], 0, h)
        canvas.drawCircle(prevX, prevY, dotR, dotPaint)
        for (i in 1 until n) {
            val x = xForAxis(i, w)
            val y = yForValue(vec[i], i, h)
            canvas.drawLine(prevX, prevY, x, y, linePaint)
            canvas.drawCircle(x, y, dotR, dotPaint)
            prevX = x
            prevY = y
        }
    }

    companion object {
        /**
         * A fixed palette of visually distinct colors, cycled by index. Shared
         * with the legend adapter so a line and its legend row always match.
         */
        fun paletteColor(index: Int): Int =
            PALETTE[((index % PALETTE.size) + PALETTE.size) % PALETTE.size]

        private val PALETTE =
            intArrayOf(
                Color.parseColor("#E24B4A"),
                Color.parseColor("#378ADD"),
                Color.parseColor("#1D9E75"),
                Color.parseColor("#EF9F27"),
                Color.parseColor("#7F77DD"),
                Color.parseColor("#D4537E"),
                Color.parseColor("#5DCAA5"),
                Color.parseColor("#D85A30"),
                Color.parseColor("#85B7EB"),
                Color.parseColor("#97C459"),
                Color.parseColor("#F0997B"),
                Color.parseColor("#AFA9EC"),
                Color.parseColor("#FAC775"),
                Color.parseColor("#ED93B1"),
                Color.parseColor("#993C1D"),
                Color.parseColor("#0C447C"))

        private const val MARGIN_X = 48f
        private const val MARGIN_Y = 64f
        private const val TAP_SLOP = 20f
        private const val TAP_HIT_DISTANCE = 48f
        private const val DIM_ALPHA = 40
        private const val NORMAL_ALPHA = 220
    }
}

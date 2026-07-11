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
import kotlin.math.abs

/**
 * Renders songs as a parallel-coordinates plot across all 24 vector dimensions —
 * the real high-dimensional data, no reduction. Each song is a polyline; the x
 * position of each vertex is its axis, the y is that axis's value. Supports
 * pinch-zoom, pan, per-line highlight (current song, search match, selection),
 * and tap-to-select for the distance readout.
 */
class ParallelCoordinatesView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    View(context, attrs, defStyle) {

    private var plots: List<ZoneVisualizerViewModel.Plot> = emptyList()
    private var currentKey: String? = null
    private var dimensions: Int = 24
    private var searchKey: String? = null
    private var selectedKeys: Set<String> = emptySet()

    /** Called when the user taps near a line; passes that song's key. */
    var onLineTapped: ((String) -> Unit)? = null

    // View transform (pan + zoom), applied on top of the base layout.
    private var scaleFactor = 1f
    private var panX = 0f
    private var panY = 0f

    // Colors resolved from theme at init; safe defaults here.
    private var colorAxis = Color.parseColor("#40808080")
    private var colorUntagged = Color.parseColor("#33888888")
    private var colorTagged = Color.parseColor("#886750A4")
    private var colorCurrent = Color.parseColor("#FF6750A4")
    private var colorSearch = Color.parseColor("#FFB3261E")
    private var colorSelected = Color.parseColor("#FF1D9E75")

    fun setThemeColors(
        axis: Int,
        untagged: Int,
        tagged: Int,
        current: Int,
        search: Int,
        selected: Int
    ) {
        colorAxis = axis
        colorUntagged = untagged
        colorTagged = tagged
        colorCurrent = current
        colorSearch = search
        colorSelected = selected
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
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    fun submit(model: ZoneVisualizerViewModel.Model) {
        plots = model.plots
        currentKey = model.currentKey
        dimensions = model.dimensions.coerceAtLeast(1)
        invalidate()
    }

    fun setSearch(key: String?) {
        searchKey = key
        invalidate()
    }

    fun setSelection(keys: Set<String>) {
        selectedKeys = keys
        invalidate()
    }

    fun resetView() {
        scaleFactor = 1f
        panX = 0f
        panY = 0f
        invalidate()
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

    private fun yForValue(v: Float, h: Float): Float {
        // Vectors are normalized (roughly -1..1); map to [top, bottom].
        val usable = h - 2 * MARGIN_Y
        val norm = ((v + 1f) / 2f).coerceIn(0f, 1f)
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
            val y1 = yForValue(vec[i], h)
            val x2 = xForAxis(i + 1, w)
            val y2 = yForValue(vec[i + 1], h)
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
            canvas.drawLine(x, yForValue(1f, h), x, yForValue(-1f, h), axisPaint)
        }

        // Draw order: untagged first (faint), tagged, then highlighted on top.
        val highlighted = ArrayList<ZoneVisualizerViewModel.Plot>()
        for (plot in plots) {
            val isCurrent = plot.key == currentKey
            val isSearch = plot.key == searchKey
            val isSelected = plot.key in selectedKeys
            if (isCurrent || isSearch || isSelected) {
                highlighted.add(plot)
                continue
            }
            linePaint.color = if (plot.tagged) colorTagged else colorUntagged
            linePaint.strokeWidth = 2f
            drawPlot(canvas, plot, w, h)
        }
        // Highlighted lines drawn last (on top), thicker.
        for (plot in highlighted) {
            linePaint.color =
                when {
                    plot.key == currentKey -> colorCurrent
                    plot.key == searchKey -> colorSearch
                    else -> colorSelected
                }
            linePaint.strokeWidth = 4f
            drawPlot(canvas, plot, w, h)
        }
    }

    private fun drawPlot(
        canvas: Canvas,
        plot: ZoneVisualizerViewModel.Plot,
        w: Float,
        h: Float
    ) {
        val vec = plot.vector
        val n = minOf(vec.size, dimensions)
        if (n < 2) return
        var prevX = xForAxis(0, w)
        var prevY = yForValue(vec[0], h)
        for (i in 1 until n) {
            val x = xForAxis(i, w)
            val y = yForValue(vec[i], h)
            canvas.drawLine(prevX, prevY, x, y, linePaint)
            prevX = x
            prevY = y
        }
    }

    private companion object {
        const val MARGIN_X = 48f
        const val MARGIN_Y = 64f
        const val TAP_SLOP = 20f
        const val TAP_HIT_DISTANCE = 40f
    }
}

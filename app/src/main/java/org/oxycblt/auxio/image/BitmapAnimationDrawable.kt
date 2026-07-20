/*
 * Copyright (c) 2026 Auxio Project
 * BitmapAnimationDrawable.kt is part of Auxio.
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

package org.oxycblt.auxio.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * Utilities for making frame-by-frame [AnimationDrawable]s cheap to run.
 *
 * The playing-indicator (ic_playing_indicator_24) is a 30-frame animation where
 * every frame is a full VectorDrawable. Played back directly, Android
 * RE-RASTERIZES a vector path from scratch on every frame (20fps, forever while
 * music plays) and invalidates the host view each time — which, on the home /
 * queue lists and the player page, forces a continuous full-surface redraw.
 * Profiling + user testing showed this "bar animation" was a large chunk of the
 * foreground-only CPU cost (it disappears when backgrounded or paused, and does
 * not exist in players without such an animation).
 *
 * The waste is the repeated vector rasterization. This rasterizes each frame
 * ONCE into a Bitmap at the target pixel size and returns an equivalent
 * AnimationDrawable made of BitmapDrawables, so each frame swap is a cheap
 * pre-decoded blit instead of a fresh path rasterization.
 */
object BitmapAnimationDrawable {
    /**
     * Convert a frame-by-frame [source] AnimationDrawable into one backed by
     * pre-rasterized bitmaps at [sizePx] x [sizePx]. Falls back to returning
     * [source] unchanged if anything goes wrong (e.g. OOM).
     */
    fun rasterize(context: Context, source: AnimationDrawable, sizePx: Int): AnimationDrawable {
        if (sizePx <= 0) return source
        return try {
            val out = AnimationDrawable()
            out.isOneShot = source.isOneShot
            val res = context.resources
            for (i in 0 until source.numberOfFrames) {
                val frame = source.getFrame(i)
                val duration = source.getDuration(i)
                val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                frame.setBounds(0, 0, sizePx, sizePx)
                frame.draw(canvas)
                out.addFrame(BitmapDrawable(res, bitmap), duration)
            }
            out
        } catch (e: OutOfMemoryError) {
            source
        } catch (e: Exception) {
            source
        }
    }

    /** Convenience overload using a dp size resolved against display density. */
    fun rasterizeDp(context: Context, source: AnimationDrawable, sizeDp: Int): AnimationDrawable {
        val px = (sizeDp * context.resources.displayMetrics.density).roundToInt()
        return rasterize(context, source, px)
    }

    /** Draw any [Drawable] into a fresh [Bitmap] of the given pixel size. */
    fun toBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }
}

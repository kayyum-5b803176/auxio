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
import androidx.annotation.DrawableRes
import kotlin.math.roundToInt
import org.oxycblt.auxio.util.getDrawableCompat

/**
 * Utilities for making frame-by-frame [AnimationDrawable]s cheap to run AND cheap to construct.
 *
 * The playing-indicator (ic_playing_indicator_24) is a 30-frame animation where every frame is a
 * full VectorDrawable. Played back directly, Android re-rasterizes a vector path from scratch on
 * every frame (20fps, forever while music plays) and invalidates the host view each time — which,
 * on the home / queue lists and the player page, forces a continuous full-surface redraw.
 * Profiling + user testing showed this "bar animation" was a large chunk of the foreground-only
 * CPU cost (it disappears when backgrounded or paused, and does not exist in players without such
 * an animation).
 *
 * The first fix rasterized each frame ONCE into a Bitmap and returned an equivalent
 * AnimationDrawable made of BitmapDrawables, so each frame swap is a cheap pre-decoded blit
 * instead of a fresh path rasterization while playing. BUT that rasterization ran fresh every
 * time it was called — and it's called once per [org.oxycblt.auxio.image.CoverView] instance,
 * i.e. once per list-item ViewHolder (every song/album/artist/genre/playlist row, plus the
 * playback bar/panel/queue covers). Rasterizing 30 frames at 96dp allocates ~8MB of bitmap memory
 * and redraws 30 vector paths — real main-thread work — even though the output is byte-identical
 * every time for a given (drawable, size), and even though at most ONE row is ever actually
 * "playing" at once. Paying that cost per-instance is exactly what stalls the first screenful of
 * newly-created ViewHolders (until RecyclerView's view pool has enough recycled views to stop
 * constructing new ones), and stalls in one large burst whenever a whole list is torn down and
 * rebuilt from scratch (e.g. returning to Home via the back button, where the entire view/pool is
 * destroyed and every visible row's ViewHolder — and its CoverView — is created fresh again).
 *
 * Now the rasterized Bitmaps are cached per (drawable, size) and computed only on first use;
 * every CoverView after that just wraps the SAME already-rasterized Bitmaps in a fresh (cheap)
 * BitmapDrawable/AnimationDrawable. This is safe because the Bitmaps are never mutated after
 * creation — only the "which frame is showing / is it running" playback state needs to stay
 * per-instance, and that already lives on the AnimationDrawable wrapper, not the pixel data.
 */
object BitmapAnimationDrawable {
    private data class CacheKey(@DrawableRes val drawableRes: Int, val sizePx: Int)

    /** A rasterized frame: bitmap pixel data (shareable) + its display duration. */
    private class RasterizedFrame(val bitmap: Bitmap, val durationMs: Int)

    /** Null/empty entries record a prior failure (e.g. OOM) so we don't retry every call. */
    private class RasterizedFrames(val isOneShot: Boolean, val frames: List<RasterizedFrame>)

    private val cache = mutableMapOf<CacheKey, RasterizedFrames>()

    /**
     * Get a cheap-to-construct [AnimationDrawable] backed by pre-rasterized bitmap frames of
     * [drawableRes] (which must resolve to an [AnimationDrawable]) at [sizePx] x [sizePx].
     *
     * The actual rasterization only happens once per (drawable, size) combination, the first
     * time it's requested; every later call - regardless of caller/instance - reuses the cached
     * bitmaps and only allocates lightweight wrapper objects. Falls back to the original
     * vector-based drawable if rasterization isn't possible (e.g. OOM) or [sizePx] is invalid.
     */
    @Synchronized
    fun rasterize(
        context: Context,
        @DrawableRes drawableRes: Int,
        sizePx: Int
    ): AnimationDrawable {
        if (sizePx <= 0) {
            return context.getDrawableCompat(drawableRes) as AnimationDrawable
        }

        val key = CacheKey(drawableRes, sizePx)
        val rasterized = cache.getOrPut(key) { rasterizeNow(context, drawableRes, sizePx) }

        if (rasterized.frames.isEmpty()) {
            // A prior attempt failed (or this one just did) - don't retry the expensive path
            // again, just hand back the original vector drawable.
            return context.getDrawableCompat(drawableRes) as AnimationDrawable
        }

        val res = context.resources
        val out = AnimationDrawable()
        out.isOneShot = rasterized.isOneShot
        for (frame in rasterized.frames) {
            // Only the BitmapDrawable wrapper is new here - frame.bitmap's pixel data was
            // already rasterized once, on a prior call, and is shared across every instance.
            out.addFrame(BitmapDrawable(res, frame.bitmap), frame.durationMs)
        }
        return out
    }

    private fun rasterizeNow(
        context: Context,
        @DrawableRes drawableRes: Int,
        sizePx: Int
    ): RasterizedFrames =
        try {
            val source = context.getDrawableCompat(drawableRes) as AnimationDrawable
            val frames =
                (0 until source.numberOfFrames).map { i ->
                    val frame = source.getFrame(i)
                    val duration = source.getDuration(i)
                    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    frame.setBounds(0, 0, sizePx, sizePx)
                    frame.draw(canvas)
                    RasterizedFrame(bitmap, duration)
                }
            RasterizedFrames(source.isOneShot, frames)
        } catch (e: OutOfMemoryError) {
            RasterizedFrames(isOneShot = false, frames = emptyList())
        } catch (e: Exception) {
            RasterizedFrames(isOneShot = false, frames = emptyList())
        }

    /** Convenience overload using a dp size resolved against display density. */
    fun rasterizeDp(
        context: Context,
        @DrawableRes drawableRes: Int,
        sizeDp: Int
    ): AnimationDrawable {
        val px = (sizeDp * context.resources.displayMetrics.density).roundToInt()
        return rasterize(context, drawableRes, px)
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

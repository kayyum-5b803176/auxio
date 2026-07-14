/*
 * Copyright (c) 2026 Auxio Project
 * AcousticFeatures.kt is part of Auxio.
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
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber as L

/**
 * Extracts a compact ACOUSTIC feature vector from a song's audio content, to
 * SEED a [SongEmbedding] with something grounded in how the song actually sounds
 * (replacing the old artist/genre-hash seed, which had zero acoustic content).
 *
 * This is classical DSP (MIR "Option 1"): decode a window of audio to mono PCM,
 * take overlapping FFT frames, and summarize each frame into interpretable
 * features — spectral centroid (brightness), rolloff, spread/bandwidth, flatness
 * (tonal vs noisy), zero-crossing rate (percussive-ness), overall energy, and a
 * bank of log band energies (a coarse chroma/timbre profile). The per-frame
 * features are averaged over the window into one fixed vector, then z-scored and
 * L2-normalized so cosine distance between two songs' feature vectors reflects
 * acoustic similarity.
 *
 * No ML runtime, no bundled model, no extra APK size — pure Kotlin DSP. Runs
 * once per song (cached), off the main thread. A future pretrained-embedding
 * path (OpenL3/YAMNet via TFLite) can replace the output of [extract] without
 * touching any consumer, since it returns the same fixed-length vector.
 */
interface AcousticFeatures {
    /** Returns a fixed-length acoustic feature vector, or null on decode failure. */
    suspend fun extract(uri: Uri, durationMs: Long): FloatArray?

    companion object {
        /** Output vector length — matches ChainRepository.DIMENSIONS. */
        const val FEATURE_DIM = 24
    }
}

class AcousticFeaturesImpl
@Inject
constructor(@ApplicationContext private val context: Context) : AcousticFeatures {

    override suspend fun extract(uri: Uri, durationMs: Long): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                val pcm = decodeWindow(uri, durationMs) ?: return@withContext null
                analyze(pcm)
            } catch (e: Exception) {
                L.e("AcousticFeatures: extract failed: $e")
                null
            }
        }

    // ---- decode a mono PCM window at TARGET_SAMPLE_RATE ----
    private fun decodeWindow(uri: Uri, durationMs: Long): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return null

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            val startUs = (durationMs * 1000L * WINDOW_START_FRACTION).toLong()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val wantSamples = (WINDOW_SECONDS * sourceRate).toInt()
            val mono = FloatArray(wantSamples)
            var monoCount = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd && monoCount < wantSamples) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEnd = true
                    }
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        monoCount = downmixInto(
                            outBuf, bufferInfo, channels, mono, monoCount, wantSamples)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
            if (monoCount < FRAME_SIZE) return null
            // Resample to TARGET_SAMPLE_RATE for rate-independent features.
            return resample(mono, monoCount, sourceRate, TARGET_SAMPLE_RATE)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun downmixInto(
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        out: FloatArray,
        startCount: Int,
        limit: Int
    ): Int {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.position(info.offset)
        val shorts = (info.size / 2)
        var count = startCount
        var i = 0
        while (i < shorts && count < limit) {
            var acc = 0f
            for (c in 0 until channels) {
                if (i < shorts) {
                    acc += buf.short.toFloat() / 32768f
                    i++
                }
            }
            out[count++] = acc / channels
        }
        return count
    }

    private fun resample(input: FloatArray, count: Int, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input.copyOf(count)
        val ratio = toRate.toDouble() / fromRate
        val outLen = (count * ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            out[i] =
                if (idx + 1 < count) input[idx] * (1 - frac) + input[idx + 1] * frac
                else input[idx.coerceIn(0, count - 1)]
        }
        return out
    }

    // ---- feature extraction ----
    private fun analyze(samples: FloatArray): FloatArray {
        val frames = (samples.size - FRAME_SIZE) / HOP_SIZE
        if (frames <= 0) return FloatArray(AcousticFeatures.FEATURE_DIM)

        // Accumulators for per-frame features averaged over the window.
        var sumCentroid = 0f
        var sumRolloff = 0f
        var sumSpread = 0f
        var sumFlatness = 0f
        var sumZcr = 0f
        var sumEnergy = 0f
        val bandSums = FloatArray(BANDS)
        var frameCount = 0

        val nyquist = TARGET_SAMPLE_RATE / 2f
        val halfSpectrum = FRAME_SIZE / 2

        for (f in 0 until frames) {
            val offset = f * HOP_SIZE
            val mags = magnitudeSpectrum(samples, offset)

            var magSum = 0f
            var weightedFreqSum = 0f
            var geoMean = 0f
            var arithMean = 0f
            for (b in 0 until halfSpectrum) {
                val m = mags[b]
                val freq = (b.toFloat() / halfSpectrum) * nyquist
                magSum += m
                weightedFreqSum += freq * m
                arithMean += m
                geoMean += ln((m + 1e-8f).toDouble()).toFloat()
            }
            if (magSum < 1e-6f) continue
            arithMean /= halfSpectrum
            geoMean = kotlin.math.exp((geoMean / halfSpectrum).toDouble()).toFloat()

            // Spectral centroid (brightness).
            val centroid = weightedFreqSum / magSum
            // Spectral rolloff: freq below which 85% of energy lies.
            var cum = 0f
            var rolloffFreq = 0f
            val target = magSum * 0.85f
            for (b in 0 until halfSpectrum) {
                cum += mags[b]
                if (cum >= target) {
                    rolloffFreq = (b.toFloat() / halfSpectrum) * nyquist
                    break
                }
            }
            // Spectral spread (bandwidth around centroid).
            var spread = 0f
            for (b in 0 until halfSpectrum) {
                val freq = (b.toFloat() / halfSpectrum) * nyquist
                val d = freq - centroid
                spread += d * d * mags[b]
            }
            spread = sqrt(spread / magSum)
            // Spectral flatness (tonal vs noisy): geo/arith mean.
            val flatness = if (arithMean > 1e-8f) geoMean / arithMean else 0f
            // Band energies (coarse timbre/chroma profile).
            for (band in 0 until BANDS) {
                val lo = (band * halfSpectrum) / BANDS
                val hi = ((band + 1) * halfSpectrum) / BANDS
                var e = 0f
                for (b in lo until hi) e += mags[b] * mags[b]
                bandSums[band] += ln((e + 1e-8f).toDouble()).toFloat()
            }
            // Zero-crossing rate (percussive-ness) over this frame.
            var zc = 0
            for (i in offset + 1 until offset + FRAME_SIZE) {
                if ((samples[i] >= 0f) != (samples[i - 1] >= 0f)) zc++
            }
            sumZcr += zc.toFloat() / FRAME_SIZE
            // Frame energy (RMS).
            var rms = 0f
            for (i in offset until offset + FRAME_SIZE) rms += samples[i] * samples[i]
            sumEnergy += sqrt(rms / FRAME_SIZE)

            sumCentroid += centroid / nyquist // normalize to 0..1
            sumRolloff += rolloffFreq / nyquist
            sumSpread += spread / nyquist
            sumFlatness += flatness
            frameCount++
        }

        if (frameCount == 0) return FloatArray(AcousticFeatures.FEATURE_DIM)

        // Assemble the 24-dim vector: 8 scalar features + 16 band energies.
        val out = FloatArray(AcousticFeatures.FEATURE_DIM)
        out[0] = sumCentroid / frameCount
        out[1] = sumRolloff / frameCount
        out[2] = sumSpread / frameCount
        out[3] = sumFlatness / frameCount
        out[4] = sumZcr / frameCount
        out[5] = sumEnergy / frameCount
        out[6] = ln((sumEnergy / frameCount + 1e-6f).toDouble()).toFloat() // log-energy
        out[7] = (sumRolloff / frameCount) - (sumCentroid / frameCount) // high-freq skew
        for (band in 0 until BANDS) {
            out[8 + band] = bandSums[band] / frameCount
        }
        // Z-score then L2-normalize so cosine reflects shape, not scale.
        return normalizeVector(out)
    }

    private fun normalizeVector(v: FloatArray): FloatArray {
        var mean = 0f
        for (x in v) mean += x
        mean /= v.size
        var variance = 0f
        for (x in v) variance += (x - mean) * (x - mean)
        variance /= v.size
        val std = sqrt(variance).coerceAtLeast(1e-6f)
        val z = FloatArray(v.size) { (v[it] - mean) / std }
        var norm = 0f
        for (x in z) norm += x * x
        norm = sqrt(norm).coerceAtLeast(1e-6f)
        return FloatArray(z.size) { z[it] / norm }
    }

    private fun magnitudeSpectrum(samples: FloatArray, offset: Int): FloatArray {
        val re = FloatArray(FRAME_SIZE)
        val im = FloatArray(FRAME_SIZE)
        for (i in 0 until FRAME_SIZE) {
            val w = 0.5f - 0.5f * cos(2.0 * Math.PI * i / FRAME_SIZE).toFloat()
            re[i] = samples[offset + i] * w
        }
        fft(re, im)
        val mags = FloatArray(FRAME_SIZE / 2)
        for (i in mags.indices) mags[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        return mags
    }

    /** In-place iterative Cooley-Tukey radix-2 FFT. FRAME_SIZE must be power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curWr = 1f
                var curWi = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curWr - im[i + k + len / 2] * curWi
                    val vIm = re[i + k + len / 2] * curWi + im[i + k + len / 2] * curWr
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextWr = curWr * wr - curWi * wi
                    curWi = curWr * wi + curWi * wr
                    curWr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 22050
        const val WINDOW_SECONDS = 30.0
        const val WINDOW_START_FRACTION = 0.25
        const val FRAME_SIZE = 2048
        const val HOP_SIZE = 1024
        const val BANDS = 16
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}

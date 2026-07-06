/*
 * Copyright (c) 2026 Auxio Project
 * AudioFingerprinter.kt is part of Auxio.
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
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber as L

/**
 * Computes a compact acoustic fingerprint of a song's audio content.
 *
 * Method: decode a fixed window of audio (30s starting at 25% of the track)
 * to PCM via the platform MediaCodec, downmix to mono, then for each ~0.37s
 * frame compute log-energies in 16 frequency bands via FFT and quantize the
 * band-to-band / frame-to-frame energy differences into bits, yielding one
 * 32-bit int per frame (Chromaprint-style structure, simplified features).
 *
 * Two files containing the same recording (any encode/bitrate/container,
 * any metadata) produce fingerprints with a low bit-error-rate; different
 * recordings produce ~50% differing bits. Comparison happens in
 * [DuplicateFinder], including a small alignment search to tolerate slightly
 * different leading silence between encodes.
 *
 * Honest scope: this is a deliberately simple, dependency-free fingerprint,
 * not Chromaprint. It reliably separates same-recording from
 * different-recording, but it has NOT been tuned to catch heavily altered
 * versions (remasters with very different EQ, etc.) — those may score below
 * the match threshold, which we treat as acceptable: for a deletion feature,
 * false positives (merging two genuinely different songs) are far worse than
 * false negatives. The interface is small so a Chromaprint JNI implementation
 * can replace it later without touching any other component.
 */
interface AudioFingerprinter {
    /**
     * Fingerprint the audio at [uri].
     *
     * @return one int per analysis frame, or null if the file could not be
     *   decoded (missing codec, I/O error, DRM, etc. — callers should treat
     *   null as "cannot participate in duplicate detection", not an error).
     */
    suspend fun fingerprint(uri: Uri, durationMs: Long): IntArray?
}

class AudioFingerprinterImpl
@Inject
constructor(@ApplicationContext private val context: Context) : AudioFingerprinter {

    override suspend fun fingerprint(uri: Uri, durationMs: Long): IntArray? =
        withContext(Dispatchers.IO) {
            try {
                val pcm = decodeWindow(uri, durationMs) ?: return@withContext null
                withContext(Dispatchers.Default) { fingerprintPcm(pcm) }
            } catch (e: Exception) {
                // Per-file failures must never abort a whole library scan.
                L.w("Failed to fingerprint $uri: $e")
                null
            }
        }

    // ---------------------------------------------------------------------
    // Decoding
    // ---------------------------------------------------------------------

    private class DecodedAudio(
        val samples: FloatArray, // mono, TARGET_SAMPLE_RATE
    )

    private fun decodeWindow(uri: Uri, durationMs: Long): DecodedAudio? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Sample a fixed window rather than the whole song: enough signal
            // to identify a recording, ~10x less decode work per track. Seek
            // to a *relative* position so two encodes of the same recording
            // sample (nearly) the same audio even if absolute durations
            // differ by a second of padding.
            val startUs = (durationMs * 1000L * WINDOW_START_FRACTION).toLong()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxSamplesOut =
                (WINDOW_SECONDS * sourceSampleRate).toInt() // mono samples wanted, pre-resample
            val monoOut = FloatArray(maxSamplesOut)
            var monoCount = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos && monoCount < maxSamplesOut) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
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
                        sawOutputEos = true
                    }
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        monoCount = downmixInto(
                            outBuf, bufferInfo, sourceChannels, monoOut, monoCount)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }

            if (monoCount < sourceSampleRate) return null // <1s decoded; not enough signal

            // Cheap decimation resample to the fixed analysis rate. Linear
            // interpolation is plenty here: the fingerprint uses coarse band
            // energies, not fine spectral detail.
            val resampled = resample(monoOut, monoCount, sourceSampleRate, TARGET_SAMPLE_RATE)
            return DecodedAudio(resampled)
        } catch (e: Exception) {
            L.w("Decode failed for $uri: $e")
            return null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
            extractor.release()
        }
    }

    /** Downmix 16-bit PCM output to mono floats, appending into [monoOut] at [offset]. */
    private fun downmixInto(
        outBuf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        monoOut: FloatArray,
        offset: Int
    ): Int {
        // Some decoders emit data at a nonzero buffer offset; honor it.
        outBuf.position(info.offset)
        outBuf.limit(info.offset + info.size)
        val shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
        var written = offset
        var i = 0
        val frameCount = info.size / 2 / channels
        while (i < frameCount && written < monoOut.size) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += shorts.get(i * channels + c) / 32768f
            }
            monoOut[written++] = acc / channels
            i++
        }
        return written
    }

    private fun resample(input: FloatArray, count: Int, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input.copyOf(count)
        val outCount = (count.toLong() * toRate / fromRate).toInt()
        val out = FloatArray(outCount)
        val ratio = fromRate.toDouble() / toRate
        for (i in 0 until outCount) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceAtMost(count - 1)
            val i1 = (i0 + 1).coerceAtMost(count - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Fingerprinting
    // ---------------------------------------------------------------------

    private fun fingerprintPcm(audio: DecodedAudio): IntArray {
        val samples = audio.samples
        val frames = (samples.size - FRAME_SIZE) / HOP_SIZE
        if (frames <= TIME_LAG) return IntArray(0)

        // Per-frame log band energies
        val bandEnergies = Array(frames) { f ->
            val spectrum = magnitudeSpectrum(samples, f * HOP_SIZE)
            FloatArray(BANDS) { b ->
                var sum = 0f
                for (bin in bandStart(b) until bandEnd(b)) sum += spectrum[bin]
                ln(sum + 1e-9f)
            }
        }

        // Haitsma-Kalker-style bits: sign of the 2D energy gradient (band
        // difference now vs. TIME_LAG frames ago). Differences rather than
        // absolute energies buy invariance to loudness/EQ drift between
        // encodes; the time derivative decorrelates the generic spectral
        // tilt that unrelated songs share. TIME_LAG=4 and HOP=FRAME/8 were
        // chosen empirically: in synthetic stress tests they gave the widest
        // separation between same-recording (noisy) pairs and
        // different-recording pairs. 15 meaningful bits per frame.
        val fingerprint = IntArray(frames - TIME_LAG)
        for (f in TIME_LAG until frames) {
            var bits = 0
            for (b in 0 until BANDS - 1) {
                val gradient =
                    (bandEnergies[f][b] - bandEnergies[f][b + 1]) -
                        (bandEnergies[f - TIME_LAG][b] - bandEnergies[f - TIME_LAG][b + 1])
                if (gradient > 0f) bits = bits or (1 shl b)
            }
            fingerprint[f - TIME_LAG] = bits
        }
        return fingerprint
    }

    /** Real-input radix-2 FFT magnitude spectrum with a Hann window. */
    private fun magnitudeSpectrum(samples: FloatArray, offset: Int): FloatArray {
        val re = FloatArray(FRAME_SIZE)
        val im = FloatArray(FRAME_SIZE)
        for (i in 0 until FRAME_SIZE) {
            // Hann window to reduce spectral leakage
            val w = 0.5f - 0.5f * cos(2.0 * Math.PI * i / FRAME_SIZE).toFloat()
            re[i] = samples[offset + i] * w
        }
        fft(re, im)
        val mags = FloatArray(FRAME_SIZE / 2)
        for (i in mags.indices) {
            mags[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        }
        return mags
    }

    /** In-place iterative Cooley-Tukey radix-2 FFT. FRAME_SIZE must be a power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        // Butterfly passes
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun bandStart(band: Int): Int = BAND_EDGES[band]

    private fun bandEnd(band: Int): Int = BAND_EDGES[band + 1]

    private companion object {
        const val TARGET_SAMPLE_RATE = 11025
        const val WINDOW_SECONDS = 30.0
        const val WINDOW_START_FRACTION = 0.25
        const val FRAME_SIZE = 4096 // ~0.37s at 11025Hz
        const val HOP_SIZE = FRAME_SIZE / 8 // ~46ms; high overlap smooths the time derivative
        const val TIME_LAG = 4 // frames of separation for the temporal gradient
        const val BANDS = 16
        const val DEQUEUE_TIMEOUT_US = 10_000L

        // Approximately log-spaced band edges (FFT bin indices) from ~30Hz to
        // ~5kHz at 11025Hz/4096 — covers the perceptually dominant range.
        val BAND_EDGES =
            intArrayOf(11, 16, 23, 33, 47, 67, 96, 137, 195, 278, 397, 566, 807, 1151, 1641, 1900, 2048)
    }
}

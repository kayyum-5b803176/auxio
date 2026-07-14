/*
 * Copyright (c) 2026 Auxio Project
 * DuplicateScanService.kt is part of Auxio.
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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.oxycblt.auxio.music.MusicRepository
import timber.log.Timber as L

/**
 * Foreground service that computes the FINGERPRINT PASS for the whole library in
 * the background (the long, screen-off-vulnerable part of duplicate detection),
 * persisting each fingerprint to cache as it completes. The fast in-memory
 * grouping/quality-ranking stays in DuplicatesViewModel and runs from cache when
 * the user is on the screen — so this service guarantees fingerprints get
 * computed even with the screen off, and reopening the page groups instantly.
 *
 * Same lifecycle contract as AcousticScanService: explicit-start only,
 * START_NOT_STICKY, per-song cache means a re-tap resumes from where it stopped.
 */
@AndroidEntryPoint
class DuplicateScanService : Service() {
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var fingerprinter: AudioFingerprinter
    @Inject lateinit var fingerprintRepository: FingerprintRepository
    @Inject lateinit var progress: DuplicateScanProgress

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var workJob: Job? = null
    private lateinit var notification: DuplicateScanNotification

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notification = DuplicateScanNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(stopped = true)
            return START_NOT_STICKY
        }
        startForeground(notification.code, notification.build())
        if (workJob == null || workJob?.isActive != true) {
            startWork()
        }
        return START_NOT_STICKY
    }

    private fun startWork() {
        workJob =
            scope.launch {
                val songs = musicRepository.library?.songs?.toList().orEmpty()
                val total = songs.size
                val log = ArrayDeque<String>()
                fun logLine(s: String) {
                    log.addFirst(s)
                    while (log.size > LOG_WINDOW) log.removeLast()
                }
                if (total == 0) {
                    progress.set(ScanProgress.State.Done(0, 0, 0, 0, emptyList(), false))
                    stop(stopped = false)
                    return@launch
                }
                // Drop cache rows for songs no longer present (bounded growth).
                fingerprintRepository.prune(songs)

                var done = 0
                var processed = 0
                var cached = 0
                var failed = 0
                val semaphore = Semaphore(CONCURRENT_DECODES)
                songs
                    .map { song ->
                        async {
                            val fileName = song.path.name ?: song.uri.toString()
                            val existing = fingerprintRepository.getCached(song)
                            val isCached = existing != null
                            var ok = isCached
                            if (!isCached) {
                                semaphore.withPermit {
                                    val computed =
                                        try {
                                            fingerprinter.fingerprint(song.uri, song.durationMs)
                                        } catch (e: Exception) {
                                            L.e("DuplicateScanService: fp failed $fileName: $e")
                                            null
                                        }
                                    // Persist even a failed/empty result so an
                                    // unanalyzable file isn't retried every scan.
                                    val toStore = computed ?: FingerprintResult(IntArray(0), FloatArray(0))
                                    fingerprintRepository.put(song, toStore)
                                    ok = computed != null && computed.fingerprint.isNotEmpty()
                                }
                            }
                            synchronized(log) {
                                done++
                                when {
                                    isCached -> cached++
                                    ok -> processed++
                                    else -> failed++
                                }
                                logLine(
                                    when {
                                        isCached -> "$fileName (cached)"
                                        ok -> fileName
                                        else -> "$fileName (failed)"
                                    })
                                progress.set(
                                    ScanProgress.State.Running(done, total, fileName, log.toList()))
                            }
                            if (notification.update(done, total)) {
                                NotificationManagerCompat.from(this@DuplicateScanService)
                                    .notify(notification.code, notification.build())
                            }
                        }
                    }
                    .awaitAll()

                progress.set(
                    ScanProgress.State.Done(processed, cached, failed, total, log.toList(), false))
                stop(stopped = false)
            }
    }

    private fun stop(stopped: Boolean) {
        workJob?.cancel()
        if (stopped) {
            val s = progress.state.value
            if (s is ScanProgress.State.Running) {
                progress.set(ScanProgress.State.Done(0, 0, 0, s.total, s.log, stopped = true))
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ACTION_STOP = "org.oxycblt.auxio.plugin.similarity.DUPLICATE_STOP"
        private const val CONCURRENT_DECODES = 2
        private const val LOG_WINDOW = 6

        fun start(context: Context) {
            val intent = Intent(context, DuplicateScanService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

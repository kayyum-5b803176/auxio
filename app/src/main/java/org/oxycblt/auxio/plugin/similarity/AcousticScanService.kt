/*
 * Copyright (c) 2026 Auxio Project
 * AcousticScanService.kt is part of Auxio.
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
 * Foreground service that acoustically seeds the whole library, surviving both
 * leaving the scan screen AND the screen turning off (a plain viewModelScope
 * coroutine dies with the Fragment and gets throttled by Doze; a foreground
 * service does not). Started ONLY by an explicit user tap — never auto-run.
 *
 * START_NOT_STICKY: if the process is killed (app swiped away), the OS does not
 * relaunch this; on reopen nothing runs until the user taps Scan again. Because
 * each song persists to the DB as it completes, a re-tap resumes from where it
 * stopped (cached songs are skipped).
 */
@AndroidEntryPoint
class AcousticScanService : Service() {
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var chainRepository: ChainRepository
    @Inject lateinit var progress: AcousticScanProgress

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var workJob: Job? = null
    private lateinit var notification: AcousticScanNotification

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notification = AcousticScanNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(stopped = true)
            return START_NOT_STICKY
        }
        // Enter foreground immediately (OS requires this promptly after start).
        startForeground(notification.code, notification.build())
        if (workJob == null || workJob?.isActive != true) {
            startWork()
        }
        return START_NOT_STICKY
    }

    private fun startWork() {
        val force = false
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

                var done = 0
                var processed = 0
                var cached = 0
                var failed = 0
                val semaphore = Semaphore(CONCURRENT_DECODES)
                songs
                    .map { song ->
                        async {
                            val fileName = song.path.name ?: song.uri.toString()
                            val isCached = !force && chainRepository.isAcousticSeeded(song)
                            val ok =
                                if (isCached) true
                                else
                                    semaphore.withPermit {
                                        try {
                                            chainRepository.seedAcoustic(song)
                                        } catch (e: Exception) {
                                            L.e("AcousticScanService: seed failed $fileName: $e")
                                            false
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
                                NotificationManagerCompat.from(this@AcousticScanService)
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
            // User cancelled: mark the last state as stopped for the UI.
            val s = progress.state.value
            if (s is ScanProgress.State.Running) {
                progress.set(
                    ScanProgress.State.Done(0, 0, 0, s.total, s.log, stopped = true))
            }
        }
        androidx.core.app.ServiceCompat.stopForeground(
            this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ACTION_STOP = "org.oxycblt.auxio.plugin.similarity.ACOUSTIC_STOP"
        private const val CONCURRENT_DECODES = 2
        private const val LOG_WINDOW = 6

        /** Start the scan (explicit user action only). */
        fun start(context: Context) {
            val intent = Intent(context, AcousticScanService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}

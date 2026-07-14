/*
 * Copyright (c) 2026 Auxio Project
 * ScanNotifications.kt is part of Auxio.
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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.oxycblt.auxio.ForegroundServiceNotification
import org.oxycblt.auxio.IntegerTable
import org.oxycblt.auxio.R
import org.oxycblt.auxio.util.newMainPendingIntent

/** Shared low-priority progress channel for both scans. */
private val scanChannel =
    ForegroundServiceNotification.ChannelInfo(
        id = "org.oxycblt.auxio.plugin.similarity.SCAN_CHANNEL",
        nameRes = R.string.set_plugins)

/** Progress notification for the acoustic scan foreground service. */
class AcousticScanNotification(private val context: Context) :
    ForegroundServiceNotification(context, scanChannel) {
    private var lastUpdateTime = -1L

    init {
        setSmallIcon(R.drawable.ic_indexer_24)
        setCategory(NotificationCompat.CATEGORY_PROGRESS)
        setShowWhen(false)
        setSilent(true)
        setContentIntent(context.newMainPendingIntent())
        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        setContentTitle(context.getString(R.string.set_acoustic_scan))
        setProgress(0, 0, true)
        addAction(
            R.drawable.ic_close_24,
            context.getString(R.string.lbl_cancel),
            stopIntent(context, AcousticScanService.ACTION_STOP, AcousticScanService::class.java))
    }

    override val code: Int
        get() = IntegerTable.ACOUSTIC_SCAN_NOTIFICATION_CODE

    /** Rate-limited (1.5s) progress update; returns true if the notification changed. */
    fun update(done: Int, total: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastUpdateTime > -1 && (now - lastUpdateTime) < 1500) return false
        lastUpdateTime = now
        setContentText(context.getString(R.string.fmt_acoustic_progress, done, total))
        setProgress(total, done, false)
        return true
    }
}

/** Progress notification for the duplicate scan foreground service. */
class DuplicateScanNotification(private val context: Context) :
    ForegroundServiceNotification(context, scanChannel) {
    private var lastUpdateTime = -1L

    init {
        setSmallIcon(R.drawable.ic_indexer_24)
        setCategory(NotificationCompat.CATEGORY_PROGRESS)
        setShowWhen(false)
        setSilent(true)
        setContentIntent(context.newMainPendingIntent())
        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        setContentTitle(context.getString(R.string.set_find_duplicates))
        setProgress(0, 0, true)
        addAction(
            R.drawable.ic_close_24,
            context.getString(R.string.lbl_cancel),
            stopIntent(context, DuplicateScanService.ACTION_STOP, DuplicateScanService::class.java))
    }

    override val code: Int
        get() = IntegerTable.DUPLICATE_SCAN_NOTIFICATION_CODE

    fun update(done: Int, total: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastUpdateTime > -1 && (now - lastUpdateTime) < 1500) return false
        lastUpdateTime = now
        setContentText(context.getString(R.string.dup_scan_progress, done, total))
        setProgress(total, done, false)
        return true
    }
}

private fun stopIntent(context: Context, action: String, cls: Class<*>): PendingIntent {
    val intent = Intent(context, cls).setAction(action)
    return PendingIntent.getService(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

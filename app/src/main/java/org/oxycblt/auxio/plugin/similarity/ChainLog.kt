/*
 * Copyright (c) 2026 Auxio Project
 * ChainLog.kt is part of Auxio.
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

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Persistent log of the most recent Smart Chain events, shown on the Logs page.
 *
 * Backed by Room (see [ChainLogDao]) rather than an in-memory buffer, so the
 * log survives app restarts. Writes go to the DB off the main thread; the Logs
 * page observes [entries], a reactive query that updates live as events land
 * and replays the stored history the moment the screen opens (which is why the
 * page is never empty after a restart, and why open-after-the-fact still shows
 * everything that was recorded).
 *
 * Capped at [CAPACITY] rows: each insert is followed by a trim to the newest
 * CAPACITY entries, so the table can't grow without bound.
 */
@Singleton
class ChainLog @Inject constructor(private val dao: ChainLogDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Most recent entries, newest first, capped at [CAPACITY]. */
    val entries: StateFlow<List<Entry>> =
        dao.recent(CAPACITY)
            .map { rows -> rows.map { Entry(it.timestampMs, it.message) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Record a learning event. Safe to call from any thread. */
    fun log(message: String) {
        scope.launch {
            dao.insert(ChainLogEntry(timestampMs = System.currentTimeMillis(), message = message))
            dao.trimTo(CAPACITY)
        }
    }

    fun clear() {
        scope.launch { dao.nuke() }
    }

    data class Entry(val timestampMs: Long, val message: String) {
        fun formattedTime(): String = TIME_FORMAT.format(Date(timestampMs))

        private companion object {
            val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        }
    }

    companion object {
        const val CAPACITY = 100
    }
}

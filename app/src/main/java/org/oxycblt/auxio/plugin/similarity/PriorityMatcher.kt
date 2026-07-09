/*
 * Copyright (c) 2026 Auxio Project
 * PriorityMatcher.kt is part of Auxio.
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

import org.oxycblt.musikr.Song

/**
 * Decides whether a song lives in a user-defined "priority" folder, matched by
 * folder NAME (case-insensitive) anywhere in the song's path.
 *
 * Name-based (not path-based) matching is deliberate: the user types folder
 * names like "export"/"import" and any folder of that name anywhere in their
 * storage counts, with no fragile SAF/document-tree path resolution involved.
 *
 * This is a general helper (not specific to duplicate detection) so future
 * plugins can reuse the same "priority folder" concept.
 */
class PriorityMatcher(priorityFolderNames: List<String>) {
    // Pre-lowered set for case-insensitive O(1) segment lookup.
    private val names: Set<String> =
        priorityFolderNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** True if any user-defined priority-folder name is configured. */
    val isActive: Boolean
        get() = names.isNotEmpty()

    /**
     * True if any directory segment of [song]'s path matches a priority folder
     * name (case-insensitive). The file's own name is excluded — only the
     * containing directories are considered.
     */
    fun isPrioritized(song: Song): Boolean {
        if (names.isEmpty()) return false
        val components = song.path.components.components
        // The last component is the file name itself; only directories qualify.
        val directoryCount = (components.size - 1).coerceAtLeast(0)
        for (i in 0 until directoryCount) {
            if (components[i].lowercase() in names) return true
        }
        return false
    }
}

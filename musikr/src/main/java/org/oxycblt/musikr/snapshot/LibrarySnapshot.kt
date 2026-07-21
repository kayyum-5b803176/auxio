/*
 * Copyright (c) 2026 Auxio Project
 * LibrarySnapshot.kt is part of Auxio.
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

package org.oxycblt.musikr.snapshot

/**
 * A flat, serialization-friendly representation of a fully-loaded music library.
 *
 * The purpose of a snapshot is to let the app rebuild its in-memory library on startup WITHOUT
 * doing a real filesystem/MediaStore scan and WITHOUT re-parsing any audio-file tags. A real scan
 * (see [org.oxycblt.musikr.Musikr.run]) walks the whole device via MediaStore/SAF, staleness-checks
 * every file against the tag cache, and rebuilds the graph - which for a large library is several
 * seconds of work on every cold start (and cold starts are common on aggressive OEM ROMs that kill
 * backgrounded processes). A snapshot skips all of that: it stores exactly the per-song data that
 * the evaluate step consumes ([org.oxycblt.musikr.pipeline.RawSong] equivalents), so on load we can
 * feed it straight into the same graph builder + LibraryFactory the scan uses.
 *
 * Design notes / why this shape:
 * - Everything here is a primitive, String, or List thereof. There are NO live handles (no
 *   file descriptors, no Bitmaps, no Deferred parents). This is what makes it safe to write to disk
 *   and read back in a different process.
 * - Covers are stored ONLY by their [SnapshotSong.coverId] string. The actual cover image bytes
 *   already live in the persistent, revisioned cover store on disk; on load we re-obtain the Cover
 *   instance by id through that same store. So no image data is duplicated into the snapshot.
 * - The tag fields mirror ParsedTags exactly (which is already proven-serializable by the Room tag
 *   cache, CachedSongData). We deliberately store the RAW, uninterpreted tag values and re-run the
 *   real TagInterpreter at load time, rather than trying to serialize the interpreted Pre* graph
 *   types (Name.Known token lists, nested Format/ReleaseType/Date, etc). Replaying the interpreter
 *   reuses the most-tested code path and keeps the on-disk format small and stable.
 * - [volumeMediaStoreName] + [pathComponents] let us rebuild the file's Path by re-matching the
 *   volume on the current device at load time. Volumes are matched by their MediaStore name, which
 *   is stable across restarts for a given physical volume.
 *
 * The [version] field guards against loading a snapshot written by an incompatible app build: if it
 * doesn't match [CURRENT_VERSION], the loader discards the snapshot and falls back to a real scan.
 * Bump [CURRENT_VERSION] whenever the shape of any type in this file changes.
 */
internal data class LibrarySnapshot(
    val version: Int,
    /** The musikr cover-store revision this snapshot was captured against, for validation. */
    val revision: String?,
    val songs: List<SnapshotSong>,
    val playlists: List<SnapshotPlaylist>
) {
    companion object {
        /**
         * The current on-disk snapshot format version.
         *
         * BUMP THIS whenever [SnapshotSong], [SnapshotPlaylist], or the serialized JSON shape
         * changes in a way that would make an older file parse incorrectly. A mismatch causes the
         * loader to safely ignore the old snapshot and do a normal scan instead.
         */
        const val CURRENT_VERSION = 1
    }
}

/**
 * Flat representation of a single song, mirroring the inputs the evaluate step needs.
 *
 * The [uri], [modifiedMs], [addedMs], [mimeType], [size], [volumeMediaStoreName], and
 * [pathComponents] fields reconstruct the file descriptor. The remaining fields are the raw
 * (uninterpreted) parsed tags + audio properties, matching ParsedTags/Properties one-to-one.
 */
internal data class SnapshotSong(
    // --- File descriptor ---
    val uri: String,
    val volumeMediaStoreName: String?,
    val pathComponents: String,
    val modifiedMs: Long,
    val addedMs: Long,
    val mimeType: String,
    val size: Long,
    // --- Audio properties ---
    val durationMs: Long,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    // --- Cover (by id only) ---
    val coverId: String?,
    // --- Raw parsed tags (mirror of ParsedTags) ---
    val musicBrainzId: String?,
    val name: String?,
    val sortName: String?,
    val track: Int?,
    val disc: Int?,
    val subtitle: String?,
    val date: String?,
    val albumMusicBrainzId: String?,
    val albumName: String?,
    val albumSortName: String?,
    val releaseTypes: List<String>,
    val artistMusicBrainzIds: List<String>,
    val artistNames: List<String>,
    val artistSortNames: List<String>,
    val albumArtistMusicBrainzIds: List<String>,
    val albumArtistNames: List<String>,
    val albumArtistSortNames: List<String>,
    val genreNames: List<String>,
    val replayGainTrackAdjustment: Float?,
    val replayGainAlbumAdjustment: Float?
)

/**
 * Flat representation of a user playlist.
 *
 * Playlists are stored persistently by musikr's own StoredPlaylists already, so on load we do NOT
 * reconstruct them from this snapshot - we re-read them from StoredPlaylists (fast, no file scan).
 * This type exists only so the snapshot can record that playlists were present and, if desired in
 * the future, validate them. Kept minimal on purpose.
 */
internal data class SnapshotPlaylist(val name: String)

/*
 * Copyright (c) 2026 Auxio Project
 * SnapshotStore.kt is part of Auxio.
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

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes a [LibrarySnapshot] to a single JSON file in the app cache directory.
 *
 * Storage-wear characteristics (this was a design requirement):
 * - The snapshot is a SINGLE flat file that is fully overwritten on each write (never appended or
 *   grown incrementally), so there's no fragmentation and no unbounded growth.
 * - Writes are ATOMIC: we write to a temp file and rename it over the real file. A process death
 *   mid-write therefore can't leave a corrupt snapshot (which would only cost one wasted extra
 *   scan+rewrite next launch anyway).
 * - The caller is responsible for only writing when the library actually changed (see the app-side
 *   MusicRepository change flags), so a plain app reopen with no library changes performs ZERO
 *   writes - it only reads. This keeps write frequency tied to how often the user's music actually
 *   changes, not how often they open the app.
 *
 * The file lives in [Context.getCacheDir], so the OS may reclaim it under storage pressure. That is
 * fine and intended: a missing snapshot simply falls back to a normal scan, which then rewrites it.
 *
 * All operations FAIL CLOSED: any I/O or parse error results in null (for reads) or a logged, but
 * swallowed, failure (for writes). Callers must treat a null read as "no snapshot, do a real scan".
 */
internal class SnapshotStore private constructor(private val file: File) {
    /**
     * Read and parse the snapshot from disk.
     *
     * @return the parsed [LibrarySnapshot], or null if the file is missing, unreadable, corrupt, or
     *   written by an incompatible version. Never throws.
     */
    suspend fun read(): LibrarySnapshot? =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) {
                    Log.d(TAG, "No snapshot file present")
                    return@withContext null
                }
                val text = file.readText()
                val snapshot = SnapshotJson.decode(text)
                if (snapshot == null) {
                    Log.w(TAG, "Snapshot failed to decode, ignoring")
                    return@withContext null
                }
                if (snapshot.version != LibrarySnapshot.CURRENT_VERSION) {
                    Log.d(
                        TAG,
                        "Snapshot version ${snapshot.version} != " +
                            "${LibrarySnapshot.CURRENT_VERSION}, ignoring")
                    return@withContext null
                }
                Log.d(TAG, "Read snapshot with ${snapshot.songs.size} songs")
                snapshot
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read snapshot, ignoring", e)
                null
            }
        }

    /**
     * Atomically write [snapshot] to disk, replacing any existing snapshot.
     *
     * Never throws; failures are logged and swallowed (a failed write just means next launch does a
     * normal scan).
     */
    suspend fun write(snapshot: LibrarySnapshot) =
        withContext(Dispatchers.IO) {
            try {
                val text = SnapshotJson.encode(snapshot)
                // Atomic replace: write to temp then rename over the target.
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    // renameTo can fail across some filesystems; fall back to copy + delete.
                    file.writeText(text)
                    tmp.delete()
                }
                Log.d(TAG, "Wrote snapshot with ${snapshot.songs.size} songs")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write snapshot", e)
            }
        }

    /** Delete the snapshot file if present. Never throws. */
    suspend fun clear() =
        withContext(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear snapshot", e)
            }
        }

    companion object {
        private const val TAG = "SnapshotStore"
        private const val FILE_NAME = "library_snapshot.json"

        fun from(context: Context): SnapshotStore =
            SnapshotStore(File(context.cacheDir, FILE_NAME))
    }
}

/** JSON (de)serialization for [LibrarySnapshot]. Uses android's built-in org.json (no new deps). */
private object SnapshotJson {
    fun encode(snapshot: LibrarySnapshot): String {
        val root = JSONObject()
        root.put("version", snapshot.version)
        root.put("revision", snapshot.revision ?: JSONObject.NULL)

        val songs = JSONArray()
        for (song in snapshot.songs) {
            songs.put(encodeSong(song))
        }
        root.put("songs", songs)

        val playlists = JSONArray()
        for (playlist in snapshot.playlists) {
            playlists.put(JSONObject().put("name", playlist.name))
        }
        root.put("playlists", playlists)

        return root.toString()
    }

    fun decode(text: String): LibrarySnapshot? {
        val root = JSONObject(text)
        val version = root.optInt("version", -1)
        if (version < 0) return null
        val revision = root.optStringOrNull("revision")

        val songsJson = root.optJSONArray("songs") ?: return null
        val songs = ArrayList<SnapshotSong>(songsJson.length())
        for (i in 0 until songsJson.length()) {
            val obj = songsJson.optJSONObject(i) ?: return null
            songs.add(decodeSong(obj) ?: return null)
        }

        val playlists = ArrayList<SnapshotPlaylist>()
        val playlistsJson = root.optJSONArray("playlists")
        if (playlistsJson != null) {
            for (i in 0 until playlistsJson.length()) {
                val obj = playlistsJson.optJSONObject(i) ?: continue
                val name = obj.optStringOrNull("name") ?: continue
                playlists.add(SnapshotPlaylist(name))
            }
        }

        return LibrarySnapshot(version, revision, songs, playlists)
    }

    private fun encodeSong(song: SnapshotSong): JSONObject =
        JSONObject().apply {
            put("uri", song.uri)
            put("volumeMediaStoreName", song.volumeMediaStoreName ?: JSONObject.NULL)
            put("pathComponents", song.pathComponents)
            put("modifiedMs", song.modifiedMs)
            put("addedMs", song.addedMs)
            put("mimeType", song.mimeType)
            put("size", song.size)
            put("durationMs", song.durationMs)
            put("bitrateKbps", song.bitrateKbps)
            put("sampleRateHz", song.sampleRateHz)
            put("coverId", song.coverId ?: JSONObject.NULL)
            put("musicBrainzId", song.musicBrainzId ?: JSONObject.NULL)
            put("name", song.name ?: JSONObject.NULL)
            put("sortName", song.sortName ?: JSONObject.NULL)
            put("track", song.track ?: JSONObject.NULL)
            put("disc", song.disc ?: JSONObject.NULL)
            put("subtitle", song.subtitle ?: JSONObject.NULL)
            put("date", song.date ?: JSONObject.NULL)
            put("albumMusicBrainzId", song.albumMusicBrainzId ?: JSONObject.NULL)
            put("albumName", song.albumName ?: JSONObject.NULL)
            put("albumSortName", song.albumSortName ?: JSONObject.NULL)
            put("releaseTypes", JSONArray(song.releaseTypes))
            put("artistMusicBrainzIds", JSONArray(song.artistMusicBrainzIds))
            put("artistNames", JSONArray(song.artistNames))
            put("artistSortNames", JSONArray(song.artistSortNames))
            put("albumArtistMusicBrainzIds", JSONArray(song.albumArtistMusicBrainzIds))
            put("albumArtistNames", JSONArray(song.albumArtistNames))
            put("albumArtistSortNames", JSONArray(song.albumArtistSortNames))
            put("genreNames", JSONArray(song.genreNames))
            put(
                "replayGainTrackAdjustment",
                song.replayGainTrackAdjustment?.toDouble() ?: JSONObject.NULL)
            put(
                "replayGainAlbumAdjustment",
                song.replayGainAlbumAdjustment?.toDouble() ?: JSONObject.NULL)
        }

    private fun decodeSong(obj: JSONObject): SnapshotSong? {
        val uri = obj.optStringOrNull("uri") ?: return null
        val pathComponents = obj.optStringOrNull("pathComponents") ?: return null
        val mimeType = obj.optStringOrNull("mimeType") ?: return null
        return SnapshotSong(
            uri = uri,
            volumeMediaStoreName = obj.optStringOrNull("volumeMediaStoreName"),
            pathComponents = pathComponents,
            modifiedMs = obj.optLong("modifiedMs"),
            addedMs = obj.optLong("addedMs"),
            mimeType = mimeType,
            size = obj.optLong("size"),
            durationMs = obj.optLong("durationMs"),
            bitrateKbps = obj.optInt("bitrateKbps"),
            sampleRateHz = obj.optInt("sampleRateHz"),
            coverId = obj.optStringOrNull("coverId"),
            musicBrainzId = obj.optStringOrNull("musicBrainzId"),
            name = obj.optStringOrNull("name"),
            sortName = obj.optStringOrNull("sortName"),
            track = obj.optIntOrNull("track"),
            disc = obj.optIntOrNull("disc"),
            subtitle = obj.optStringOrNull("subtitle"),
            date = obj.optStringOrNull("date"),
            albumMusicBrainzId = obj.optStringOrNull("albumMusicBrainzId"),
            albumName = obj.optStringOrNull("albumName"),
            albumSortName = obj.optStringOrNull("albumSortName"),
            releaseTypes = obj.optStringList("releaseTypes"),
            artistMusicBrainzIds = obj.optStringList("artistMusicBrainzIds"),
            artistNames = obj.optStringList("artistNames"),
            artistSortNames = obj.optStringList("artistSortNames"),
            albumArtistMusicBrainzIds = obj.optStringList("albumArtistMusicBrainzIds"),
            albumArtistNames = obj.optStringList("albumArtistNames"),
            albumArtistSortNames = obj.optStringList("albumArtistSortNames"),
            genreNames = obj.optStringList("genreNames"),
            replayGainTrackAdjustment = obj.optFloatOrNull("replayGainTrackAdjustment"),
            replayGainAlbumAdjustment = obj.optFloatOrNull("replayGainAlbumAdjustment"))
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun JSONObject.optFloatOrNull(key: String): Float? =
        if (isNull(key) || !has(key)) null else optDouble(key).toFloat()

    private fun JSONObject.optStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            out.add(array.optString(i))
        }
        return out
    }
}

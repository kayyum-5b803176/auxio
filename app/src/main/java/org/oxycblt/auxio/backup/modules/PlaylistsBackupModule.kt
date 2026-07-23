/*
 * Copyright (c) 2026 Auxio Project
 * PlaylistsBackupModule.kt is part of Auxio.
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

package org.oxycblt.auxio.backup.modules

import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.backup.BackupModule
import org.oxycblt.auxio.backup.ConflictResolution
import org.oxycblt.auxio.backup.MergeResult
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name

/**
 * Backs up user playlists.
 *
 * Playlists are matched across devices by NAME (case-insensitive). Songs
 * within a playlist are matched first by [Music.UID] — which, being derived
 * from stable file metadata, is often identical across two libraries holding
 * the same track — and, when a backed-up UID doesn't resolve in the current
 * library, by a metadata fallback key (title|artist|album, lowercased). Each
 * backed-up song also carries that metadata so the fallback works even when
 * UIDs legitimately differ (e.g. one library tagged from MusicBrainz, the
 * other not).
 *
 * The merge is strictly append-only, honoring "nothing is lost":
 *  - A playlist name that doesn't exist here is created with whatever of its
 *    songs resolve in this library.
 *  - A playlist name that exists here keeps all its current songs, in order,
 *    and any backed-up songs not already present are appended to the end.
 *    Existing songs are never removed or reordered.
 *  - Songs referenced in the backup that don't exist in this library at all
 *    (neither by UID nor metadata) are silently skipped — they'll simply be
 *    absent until that music is added, at which point re-importing picks them
 *    up. This never deletes anything.
 *
 * There is no genuine conflict case (append-only union of song sets has one
 * right answer), so this module never raises a
 * [org.oxycblt.auxio.backup.Conflict].
 */
class PlaylistsBackupModule @Inject constructor(private val musicRepository: MusicRepository) :
    BackupModule {
    override val id = "playlists"
    override val displayName = "Playlists"
    override val schemaVersion = 1

    override suspend fun hasData(): Boolean =
        musicRepository.library?.playlists?.isNotEmpty() == true

    override suspend fun export(): JSONObject {
        val library = musicRepository.library
        return JSONObject().apply {
            put(
                "playlists",
                JSONArray().apply {
                    library?.playlists?.forEach { playlist ->
                        put(
                            JSONObject().apply {
                                put("name", playlistRawName(playlist))
                                put(
                                    "songs",
                                    JSONArray().apply {
                                        playlist.songs.forEach { song -> put(songJson(song)) }
                                    })
                            })
                    }
                })
        }
    }

    override suspend fun merge(
        incoming: JSONObject,
        incomingSchemaVersion: Int,
        resolutions: Map<String, ConflictResolution>,
        apply: Boolean
    ): MergeResult {
        val library = musicRepository.library ?: return MergeResult()
        var added = 0
        var updated = 0
        var unchanged = 0

        // Build the metadata fallback index once for this whole merge pass.
        val byMetadata = HashMap<String, Song>()
        for (song in library.songs) byMetadata.putIfAbsent(metadataKey(song), song)

        fun resolveSong(row: JSONObject): Song? {
            val uidStr = row.optString("uid", "")
            if (uidStr.isNotEmpty()) {
                Music.UID.fromString(uidStr)?.let { uid -> library.findSong(uid)?.let { return it } }
            }
            val key = metadataKeyFromJson(row) ?: return null
            return byMetadata[key]
        }

        val incomingPlaylists = incoming.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until incomingPlaylists.length()) {
            val row = incomingPlaylists.getJSONObject(i)
            val name = row.getString("name")
            val songRows = row.optJSONArray("songs") ?: JSONArray()
            val resolvedSongs =
                (0 until songRows.length()).mapNotNull { resolveSong(songRows.getJSONObject(it)) }

            val existing =
                library.playlists.find { playlistRawName(it).equals(name, ignoreCase = true) }
            if (existing == null) {
                added++
                if (apply && resolvedSongs.isNotEmpty()) {
                    musicRepository.createPlaylist(name, resolvedSongs.distinctBy { it.uid })
                }
            } else {
                val existingUids = existing.songs.map { it.uid }.toHashSet()
                val toAppend = resolvedSongs.filter { it.uid !in existingUids }.distinctBy { it.uid }
                if (toAppend.isEmpty()) {
                    unchanged++
                } else {
                    updated++
                    if (apply) musicRepository.addToPlaylist(toAppend, existing)
                }
            }
        }

        return MergeResult(added = added, updated = updated, unchanged = unchanged)
    }

    // ---- song (de)serialization + matching keys -------------------------

    /** Playlist names are always [Name.Known] (user-typed). */
    private fun playlistRawName(playlist: Playlist): String = playlist.name.raw

    private fun songJson(song: Song): JSONObject =
        JSONObject().apply {
            put("uid", song.uid.toString())
            put("title", song.name.raw)
            put("artist", song.artists.firstOrNull()?.let { rawNameOf(it.name) } ?: "")
            put("album", rawNameOf(song.album.name))
        }

    private fun metadataKey(song: Song): String {
        val title = song.name.raw.lowercase()
        val artist = (song.artists.firstOrNull()?.let { rawNameOf(it.name) } ?: "").lowercase()
        val album = rawNameOf(song.album.name).lowercase()
        return "$title|$artist|$album"
    }

    /** [Name.Known.raw] if known, or "" for [Name.Unknown] (Album/Artist names aren't always tagged). */
    private fun rawNameOf(name: Name): String =
        when (name) {
            is Name.Known -> name.raw
            is Name.Unknown -> ""
        }

    private fun metadataKeyFromJson(row: JSONObject): String? {
        val title = row.optString("title", "").lowercase()
        if (title.isEmpty()) return null
        val artist = row.optString("artist", "").lowercase()
        val album = row.optString("album", "").lowercase()
        return "$title|$artist|$album"
    }
}

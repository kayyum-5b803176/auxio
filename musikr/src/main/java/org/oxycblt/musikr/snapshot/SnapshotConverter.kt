/*
 * Copyright (c) 2026 Auxio Project
 * SnapshotConverter.kt is part of Auxio.
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
import android.net.Uri
import org.oxycblt.musikr.Storage
import org.oxycblt.musikr.covers.Cover
import org.oxycblt.musikr.covers.CoverResult
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.fs.path.VolumeManager
import org.oxycblt.musikr.metadata.Properties
import org.oxycblt.musikr.pipeline.RawSong
import org.oxycblt.musikr.tag.parse.ParsedTags

/**
 * Converts between the pipeline's live [RawSong] and the flat, serializable [SnapshotSong].
 *
 * Capture ([toSnapshotSong]) is trivial - just copy the flat fields plus the cover id. Restore
 * ([toRawSong]) is where the care is: we must rebuild a valid [File] (including its [Path] on the
 * current device) and re-obtain the [Cover] instance by id, all without touching the real
 * filesystem index.
 */
internal class SnapshotConverter(context: Context) {
    private val volumeManager = VolumeManager.from(context)

    /** All volumes currently on the device, indexed by their MediaStore name for path rebuilds. */
    private val volumesByName: Map<String?, Volume> by lazy {
        volumeManager.getVolumes().associateBy { it.mediaStoreName }
    }

    fun toSnapshotSong(song: RawSong): SnapshotSong {
        val file = song.file
        val tags = song.tags
        val props = song.properties
        return SnapshotSong(
            uri = file.uri.toString(),
            volumeMediaStoreName = file.path.volume.mediaStoreName,
            pathComponents = file.path.components.unixString,
            modifiedMs = file.modifiedMs,
            addedMs = song.addedMs,
            mimeType = file.mimeType,
            size = file.size,
            durationMs = props.durationMs,
            bitrateKbps = props.bitrateKbps,
            sampleRateHz = props.sampleRateHz,
            coverId = song.cover?.id,
            musicBrainzId = tags.musicBrainzId,
            name = tags.name,
            sortName = tags.sortName,
            track = tags.track,
            disc = tags.disc,
            subtitle = tags.subtitle,
            date = tags.date?.toString(),
            albumMusicBrainzId = tags.albumMusicBrainzId,
            albumName = tags.albumName,
            albumSortName = tags.albumSortName,
            releaseTypes = tags.releaseTypes,
            artistMusicBrainzIds = tags.artistMusicBrainzIds,
            artistNames = tags.artistNames,
            artistSortNames = tags.artistSortNames,
            albumArtistMusicBrainzIds = tags.albumArtistMusicBrainzIds,
            albumArtistNames = tags.albumArtistNames,
            albumArtistSortNames = tags.albumArtistSortNames,
            genreNames = tags.genreNames,
            replayGainTrackAdjustment = tags.replayGainTrackAdjustment,
            replayGainAlbumAdjustment = tags.replayGainAlbumAdjustment)
    }

    /**
     * Rebuild a [RawSong] from a snapshot entry.
     *
     * @param snapshot the flat song entry.
     * @param storage used to re-obtain the [Cover] by id from the persistent cover store.
     * @return the reconstructed [RawSong], or null if it can't be safely rebuilt (e.g. the volume
     *   it lived on is no longer present on the device). A null return causes the whole snapshot
     *   load to be abandoned in favor of a real scan, so we never present a partial library.
     */
    suspend fun toRawSong(snapshot: SnapshotSong, storage: Storage): RawSong? {
        val volume = volumesByName[snapshot.volumeMediaStoreName] ?: return null
        val path = Path(volume, Components.parseUnix(snapshot.pathComponents))

        val file =
            File(
                uri = Uri.parse(snapshot.uri),
                path = path,
                addedMs = SnapshotAddedMs(snapshot.addedMs),
                modifiedMs = snapshot.modifiedMs,
                mimeType = snapshot.mimeType,
                size = snapshot.size,
                // parent is only consumed during the explore step, which the snapshot path skips
                // entirely. Downstream (extract/evaluate) never dereferences it for a RawSong.
                parent = null)

        val properties =
            Properties(
                snapshot.mimeType,
                snapshot.durationMs,
                snapshot.bitrateKbps,
                snapshot.sampleRateHz)

        val tags =
            ParsedTags(
                durationMs = snapshot.durationMs,
                replayGainTrackAdjustment = snapshot.replayGainTrackAdjustment,
                replayGainAlbumAdjustment = snapshot.replayGainAlbumAdjustment,
                musicBrainzId = snapshot.musicBrainzId,
                name = snapshot.name,
                sortName = snapshot.sortName,
                track = snapshot.track,
                disc = snapshot.disc,
                subtitle = snapshot.subtitle,
                date = snapshot.date?.let { org.oxycblt.musikr.tag.Date.from(it) },
                albumMusicBrainzId = snapshot.albumMusicBrainzId,
                albumName = snapshot.albumName,
                albumSortName = snapshot.albumSortName,
                releaseTypes = snapshot.releaseTypes,
                artistMusicBrainzIds = snapshot.artistMusicBrainzIds,
                artistNames = snapshot.artistNames,
                artistSortNames = snapshot.artistSortNames,
                albumArtistMusicBrainzIds = snapshot.albumArtistMusicBrainzIds,
                albumArtistNames = snapshot.albumArtistNames,
                albumArtistSortNames = snapshot.albumArtistSortNames,
                genreNames = snapshot.genreNames)

        // Re-obtain the cover from the persistent, revisioned cover store by id. A Miss here means
        // the cover store no longer has that cover (revision drifted) - we treat the song as
        // cover-less rather than failing, matching how the normal explore step degrades.
        val cover: Cover? =
            snapshot.coverId?.let { id ->
                when (val result = storage.covers.obtain(id)) {
                    is CoverResult.Hit -> result.cover
                    else -> null
                }
            }

        return RawSong(file, properties, tags, cover, snapshot.addedMs)
    }
}

/** A trivial [org.oxycblt.musikr.fs.AddedMs] that yields a value already known from the snapshot. */
private class SnapshotAddedMs(private val value: Long) : org.oxycblt.musikr.fs.AddedMs {
    override suspend fun resolve(): Long = value
}

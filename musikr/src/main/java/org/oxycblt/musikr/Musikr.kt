/*
 * Copyright (c) 2024 Auxio Project
 * Musikr.kt is part of Auxio.
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
 
package org.oxycblt.musikr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import org.oxycblt.musikr.pipeline.EvaluateStep
import org.oxycblt.musikr.pipeline.ExploreStep
import org.oxycblt.musikr.pipeline.Explored
import org.oxycblt.musikr.pipeline.ExtractStep
import org.oxycblt.musikr.pipeline.Extracted
import org.oxycblt.musikr.pipeline.RawSong
import org.oxycblt.musikr.snapshot.LibrarySnapshot
import org.oxycblt.musikr.snapshot.SnapshotConverter
import org.oxycblt.musikr.snapshot.SnapshotPlaylist
import org.oxycblt.musikr.snapshot.SnapshotStore
import org.oxycblt.musikr.util.merge
import org.oxycblt.musikr.util.tryAsyncWith

/**
 * A highly opinionated, multi-threaded device music library.
 *
 * Use this to load music with [run].
 *
 * Note the following:
 * 1. Musikr's API surface is intended to be primarily "stateless", with side-effects mostly
 *    contained within [Storage]. It's your job to manage long-term state.
 * 2. There are no "defaults" in Musikr. You should think carefully about the parameters you are
 *    specifying and know consider they are desirable or not.
 * 3. Musikr is currently not extendable, so if you're embedding this elsewhere you should be ready
 *    to fork and modify the source code.
 */
interface Musikr {
    /**
     * Start loading music using the given config and the configuration provided earlier.
     *
     * @param onProgress Optional callback to receive progress on the current status of the music
     *   pipeline. Warning: These events will be rapid-fire.
     * @return A handle to the newly created library alongside further cleanup.
     */
    suspend fun run(onProgress: suspend (IndexingProgress) -> Unit = {}): LibraryResult

    /**
     * Attempt to load the library from a previously-written on-disk snapshot, WITHOUT scanning the
     * filesystem or re-parsing any tags.
     *
     * This is the fast path for app startup (including cold starts after the OS kills the process):
     * it reads the flat snapshot file from the cache directory, reconstructs the raw song inputs,
     * re-obtains covers by id from the persistent cover store, and rebuilds the library graph via
     * the same evaluation path a real scan uses.
     *
     * It FAILS CLOSED: if there's no snapshot, it's corrupt, it's from an incompatible version, its
     * recorded revision doesn't match [expectedRevision], or any song can't be safely rebuilt, this
     * returns null and the caller should fall back to [run].
     *
     * A successful snapshot load does NOT itself write anything to disk, so ordinary app reopens
     * with an unchanged library perform zero writes.
     *
     * @param expectedRevision the cover-store revision the caller currently considers valid, used to
     *   reject a stale snapshot. Pass null to skip this check.
     * @return a [LibraryResult], or null if the snapshot couldn't be used.
     */
    suspend fun loadSnapshot(expectedRevision: String?): LibraryResult?

    companion object {
        /**
         * Create a new instance from the given configuration.
         *
         * @param context The context to use for loading resources.
         * @param config Side-effect laden storage for use within the music loader **and** when
         *   mutating [MutableLibrary]. You should take responsibility for managing their long-term
         *   state.
         * @param interpretation The configuration to use for interpreting certain vague tags. This
         *   should be configured by the user, if possible.
         */
        fun new(context: Context, config: Config): Musikr =
            MusikrImpl(
                config,
                ExploreStep.from(context, config),
                ExtractStep.from(context, config),
                EvaluateStep.new(context, config, config.interpretation),
                SnapshotStore.from(context),
                SnapshotConverter(context))
    }
}

/** Simple library handle returned by [Musikr.run] and [Musikr.loadSnapshot]. */
interface LibraryResult {
    val library: MutableLibrary

    /**
     * Clean up expired resources. This should be done as soon as possible after music loading to
     * reduce storage use.
     *
     * This may have unexpected results if previous [Library]s are in circulation across your app,
     * so use it once you've fully updated your state.
     */
    suspend fun cleanup()

    /**
     * Persist a startup snapshot of this library to disk for fast future loads via
     * [Musikr.loadSnapshot].
     *
     * Callers should invoke this ONLY when the library actually changed (i.e. after a real scan that
     * produced different content), to minimize disk writes and storage wear. Calling it on a result
     * that came from [Musikr.loadSnapshot] is a safe no-op, since the on-disk snapshot is already
     * current.
     *
     * @param revision the current cover-store revision to stamp into the snapshot, so a future load
     *   can detect if the snapshot has gone stale relative to the covers on disk.
     */
    suspend fun persistSnapshot(revision: String?)
}

/** Music loading progress as reported by the music pipeline. */
sealed interface IndexingProgress {
    /**
     * Currently indexing and extracting tags from device music.
     *
     * @param explored The amount of music currently found from the given [Query].
     * @param loaded The amount of music that has had metadata extracted and parsed.
     */
    data class Songs(val loaded: Int, val explored: Int) : IndexingProgress

    /**
     * Currently creating the music graph alongside I/O finalization.
     *
     * There is no way to measure progress on these events.
     */
    data object Indeterminate : IndexingProgress
}

private class MusikrImpl(
    private val config: Config,
    private val exploreStep: ExploreStep,
    private val extractStep: ExtractStep,
    private val evaluateStep: EvaluateStep,
    private val snapshotStore: SnapshotStore,
    private val snapshotConverter: SnapshotConverter
) : Musikr {
    override suspend fun run(onProgress: suspend (IndexingProgress) -> Unit) = coroutineScope {
        onProgress(IndexingProgress.Songs(0, 0))
        val start = System.currentTimeMillis()
        var explored = 0
        var loaded = 0
        val exploredChannel = Channel<Explored>(Channel.UNLIMITED)
        val exploredTask = exploreStep.explore(this, exploredChannel)
        val trackedExploredChannel = Channel<Explored>(Channel.UNLIMITED)
        val trackedExploredTask =
            tryAsyncWith(trackedExploredChannel, Dispatchers.Main) {
                for (item in exploredChannel) {
                    explored++
                    onProgress(IndexingProgress.Songs(loaded, explored))
                    trackedExploredChannel.send(item)
                }
            }
        val extractedChannel = Channel<Extracted>(Channel.UNLIMITED)
        val extractedTask = extractStep.extract(this, trackedExploredChannel, extractedChannel)
        val trackedExtractedChannel = Channel<Extracted>(Channel.UNLIMITED)
        val trackedExtractedTask =
            tryAsyncWith(trackedExtractedChannel, Dispatchers.Main) {
                for (item in extractedChannel) {
                    loaded++
                    onProgress(IndexingProgress.Songs(loaded, explored))
                    trackedExtractedChannel.send(item)
                }
                onProgress(IndexingProgress.Indeterminate)
            }
        // Capture the raw songs as they flow through evaluation so we can persist a snapshot
        // afterward for fast startup, without a second pass over the library.
        val capturedRawSongs = mutableListOf<RawSong>()
        val library =
            evaluateStep.evaluate(trackedExtractedChannel) { rawSong ->
                capturedRawSongs.add(rawSong)
            }
        merge(exploredTask, extractedTask, trackedExploredTask, trackedExtractedTask).await()
        Log.d("Musikr", "Indexing took ${System.currentTimeMillis() - start}ms")
        LibraryResultImpl(config, library, snapshotStore, snapshotConverter, capturedRawSongs)
    }

    override suspend fun loadSnapshot(expectedRevision: String?): LibraryResult? = coroutineScope {
        val start = System.currentTimeMillis()
        val snapshot = snapshotStore.read() ?: return@coroutineScope null

        // Reject a snapshot whose recorded revision no longer matches what the caller expects; the
        // covers it references may have been cleaned up under a newer revision.
        if (expectedRevision != null && snapshot.revision != expectedRevision) {
            Log.d(
                "Musikr",
                "Snapshot revision ${snapshot.revision} != expected $expectedRevision, ignoring")
            return@coroutineScope null
        }

        val rawSongsOrNull =
            snapshot.songs
                .map { snapshotSong ->
                    async(Dispatchers.IO) { snapshotConverter.toRawSong(snapshotSong, config.storage) }
                }
                .awaitAll()

        if (rawSongsOrNull.any { it == null }) {
            // A song couldn't be safely rebuilt (e.g. its volume is gone). Abandon the whole
            // snapshot rather than present a partial library.
            Log.w("Musikr", "Snapshot song could not be reconstructed, ignoring snapshot")
            return@coroutineScope null
        }
        @Suppress("UNCHECKED_CAST") val rawSongs = rawSongsOrNull as List<RawSong>

        val library = evaluateStep.evaluateFromRaw(rawSongs)
        Log.d(
            "Musikr",
            "Loaded library from snapshot (${rawSongs.size} songs) in " +
                "${System.currentTimeMillis() - start}ms")
        // A snapshot load reflects on-disk state as-is; nothing new to persist, so pass null raw
        // songs to indicate "no snapshot rewrite needed".
        LibraryResultImpl(config, library, snapshotStore, snapshotConverter, null)
    }
}

private class LibraryResultImpl(
    private val config: Config,
    override val library: MutableLibrary,
    private val snapshotStore: SnapshotStore,
    private val snapshotConverter: SnapshotConverter,
    private val capturedRawSongs: List<RawSong>?
) : LibraryResult {
    override suspend fun cleanup() {
        config.storage.covers.cleanup(library.songs.mapNotNull { it.cover })
    }

    override suspend fun persistSnapshot(revision: String?) {
        val raw = capturedRawSongs
        if (raw == null) {
            // This result came from a snapshot load; the on-disk snapshot is already current.
            return
        }
        val snapshot =
            LibrarySnapshot(
                version = LibrarySnapshot.CURRENT_VERSION,
                revision = revision,
                songs = raw.map { snapshotConverter.toSnapshotSong(it) },
                playlists = library.playlists.map { SnapshotPlaylist(it.name.raw) })
        snapshotStore.write(snapshot)
    }
}

/*
 * Copyright (c) 2024 Auxio Project
 * EvaluateStep.kt is part of Auxio.
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
 
package org.oxycblt.musikr.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import org.oxycblt.musikr.BuildConfig
import org.oxycblt.musikr.Config
import org.oxycblt.musikr.Interpretation
import org.oxycblt.musikr.MutableLibrary
import org.oxycblt.musikr.graph.MusicGraph
import org.oxycblt.musikr.model.LibraryFactory
import org.oxycblt.musikr.playlist.db.StoredPlaylists
import org.oxycblt.musikr.playlist.interpret.PlaylistInterpreter
import org.oxycblt.musikr.tag.interpret.TagInterpreter

internal interface EvaluateStep {
    /**
     * Evaluate music coming off the extract pipeline into a full library.
     *
     * @param extractedMusic the channel of extracted items.
     * @param onRawSong optional callback invoked for every valid [RawSong] as it's consumed. Used
     *   to capture a snapshot of the raw inputs without a second pass. Playlists are intentionally
     *   not captured here because they're already persisted separately by StoredPlaylists.
     */
    suspend fun evaluate(
        extractedMusic: Channel<Extracted>,
        onRawSong: (suspend (RawSong) -> Unit)? = null
    ): MutableLibrary

    /**
     * Evaluate a pre-reconstructed list of [RawSong]s (from a snapshot) into a full library,
     * bypassing the explore + extract pipeline entirely. Playlists are re-read from StoredPlaylists,
     * matching the normal path, since they're cheap and persisted independently.
     */
    suspend fun evaluateFromRaw(rawSongs: List<RawSong>): MutableLibrary

    companion object {
        fun new(context: Context, config: Config, interpretation: Interpretation): EvaluateStep =
            EvaluateStepImpl(
                context,
                TagInterpreter.new(interpretation),
                PlaylistInterpreter.new(interpretation),
                config.storage.storedPlaylists,
                LibraryFactory.new())
    }
}

private class EvaluateStepImpl(
    private val context: Context,
    private val tagInterpreter: TagInterpreter,
    private val playlistInterpreter: PlaylistInterpreter,
    private val storedPlaylists: StoredPlaylists,
    private val libraryFactory: LibraryFactory
) : EvaluateStep {
    override suspend fun evaluate(
        extractedMusic: Channel<Extracted>,
        onRawSong: (suspend (RawSong) -> Unit)?
    ): MutableLibrary {
        val builder = MusicGraph.builder()
        for (extracted in extractedMusic) {
            when (extracted) {
                is RawSong -> {
                    builder.add(tagInterpreter.interpret(extracted))
                    onRawSong?.invoke(extracted)
                }
                is RawPlaylist -> builder.add(playlistInterpreter.interpret(extracted.file))
                is InvalidSong -> {}
            }
            builder
        }
        val graph = builder.build()

        // Render graph to Graphviz in debug mode
        if (BuildConfig.DEBUG) {
            try {
                val fileName = "music_graph_debug.dot"
                graph.renderToGraphviz(context, fileName)
                val filePath = context.filesDir.resolve(fileName).absolutePath
                Log.d("EvaluateStep", "Music graph rendered to: $filePath")
                Log.d("EvaluateStep", "To pull the file, run: adb pull $filePath")
            } catch (e: Exception) {
                Log.e("EvaluateStep", "Failed to render music graph", e)
            }
        }

        return libraryFactory.create(graph, storedPlaylists, playlistInterpreter)
    }

    override suspend fun evaluateFromRaw(rawSongs: List<RawSong>): MutableLibrary =
        coroutineScope {
            // Tag interpretation (particularly name tokenization for sorting) is real CPU work
            // per song - profiling flagged it as a significant chunk of normal song-building
            // time (see IntelligentKnownName.parseTokens). Running it sequentially for a whole
            // snapshot-loaded library (hundreds of songs) adds up to a very visible delay before
            // anything can be shown. tagInterpreter.interpret() is a pure function of its input
            // (no shared mutable state), so it's safe to fan out across a worker pool - this
            // mirrors how ExtractStep already parallelizes per-file tag extraction during a
            // normal scan.
            val preSongs =
                rawSongs
                    .map { rawSong -> async(Dispatchers.Default) { tagInterpreter.interpret(rawSong) } }
                    .awaitAll()

            // Building the graph itself is cheap (map insertions) - keep it single-threaded and
            // sequential, since MusicGraph.Builder is not safe for concurrent mutation.
            val builder = MusicGraph.builder()
            for (preSong in preSongs) {
                builder.add(preSong)
            }
            // Playlists are persisted independently and cheap to read; pull them the same way the
            // normal explore step does so snapshot-loaded libraries still include user playlists.
            for (playlist in storedPlaylists.read()) {
                builder.add(playlistInterpreter.interpret(playlist))
            }
            val graph = builder.build()
            libraryFactory.create(graph, storedPlaylists, playlistInterpreter)
        }
}

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
import kotlinx.coroutines.channels.Channel
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

    override suspend fun evaluateFromRaw(rawSongs: List<RawSong>): MutableLibrary {
        val builder = MusicGraph.builder()
        for (rawSong in rawSongs) {
            builder.add(tagInterpreter.interpret(rawSong))
        }
        // Playlists are persisted independently and cheap to read; pull them the same way the
        // normal explore step does so snapshot-loaded libraries still include user playlists.
        for (playlist in storedPlaylists.read()) {
            builder.add(playlistInterpreter.interpret(playlist))
        }
        val graph = builder.build()
        return libraryFactory.create(graph, storedPlaylists, playlistInterpreter)
    }
}

/*
 * Copyright (c) 2026 Auxio Project
 * SongDeleter.kt is part of Auxio.
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
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Deletes a song's underlying file, covering both of Auxio's location modes:
 * - SAF document URIs: deleted directly via [DocumentsContract.deleteDocument]
 *   (Auxio takes persistable write permission on its music locations).
 * - MediaStore URIs on API 29+: the system requires per-item user consent, so
 *   deletion is a two-step flow — this returns a [PendingIntent] the UI must
 *   launch; the system shows its own confirmation, then performs the delete.
 */
interface SongDeleter {
    sealed interface Result {
        /** File is gone. Trigger a library refresh. */
        data object Deleted : Result

        /** System requires user consent; launch [pendingIntent] via the Activity Result API. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : Result

        /** Deletion failed (no permission, missing file, etc.). */
        data object Failed : Result
    }

    suspend fun delete(song: Song): Result
}

class SongDeleterImpl
@Inject
constructor(@ApplicationContext private val context: Context) : SongDeleter {

    override suspend fun delete(song: Song): SongDeleter.Result =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            try {
                if (DocumentsContract.isDocumentUri(context, song.uri)) {
                    val ok = DocumentsContract.deleteDocument(resolver, song.uri)
                    return@withContext if (ok) SongDeleter.Result.Deleted
                    else SongDeleter.Result.Failed
                }

                // MediaStore URI path
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Batch consent API: system dialog, then system deletes.
                    val pi = MediaStore.createDeleteRequest(resolver, listOf(song.uri))
                    return@withContext SongDeleter.Result.NeedsConsent(pi)
                }

                // Pre-R: direct delete may work if we hold write permission.
                val rows = resolver.delete(song.uri, null, null)
                if (rows > 0) SongDeleter.Result.Deleted else SongDeleter.Result.Failed
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // RecoverableSecurityException path on Q
                    val recoverable =
                        e as? android.app.RecoverableSecurityException
                    if (recoverable != null) {
                        return@withContext SongDeleter.Result.NeedsConsent(
                            recoverable.userAction.actionIntent)
                    }
                }
                L.e("No permission to delete ${song.uri}: $e")
                SongDeleter.Result.Failed
            } catch (e: Exception) {
                L.e("Failed to delete ${song.uri}: $e")
                SongDeleter.Result.Failed
            }
        }
}

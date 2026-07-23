/*
 * Copyright (c) 2026 Auxio Project
 * BackupCoordinator.kt is part of Auxio.
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

package org.oxycblt.auxio.backup

import android.content.Context
import android.os.Build
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.oxycblt.auxio.BuildConfig
import timber.log.Timber as L

/**
 * Top-level manifest stored as `manifest.json` at the root of every backup
 * archive. This is the ONLY thing the importer trusts blindly; everything
 * else is looked up by the module ids listed here, so unrecognized entries
 * (from a newer app version) are simply not iterated and never crash an
 * older build.
 */
private const val MANIFEST_ENTRY = "manifest.json"
private const val MANIFEST_FORMAT_VERSION = 1

/** One module's entry inside `manifest.json`. */
data class ManifestModuleEntry(val id: String, val schemaVersion: Int, val displayName: String)

/** Parsed `manifest.json`. */
data class BackupManifest(
    val formatVersion: Int,
    val appVersionName: String,
    val exportedAtMs: Long,
    val deviceLabel: String,
    val modules: List<ManifestModuleEntry>
)

/** Per-module outcome shown in the post-import summary. */
sealed interface ModuleImportOutcome {
    val moduleId: String
    val displayName: String

    data class Applied(
        override val moduleId: String,
        override val displayName: String,
        val result: MergeResult
    ) : ModuleImportOutcome

    /** Backup predates this module, or the exporting device had no data for it. */
    data class NotPresent(override val moduleId: String, override val displayName: String) :
        ModuleImportOutcome

    /**
     * This exact backup was already imported on this device, so it was
     * skipped to avoid double-counting additive data (see the idempotency
     * guard in [BackupCoordinator]).
     */
    data class AlreadyApplied(override val moduleId: String, override val displayName: String) :
        ModuleImportOutcome

    /**
     * The backup contains a module id this build doesn't know, or knows at a
     * newer [schemaVersion] than it supports. Nothing for this module was
     * touched; every other module still imported normally.
     */
    data class SkippedUnsupported(
        override val moduleId: String,
        override val displayName: String,
        val schemaVersion: Int
    ) : ModuleImportOutcome
}

/** Full result of a dry-run or real import pass across every module. */
data class ImportPlan(
    val manifest: BackupManifest,
    val outcomes: List<ModuleImportOutcome>,
    val pendingConflicts: List<Conflict>
) {
    val hasConflicts: Boolean
        get() = pendingConflicts.isNotEmpty()
}

/**
 * Orchestrates export and import across every registered [BackupModule].
 * This class knows nothing about Smart Chain, Zone Axis, or any other
 * specific feature — it only knows the [BackupModule] contract, which is
 * what lets new features plug in later without touching this file.
 */
@Singleton
class BackupCoordinator
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val modules: Set<@JvmSuppressWildcards BackupModule>
) {
    /** Modules that currently have something worth exporting, for the picker UI. */
    suspend fun modulesWithData(): List<BackupModule> = modules.filter { it.hasData() }

    /** Writes a backup archive containing every module in [selected] to [out]. */
    suspend fun export(out: OutputStream, selected: Collection<BackupModule> = modules) {
        withContext(Dispatchers.IO) {
            ZipOutputStream(out).use { zip ->
                val entries = mutableListOf<ManifestModuleEntry>()
                for (module in selected) {
                    if (!module.hasData()) continue
                    val payload = module.export()
                    zip.putNextEntry(ZipEntry(moduleEntryName(module.id)))
                    zip.write(payload.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    entries.add(ManifestModuleEntry(module.id, module.schemaVersion, module.displayName))
                }
                val manifest =
                    JSONObject().apply {
                        put("formatVersion", MANIFEST_FORMAT_VERSION)
                        put("appVersionName", BuildConfig.VERSION_NAME)
                        put("exportedAtMs", System.currentTimeMillis())
                        put("deviceLabel", deviceLabel())
                        put(
                            "modules",
                            org.json.JSONArray().apply {
                                entries.forEach {
                                    put(
                                        JSONObject().apply {
                                            put("id", it.id)
                                            put("schemaVersion", it.schemaVersion)
                                            put("displayName", it.displayName)
                                        })
                                }
                            })
                    }
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // Modules that need to write raw files alongside their JSON
                // (e.g. the fingerprint cache's database file) get a
                // dedicated sub-directory named after their id, so nothing
                // collides and unknown-module cleanup during import is just
                // "skip anything under an id we didn't list".
                for (module in selected) {
                    if (module is RawFileBackupModule) {
                        module.exportRawFiles { name, input ->
                            zip.putNextEntry(ZipEntry(rawFileEntryName(module.id, name)))
                            input.copyTo(zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads a backup archive from [input] into a temp working directory and
     * returns a dry-run [ImportPlan] — no data is modified yet. Call
     * [applyPlan] afterward with the same file plus any conflict
     * resolutions to actually write the merged data.
     */
    suspend fun dryRun(input: InputStream): ImportPlan = runImport(input, apply = false, resolutions = emptyMap())

    /** Re-reads [input] and actually writes merged data, using [resolutions] for conflicts. */
    suspend fun applyPlan(
        input: InputStream,
        resolutions: Map<String, ConflictResolution>
    ): ImportPlan = runImport(input, apply = true, resolutions = resolutions)

    private suspend fun runImport(
        input: InputStream,
        apply: Boolean,
        resolutions: Map<String, ConflictResolution>
    ): ImportPlan =
        withContext(Dispatchers.IO) {
            val workDir = File(context.cacheDir, "backup_import_${System.nanoTime()}")
            workDir.mkdirs()
            try {
                val payloads = mutableMapOf<String, ByteArray>()
                val rawFiles = mutableMapOf<String, MutableMap<String, File>>()
                var manifestJson: JSONObject? = null

                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == MANIFEST_ENTRY -> {
                                manifestJson = JSONObject(String(zip.readBytes(), Charsets.UTF_8))
                            }
                            name.startsWith(RAW_PREFIX) -> {
                                val rest = name.removePrefix(RAW_PREFIX)
                                val sep = rest.indexOf('/')
                                if (sep > 0) {
                                    val moduleId = rest.substring(0, sep)
                                    val fileName = rest.substring(sep + 1)
                                    val dest = File(workDir, "$moduleId/$fileName")
                                    dest.parentFile?.mkdirs()
                                    dest.outputStream().use { fos -> zip.copyTo(fos) }
                                    rawFiles.getOrPut(moduleId) { mutableMapOf() }[fileName] = dest
                                }
                            }
                            name.endsWith(".json") -> {
                                val moduleId = name.removeSuffix(".json")
                                payloads[moduleId] = zip.readBytes()
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }

                val manifestObj =
                    manifestJson ?: throw BackupFormatException("Not a valid Auxio backup: missing manifest")
                val manifest = parseManifest(manifestObj)

                // Idempotency guard. Counter-summing merges (transitions,
                // quality) are NOT idempotent, so importing the exact same
                // backup twice would double-count. We fingerprint the backup
                // (its manifest + every module payload) and, on a real apply,
                // refuse to re-apply one we've already applied. Dry runs are
                // always allowed (they change nothing) so the user can still
                // preview. This makes "import the same file again" a safe
                // no-op instead of silently inflating counts.
                val fingerprint = computeFingerprint(manifestObj, payloads)
                val alreadyApplied = appliedLedger().contains(fingerprint)

                val outcomes = mutableListOf<ModuleImportOutcome>()
                val conflicts = mutableListOf<Conflict>()
                val moduleById = modules.associateBy { it.id }

                if (apply && alreadyApplied) {
                    // Report every module as "nothing to do" and skip writing.
                    for (entry in manifest.modules) {
                        outcomes.add(ModuleImportOutcome.AlreadyApplied(entry.id, entry.displayName))
                    }
                    return@withContext ImportPlan(manifest, outcomes, emptyList())
                }

                for (entry in manifest.modules) {
                    val module = moduleById[entry.id]
                    if (module == null || entry.schemaVersion > module.schemaVersion) {
                        outcomes.add(
                            ModuleImportOutcome.SkippedUnsupported(
                                entry.id, entry.displayName, entry.schemaVersion))
                        continue
                    }
                    val rawBytes = payloads[entry.id]
                    if (rawBytes == null) {
                        outcomes.add(ModuleImportOutcome.NotPresent(entry.id, entry.displayName))
                        continue
                    }
                    if (module is RawFileBackupModule) {
                        module.stageRawFiles(rawFiles[entry.id].orEmpty())
                    }
                    val payload = JSONObject(String(rawBytes, Charsets.UTF_8))
                    val result =
                        try {
                            module.merge(payload, entry.schemaVersion, resolutions, apply)
                        } catch (e: Exception) {
                            L.e("Module ${entry.id} failed to merge: $e")
                            MergeResult()
                        }
                    conflicts.addAll(result.conflicts)
                    outcomes.add(ModuleImportOutcome.Applied(entry.id, entry.displayName, result))
                }

                // Record the fingerprint only once the real apply has fully
                // committed (i.e. no unresolved conflicts remain — a partial
                // apply blocked on conflicts must remain re-appliable).
                if (apply && conflicts.isEmpty()) {
                    appliedLedger().edit().putBoolean(fingerprint, true).apply()
                }

                ImportPlan(manifest, outcomes, conflicts)
            } finally {
                workDir.deleteRecursively()
            }
        }

    private fun parseManifest(json: JSONObject): BackupManifest {
        val modulesArr = json.optJSONArray("modules") ?: org.json.JSONArray()
        val entries =
            (0 until modulesArr.length()).map { i ->
                val m = modulesArr.getJSONObject(i)
                ManifestModuleEntry(
                    m.getString("id"), m.getInt("schemaVersion"), m.optString("displayName", m.getString("id")))
            }
        return BackupManifest(
            formatVersion = json.optInt("formatVersion", 1),
            appVersionName = json.optString("appVersionName", "unknown"),
            exportedAtMs = json.optLong("exportedAtMs", 0L),
            deviceLabel = json.optString("deviceLabel", "unknown device"),
            modules = entries)
    }

    private fun deviceLabel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Unknown device" }

    /**
     * A stable content fingerprint of a backup: SHA-256 over the manifest
     * plus every module payload, in sorted id order (so entry ordering in
     * the zip doesn't change the result). Used only for the "already
     * applied" idempotency guard.
     */
    private fun computeFingerprint(manifest: JSONObject, payloads: Map<String, ByteArray>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(manifest.toString().toByteArray(Charsets.UTF_8))
        payloads.keys.sorted().forEach { key ->
            digest.update(key.toByteArray(Charsets.UTF_8))
            digest.update(payloads.getValue(key))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun appliedLedger() =
        context.getSharedPreferences(APPLIED_LEDGER_PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val RAW_PREFIX = "raw/"
        private const val APPLIED_LEDGER_PREFS = "backup_applied_ledger"

        private fun moduleEntryName(moduleId: String) = "$moduleId.json"

        private fun rawFileEntryName(moduleId: String, fileName: String) = "$RAW_PREFIX$moduleId/$fileName"
    }
}

/** Thrown when an archive isn't a recognizable Auxio backup. */
class BackupFormatException(message: String) : Exception(message)

/**
 * Optional extra capability for a [BackupModule] that needs to ship raw
 * files (e.g. a copy of a SQLite database) alongside its JSON payload,
 * instead of encoding everything as JSON. Used by the fingerprint cache,
 * whose data is bulky binary blobs with no user-authored meaning.
 */
interface RawFileBackupModule {
    /** The stable module [BackupModule.id] this belongs to. */
    val id: String

    /** Write each raw file this module wants included, via [write]. */
    suspend fun exportRawFiles(write: suspend (name: String, input: InputStream) -> Unit)

    /**
     * Called before [BackupModule.merge] with the raw files this module
     * exported previously, extracted to disk for this import (keyed by the
     * same [name] used in [exportRawFiles]). Files are deleted after import
     * finishes; copy anything that needs to persist.
     */
    suspend fun stageRawFiles(files: Map<String, File>)
}

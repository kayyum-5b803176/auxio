/*
 * Copyright (c) 2026 Auxio Project
 * BackupModule.kt is part of Auxio.
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

import org.json.JSONObject

/**
 * A single self-contained slice of the app's data that can be backed up.
 *
 * This is the extension point that makes the backup system future-proof:
 * every feature (Smart Chain, Zone Axis, a hypothetical future feature) is
 * one [BackupModule] that knows how to serialize and merge ONLY its own
 * data. [BackupCoordinator] never hard-codes what data exists — it just
 * asks every registered module in turn. Adding a brand-new feature later is
 * "write a new BackupModule and add it to the Hilt multibinding set"; the
 * zip format, the export/import screen, and the conflict-resolution engine
 * do not change.
 *
 * ## Versioning
 * Each module carries a small integer [schemaVersion]. This is NOT the Room
 * database version — it is a version for the JSON shape this module reads
 * and writes in a backup file. A module must be able to read every
 * [schemaVersion] it has ever shipped (typically by "upgrading" older JSON
 * shapes in memory before merging), so a backup made by an old build always
 * imports cleanly into a newer one. Bump [schemaVersion] only when the JSON
 * *shape* changes, not on every unrelated app update.
 *
 * ## Forward-compatibility (newer backup -> older app)
 * If a backup contains a module [id] the running app doesn't recognize (a
 * newer feature this build predates), or a known module with a
 * [schemaVersion] higher than this build supports, [BackupCoordinator] skips
 * it and reports it to the user as "skipped: not supported by this version"
 * rather than failing the whole import. Nothing else in the backup is
 * affected.
 */
interface BackupModule {
    /**
     * Stable, unique identifier for this module, e.g. "smart_chain". Never
     * renamed once shipped — it is the key used to find this module's data
     * inside a backup file, including backups written by older app versions.
     */
    val id: String

    /** Human-readable name shown in the export/import summary UI. */
    val displayName: String

    /** The schema version this build writes. See class doc. */
    val schemaVersion: Int

    /**
     * Whether this module currently has anything to export. Used only to
     * skip writing an empty section and to gray out the item in the export
     * picker; import must still work even if this returns false at export
     * time on the *importing* device.
     */
    suspend fun hasData(): Boolean

    /** Serialize this module's current data into a JSON payload. */
    suspend fun export(): JSONObject

    /**
     * Merge [incoming] data (from a backup, at [incomingSchemaVersion]) into
     * this device's current data. Implementations must:
     *  - never delete or overwrite existing data with "nothing" from the
     *    incoming side. Every value the destination device already had, and
     *    every value the incoming side had, must survive.
     *  - combine additively/numerically wherever a sound merge rule exists
     *    (sum counters, union sets/lists, keep-strongest, weighted-average
     *    vectors, etc).
     *  - only add an entry to [ConflictSet] when two sides disagree on a
     *    single logical value in a way that cannot be soundly combined (e.g.
     *    same tag slot assigned two different values). Report it in
     *    [MergeResult.conflicts] rather than guessing.
     *  - be idempotent: importing the same backup twice must not double-count
     *    (e.g. sum counters again). Rows are matched by stable content keys
     *    for this reason (see individual module implementations).
     *
     * @param incoming the module's payload as exported by [export] (any
     *   supported past [schemaVersion]) on the source device.
     * @param incomingSchemaVersion the schema version [incoming] was written
     *   with; implementations upgrade older shapes before merging.
     * @param resolutions user answers (from a previous dry run) to this
     *   module's conflicts, keyed by [Conflict.conflictKey]. Empty on the
     *   first (dry-run) pass.
     * @return a [MergeResult] describing what would happen (dry run) or what
     *   happened (real run — see [apply]).
     */
    suspend fun merge(
        incoming: JSONObject,
        incomingSchemaVersion: Int,
        resolutions: Map<String, ConflictResolution>,
        apply: Boolean
    ): MergeResult
}

/** One irreconcilable disagreement between destination and incoming data. */
data class Conflict(
    /** Stable key identifying this exact conflict, unique within the module. */
    val conflictKey: String,
    /** Which module raised this, for grouping in the review UI. */
    val moduleId: String,
    /** Human-readable description, e.g. "Zone value \"Hindi\" position differs". */
    val description: String,
    /** Human-readable current (destination) value. */
    val currentValue: String,
    /** Human-readable incoming (backup) value. */
    val incomingValue: String
)

/** The user's chosen resolution for one [Conflict]. */
enum class ConflictResolution {
    KEEP_CURRENT,
    USE_INCOMING
}

/** Outcome of a [BackupModule.merge] call, for either a dry run or a real apply. */
data class MergeResult(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val conflicts: List<Conflict> = emptyList()
)

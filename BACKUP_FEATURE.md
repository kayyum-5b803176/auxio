# Backup / Import-Export feature

**Version: 5.4.14 → 5.5.0** (minor bump: new feature, no breaking changes, versionCode left at 80)

A reliable, future-proof backup system for all app data: Smart Chain, Zone
Axis, acoustic-scan fingerprint cache, priority folders, plugin toggles, and
playlists. Import is a **merge, never a replace** — importing another
device's backup folds its data into this one without losing anything.

## How it works

- **`BackupModule`** is the extension point. Each feature implements one
  module that knows how to serialize (`export`) and combine (`merge`) only
  its own data. `BackupCoordinator` iterates every registered module — it
  never hard-codes what data exists.
- **Adding a new feature later** = write a new `BackupModule` + add one
  `@Binds @IntoSet` line in `BackupModuleBindings`. The zip format, the
  UI, the merge/conflict engine, and this doc's guarantees don't change.
- **Export** writes a `.auxbak` zip: `manifest.json` + one `<module>.json`
  per module (+ an optional `raw/<module>/…` area for any future module that
  needs raw files).
- **Import** is two-phase: a dry run detects conflicts without touching
  anything, then (after the user resolves any conflicts) a real apply.
  Unknown modules or modules with a newer schema than this build are
  **skipped, not fatal** — an older app can safely open a newer backup.

## Merge rules (per module)

| Data | Rule | Conflicts? |
|------|------|-----------|
| Smart Chain embeddings | weighted-average by observation count; counts sum | no |
| Smart Chain quality | sum every counter, add scores | no |
| Smart Chain lineage | keep strongest edge; newest breaks ties | no |
| Smart Chain transitions | sum plays/skips per (from,to) | no |
| Smart Chain logs | union, re-trim to capacity | no |
| Zone Axis values | union by (axis,label) case-insensitively | position differs → ask |
| Zone Axis tags | fill empty slots; keep both when same | tagged differently → ask |
| Zone Axis relations | union | differing value → ask |
| Fingerprint cache | keep newer by mtime / algorithm version | no |
| Priority folders | set union | no |
| Plugin toggles | OR-merge (on wins) | no |
| Playlists | match by name; append missing songs (UID→metadata) | no |

**Excluded by design** (device-local live dials, like playback state):
queue-order sliders, queue-order type/language filters.

## Idempotency

Counter-summing merges aren't idempotent, so importing the *same* backup
twice would double-count. The coordinator fingerprints each backup
(SHA-256 over manifest + payloads) and skips a real re-apply of one already
applied on this device. Dry runs are always allowed.

## Files added

All under `app/src/main/java/org/oxycblt/auxio/backup/`:
`BackupModule`, `BackupCoordinator`, `MergeUtil`, `BackupModuleBindings`,
`BackupViewModel`, `BackupFragment`, `ConflictResolutionDialog`, and
`modules/{SmartChain,ZoneAxis,PluginPreferences,FingerprintCache,Playlists}BackupModule`.

Resources: `res/xml/preferences_backup.xml`; new strings in
`res/values/strings.xml` and keys in `res/values/settings.xml`; nav entries
in `res/navigation/outer.xml`; root entry in `res/xml/preferences_root.xml`.

## Existing files modified

- `plugin/similarity/ChainDatabase.kt` — added `EmbeddingDao.any()` and
  `ChainLogDao.recentSnapshot()`.
- `plugin/similarity/ZoneAxisDatabase.kt` — added `ZoneAxisDao.allTags()`.
- `plugin/similarity/PluginSettings.kt` — `PluginSettingsImpl` now also
  implements `PluginSettingsWriter` (backup-import-only toggle setters).
- `settings/RootPreferenceFragment.kt` — navigate to the Backup screen.

## Note

Requires an Android build to compile (Room codegen, Hilt, Safe Args). It
was written against the project's existing conventions and verified for
API/signature correctness statically, but has not been compiled in this
environment.

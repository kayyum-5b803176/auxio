# v5.5.2 — Backup / Import-Export feature (full export)

Consolidated, final-state export of everything for the Backup feature —
use this if you're unsure which prior patch zips have been applied, or
want a single clean drop instead of applying v5.5.0 → v5.5.1 → v5.5.2
in sequence. This supersedes all three.

## Contents

**New files** (`app/src/main/java/org/oxycblt/auxio/backup/`):
- `BackupModule.kt` — the pluggable per-feature contract
- `BackupCoordinator.kt` — export/import orchestration, zip I/O, idempotency guard
- `MergeUtil.kt` — shared merge primitives
- `BackupModuleBindings.kt` — Hilt multibinding of all 5 modules
- `BackupViewModel.kt`, `BackupFragment.kt`, `ConflictResolutionDialog.kt` — UI
- `modules/SmartChainBackupModule.kt`
- `modules/ZoneAxisBackupModule.kt`
- `modules/PluginPreferencesBackupModule.kt`
- `modules/FingerprintCacheBackupModule.kt`
- `modules/PlaylistsBackupModule.kt`

**Modified files:**
- `app/build.gradle` — `versionName` → `5.5.2` (`versionCode` untouched at 80)
- `plugin/similarity/ChainDatabase.kt` — added `EmbeddingDao.any()`, `ChainLogDao.recentSnapshot()`
- `plugin/similarity/ZoneAxisDatabase.kt` — added `ZoneAxisDao.allTags()`
- `plugin/similarity/PluginSettings.kt` — `PluginSettingsImpl` implements the
  (also newly added, in this same file) `PluginSettingsWriter` interface
- `settings/RootPreferenceFragment.kt` — navigate to the Backup screen
- `res/xml/preferences_backup.xml` (new), `res/xml/preferences_root.xml`,
  `res/values/strings.xml`, `res/values/settings.xml`,
  `res/navigation/outer.xml`

## What was fixed along the way (already included here)

1. **`Unresolved reference 'raw'`** in `PlaylistsBackupModule.kt` —
   `Album.name`/`Artist.name` are the sealed `Name` type, not `Name.Known`;
   added a `rawNameOf()` helper that handles both variants.
2. **kapt failure: `BackupModule could not be resolved`** — caused by a
   two-way import cycle between `backup.modules` and `plugin.similarity`
   (`PluginSettingsWriter` was defined in `backup.modules` and imported
   back into `plugin.similarity`). Fixed by moving `PluginSettingsWriter`
   into `plugin/similarity/PluginSettings.kt`, so the dependency direction
   is one-way (`backup.modules` → `plugin.similarity`), matching every
   other module in the feature.

## Before building

If any of the earlier patch zips (v5.5.0, v5.5.1, v5.5.2) were partially
applied, **delete the existing files at the paths above first**, then
extract this zip over the project root, to guarantee no stale duplicate
or half-patched file is left behind. In particular double check:

- `app/src/main/java/org/oxycblt/auxio/plugin/similarity/PluginSettings.kt`
  should contain exactly ONE `interface PluginSettingsWriter { ... }`
  definition, and it must be in this file (not in
  `backup/modules/PluginPreferencesBackupModule.kt`).
- `app/src/main/java/org/oxycblt/auxio/backup/modules/PluginPreferencesBackupModule.kt`
  should `import org.oxycblt.auxio.plugin.similarity.PluginSettingsWriter`,
  not define it.

After extracting, a clean rebuild is recommended:
`./gradlew --stop && ./gradlew clean assembleDebug`
(a plain incremental build can sometimes keep stale kapt-generated stubs
around after a package-structure change like this one).

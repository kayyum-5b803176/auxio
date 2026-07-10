# Similarity Detection Plugin — implementation notes

## What was added to Auxio

A fully opt-in duplicate-song detector, reached via:
**Settings → Plugins → Similarity detection** (toggle, default OFF)
When ON: **Settings → Library → Find duplicates** appears.

The duplicates screen automatically fingerprints the library, groups files
that contain the same recording (even with different metadata, formats, or
bitrates), shows side-by-side quality metadata (format • bitrate • sample
rate • size • full path, with a "Highest quality" hint on the best copy),
and lets the user delete unwanted copies after a confirmation dialog. The
library refreshes automatically after each deletion.

## The "completely disabled" guarantee

When the toggle is off:
- The "Find duplicates" preference is invisible (`isVisible=false` in XML;
  only ever set true by `RootPreferenceFragment.onResume` reading the flag).
- That preference is the ONLY entry point to any plugin code. No background
  jobs are scheduled, no service hooks exist, nothing is initialized eagerly
  (all plugin classes are constructed lazily by Hilt only when the
  duplicates screen is opened).
- Zero changes to playback, indexing, or any other stock code path. The only
  modified existing file with logic changes is RootPreferenceFragment
  (navigation + visibility), plus resource files.

## Files added

| File | Role |
|---|---|
| `plugin/similarity/PluginSettings.kt` | Settings flag (Auxio Settings pattern) |
| `plugin/similarity/PluginModule.kt` | Hilt bindings |
| `plugin/similarity/AudioFingerprinter.kt` | MediaCodec decode → FFT → 15-bit/frame acoustic fingerprint |
| `plugin/similarity/DuplicateFinder.kt` | Duration prefilter → aligned Hamming similarity → union-find grouping |
| `plugin/similarity/SongDeleter.kt` | SAF `deleteDocument` + MediaStore consent-flow deletion |
| `plugin/similarity/DuplicatesViewModel.kt` | Scan orchestration (bounded concurrency), delete events |
| `plugin/similarity/DuplicatesFragment.kt` | Screen: progress → results → confirm-delete → consent launcher |
| `plugin/similarity/DuplicateGroupAdapter.kt` | Group cards with per-song metadata rows |
| `settings/categories/PluginPreferenceFragment.kt` | Plugins settings page |
| `res/xml/preferences_plugin.xml`, `res/layout/fragment_duplicates.xml`, `res/layout/item_duplicate_group.xml`, `res/layout/item_duplicate_song.xml` | UI |

Modified: `preferences_root.xml`, `navigation/outer.xml`, `values/settings.xml`,
`values/strings.xml`, `RootPreferenceFragment.kt`.

## How detection works (and its honest limits)

1. For each song, decode a 30s window (starting 25% into the track) to mono
   PCM via the platform `MediaCodec` — no NDK/native changes, works with any
   codec the device supports.
2. Compute log energies in 16 log-spaced frequency bands per ~46ms hop
   (4096-point FFT, Hann window), then quantize Haitsma–Kalker-style
   temporal gradients into 15 bits per frame.
3. Compare fingerprints of duration-matched songs (±4s) by bit-error-rate,
   searching ±4.6s of alignment offset; group pairs ≥85% similarity.

Design parameters (lag-4 gradient, hop=frame/8, 16 bands, 0.85 threshold)
were selected empirically: the FFT was verified against a reference DFT
(error ~1e-13), and quantization variants were benchmarked on synthetic
stress data where this configuration gave the widest same-recording vs.
different-recording separation (different recordings capped at ~0.54
similarity even under pathological conditions).

**Detects:** same recording as different encodes/bitrates/containers/tags.
**Does not detect (by design):** covers, remixes, live versions, different
masters — those are different recordings; matching them would create false
positives, which are unacceptable when the action is deletion.

**Untested caveats (I could not compile or run this in my environment):**
- The whole feature is untested on a real device — expect to iterate on the
  Kotlin (imports/generated Safe Args/binding names) during the first build.
- The 0.85 threshold is grounded in synthetic tests only; validate against a
  real library with known duplicates before trusting it, and consider
  logging similarity scores during your own testing.
- Scan speed: decoding 30s per track at 2-way concurrency is roughly 0.5-2s
  per song depending on device/codec — a 1,000-song library may take
  10-30 minutes on first scan. Results are not persisted between visits to
  the screen (deliberate v1 simplicity; a Room cache keyed by song UID +
  modifiedMs is the natural next step).

---

## Smart Chain — Phase 2 & 3: driving what plays next

Phase 1 learned the chain (song→song transitions weighted by how much of the
following song was heard). Phases 2 & 3 make that learning actually order
playback, via the player's shuffle order.

### How it hooks in
Auxio's ExoPlayer walks songs using a `BetterShuffleOrder`. When Smart Chain is
enabled, instead of a random shuffle order we compute a **chain-aware order**:
starting from the current song, greedily follow its strongest proven follower
that hasn't played yet; when a song has no unused proven follower, fall back to
a random unused song. The result is a full permutation, so every song still
plays exactly once — it's the *order* that's learned, not a filtered subset.

Because the order is handed to ExoPlayer up front, BOTH auto-advance and the
next/prev buttons follow it automatically — no per-song hooks, no surprises.

- **Phase 2 — exploit** (`explore = false`): always prefer proven followers;
  randomness only fills gaps. This is the "play what I usually play after this"
  behavior.
- **Phase 3 — explore** (`explore = true`, used in shuffle): mostly follows
  proven followers but with a 30% per-step chance of jumping to a random unused
  song, so new pairings keep getting discovered and learned.

### Where it applies
Chain ordering rides on the **shuffle** mechanism (shuffle toggle, "shuffle
all", and starting a shuffled play). This is deliberate: shuffle is where
reordering is expected. Non-shuffled linear play (e.g. "play this album in
order") is left exactly as stock — we do NOT scramble an intentional queue.
The exploit-only ordering (Phase 2) is implemented and available; it is applied
through shuffle rather than by silently reordering linear queues.

### Safety / fallback
- Fully gated by the Smart Chain toggle. When OFF, `shuffled()` and
  `newPlayback()` use the stock random `BetterShuffleOrder`, byte-for-byte
  unchanged.
- The chain computation (Room lookups, key resolution) runs OFF the main
  thread; the player is only touched back on the main thread. Playback starts
  immediately on a stock order and is re-ordered a moment later.
- Any failure or state change mid-computation falls back to the stock order, so
  shuffle can never break.

### Untested caveats (not compiled/run here)
- Room KSP, DI wiring for the new ChainRepository/PluginSettings deps threaded
  into ExoPlaybackStateHolder + its Factory.
- On-device: verify shuffle order visibly follows learned pairings once you've
  built up history; verify Smart-Chain-off leaves shuffle stock; verify no
  stutter at shuffle-enable on a large library (ordering is O(n) with cached
  follower lookups, but validate on your ~680-song library).

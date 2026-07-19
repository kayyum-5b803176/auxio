# Performance fixes (foreground CPU 99–201% -> expected near-idle)

## Round 3 — smooth AND cheap marquee

Round 2's 25fps scrollTo ticker had two flaws: Handler-timed ticks aren't
vsync-aligned (visible judder — the "struggling" text), and every tick still
re-recorded the full text draw, so scrolling stayed expensive.

`ThrottledMarqueeTextView` reworked: the full text line is rendered ONCE into
an offscreen bitmap when a scroll cycle starts; a vsync-driven ValueAnimator
then animates a float offset at the display's native rate and onDraw is a
single drawBitmap(). Motion is perfectly smooth (linear, vsync-aligned) while
per-frame cost on the main thread and RenderThread collapses to near-zero
(one blit instead of a glyph-run re-record + re-render). After 3 passes the
view goes fully idle and the bitmap is released. Still driven by isSelected,
so all existing gating is unchanged.

Reminder for measuring: ~20% of playback CPU on the profiled device is
software audio decoding (format has no HW decoder) — that floor is not
reachable by UI fixes — and debug builds (org.oxycblt.auxio.debug) roughly
double all rendering costs; profile the release build.

## Round 2 — driven by Perfetto traces (the definitive analysis)

Three traces (foreground+playing, background+playing, foreground+paused)
showed, over a 14s window on a 60Hz Mali device, debug build:

- foreground+paused: app essentially IDLE (~0% CPU) -> no rogue invalidation
  loop; the problem only exists while text is scrolling / playback animates.
- background+playing: ExoPlayer + MediaCodec + audio HAL ≈ 20% -> the
  background baseline is pure software audio decoding (the file format has no
  hardware decoder on this device). Not an app bug; unavoidable per format.
- foreground+playing: a frame EVERY vsync for the entire window. Per frame:
  main thread ~4.6ms + RenderThread ~6.3ms + Mali driver ~2.2ms ≈ 11ms of
  CPU per frame x 60fps ≈ 78% + ~21% decode = the observed ~99%.

Conclusion: the framework marquee renders at display vsync and its frame rate
is NOT configurable; on this device each frame is expensive (debug build makes
it worse), so ANY long-running framework marquee = ~80% CPU. Limiting repeats
(round 1) wasn't enough because one scroll pass of a long title lasts longer
than the whole measurement window.

Fix: `ThrottledMarqueeTextView` — a custom marquee that scrolls via scrollTo
on a SHARED ~25fps ticker (all labels step in one coalesced frame), with a
finite repeat count and full idle (zero redraws, END ellipsis) afterwards.
It's driven by isSelected exactly like the framework marquee, so all the
existing playing/visibility gating keeps working. Wired into
fragment_playback_bar.xml and all fragment_playback_panel.xml variants;
`ellipsize=marquee` removed from styles (now `end`).

Expected: rendering cost while a title scrolls drops ~2.5x (25fps vs 60) and
drops to ZERO between scroll cycles; on 120Hz devices the win is ~5x. Also:
profile a RELEASE build — the traces were from org.oxycblt.auxio.debug, which
runs unoptimized ART and roughly doubles per-frame cost.

Symptom: ~20% CPU in background, ~99% sustained in foreground with peaks to
~201% during playback; worse with long (marquee/scrolling) titles.

## Root causes found & fixed

1. **`android:layerType="hardware"` on marquee TextViews** (the #1 cause)
   - `res/layout/fragment_playback_bar.xml` (song + info), `res/layout/fragment_playback_panel.xml` (song)
   - A hardware layer must be fully re-rendered every time the view's content
     changes. A marquee changes content EVERY frame, so each of these views
     forced an extra GPU render pass per frame, per view, at the display
     refresh rate (60–120 Hz). Cost scales with text width — exactly why long
     titles used more CPU. Removed all three attributes.

2. **`marqueeRepeatLimit="marquee_forever"`** kept 2–3 marquees scrolling for
   the entire foreground session, so the app never reached an idle (zero
   redraw) state. Now `3` in `values/styles_ui.xml` (three styles) and inline
   in `fragment_playback_bar.xml`: the title scrolls fully three times, then
   rests until the next song / panel event. Companion change in
   `PlaybackBarFragment.updateSong()` / `PlaybackPanelFragment.updateSong()`:
   `isSelected` is re-toggled on song change so the limited marquee reliably
   restarts for each new track. (Revert: change `3` back to `marquee_forever`.)

3. **Per-frame `isEnabled` assignment** in `MainFragment.SheetBackPressedCallback
   .invalidateEnabled()` (called from `onPreDraw` every frame). AndroidX's
   setter fires its enabled-changed callback on every assignment even when the
   value is unchanged. Now assigns only on change.

4. **Playing-indicator frame animation at ~33 fps**
   (`drawable/ic_playing_indicator_24.xml`, 30 ms frames) redrew a vector
   continuously whenever a playing row was visible. Frames now 50 ms (~20 fps),
   ~40% fewer redraws, slightly slower wiggle.

5. **Position polling loop** (`PlaybackViewModel`) ticked at 100 ms even when
   the Smart Chain plugin (its only consumer of 100 ms resolution) is disabled,
   and keeps running while backgrounded. Now 100 ms only when Smart Chain is
   on, else 300 ms (UI already updated at ~3.3 Hz). New
   `PlaybackTracker.needsPositionTicks` exposes the requirement.

6. **Chain shuffle ordering did one Room query per song, twice**
   (`ChainRepository.chainOrdering`): `keyOf()` (fingerprint) and
   `frequencyOf()` (quality) ran sequentially per song — thousands of SQLite
   round-trips on a shuffle-all, a multi-second CPU spike (the ~201% peaks).
   Now batched via new `FingerprintDao.getAll(uids)` +
   `FingerprintRepository.getCachedBatch()` and
   `ZoneAxisRepository.frequencyByKeys()` (chunked IN queries).
   Additionally, `tagCloseness` and the final sort score are computed once per
   candidate instead of repeatedly inside sort comparators (previously
   O(n log n) re-evaluations including 24-dim cosines), and `ChainKey.of` uses
   a hex lookup table instead of one `String.format` per byte.

## Known remaining (by design)

- `AcousticFeatures.extract()` decodes + FFTs 30 s of audio the first time an
  unseeded song participates in a transition — a 1–2 s single-core burst at
  song change until the library is seeded. Running the Acoustic Scan once
  (Settings -> plugin) pre-seeds everything and removes these bursts.
- Debug builds plant Timber's DebugTree and run unminified; profile release
  builds for representative numbers.

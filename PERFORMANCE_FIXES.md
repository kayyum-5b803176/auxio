# Performance fixes (foreground CPU 99–201% -> expected near-idle)

## Round 6 — narrowing to the audio-processing path

top after round 5 showed decode CPU UNCHANGED (ExoPlayer:Playback ~13%,
MediaCodec_loop ~7.6%) -> the hw-decode reorder + buffer cap didn't move
steady state. User facts: files are MP3/AAC (cheap, hardware-decodable),
ReplayGain is ON, and it's high on RELEASE too. So ~20% is NOT the decode
(MediaCodec_loop is the hw decoder, working fine) and NOT a debug artifact.
The cost is on ExoPlayer:Playback, which runs the AudioProcessor chain + sink
write loop.

Fix applied: ReplayGainAudioProcessor.queueInput processed audio one sample at
a time using four bounds-checked ByteBuffer.get/put calls per sample (via
getLeShort/putLeShort). Rewritten to bulk ShortBuffer views (LITTLE_ENDIAN
forced to preserve exact output) — ~4x less per-sample overhead on the hot
audio thread, for every frame while a non-unity gain is applied.

OPEN QUESTION (needs a user measurement, not another patch): toggle ReplayGain
OFF and re-run top -H.
 - ExoPlayer:Playback drops to low single digits -> ReplayGain was the cost;
   this optimization targets it directly.
 - stays ~13% -> cost is in ExoPlayer's sink/buffering (framework-level); ~20%
   is then the practical floor for this device+player and not app-fixable.

Note: ReplayGainAudioProcessor.isActive() is always true (onConfigure accepts
all 16-bit PCM), so the processor sits in the pipeline even at unity gain. Left
as-is for now; the measurement above decides whether it's worth addressing.

## Round 5 — the "22% -> 50% after 5s, nothing touched" jump (real cause)

A `top -H` capture during the event was decisive and DISPROVED the round-4
Smart Chain theory: between the 22% and 50% snapshots, the SAME
ExoPlayer:Playback thread doubled (5.6->11.9%) and MediaCodec_loop doubled
(3.7->7.4%), with NO acoustic-decode/DefaultDispatch thread anywhere. The jump
is purely the audio DECODER doing more work — two independent causes:

1. NO LoadControl -> default ~50s max buffer. On start, ExoPlayer decodes a
   small amount to begin quickly (the low ~22% window), then front-loads up to
   50s of audio to fill the buffer (the ramp to ~50%), then settles. For LOCAL
   files there's no network latency to mask, so a 50s buffer is pointless.
   Fix: DefaultLoadControl with 5s/15s buffers (min/max) and 1s/2s playback
   thresholds -> the elevated-decode window is ~3x shorter and gentler.

2. FfmpegAudioRenderer listed FIRST -> SOFTWARE decoding. Renderer order is
   decode priority; FFmpeg (CPU software decoder) preceded the hardware
   MediaCodecAudioRenderer, so common formats (mp3/aac/flac) were decoded on
   the CPU even with a hardware decoder present — the dominant contributor to
   the 20-50% floor. Fix: MediaCodec FIRST, FFmpeg as fallback for formats HW
   can't handle. ReplayGain is applied in both paths (the processor is passed
   to the FFmpeg renderer AND the MediaCodec sink), so gain is unaffected.
   TRADEOFF: upstream prefers FFmpeg for maximum format/gapless robustness;
   preferring HW lowers CPU but leans on MediaCodec. The FFmpeg fallback still
   covers anything MediaCodec rejects.

NOTE: the round-4 Smart Chain changes are still correct and kept (they remove
real duplicate work + a during-playback decode), they just weren't THIS
symptom. Still a debug build in the capture; release will read lower.

## Round 4 — the delayed "22% -> 50% a few seconds in" jump

Symptom: after a force-close + reopen with music playing and the player page
open, CPU sat at ~22% (just the audio decoder) then jumped to ~50% ~5s later
for the identical task. A delayed jump = event/timer-triggered work, not
per-frame drawing. Three causes, all in the Smart Chain plugin path:

1. ACOUSTIC DECODE DURING PLAYBACK (main cause). On a cold start nothing is
   cached, so the first tracked transition called AcousticFeatures.extract(),
   which spins up a SECOND MediaCodec decoder and decodes ~30s of audio — run
   concurrently with the playback decoder, hence the second-core jump.
   Fix (ChainRepository.embeddingFor): only decode-seed acoustically when the
   player is NOT playing. During playback, write the instant hash seed and
   mark it un-seeded; a later paused transition or the manual Acoustic Scan
   upgrades it to acoustic. Chain ordering works from day one regardless.
   Added PlaybackStateManager injection (cycle-free: it's a no-arg @Inject
   singleton the tracker already depends on).

2. DOUBLE TRACKER INVOCATION. Both PlaybackViewModel and PlaybackService
   FragmentForward onProgression() to the SAME @Singleton PlaybackTracker, so
   every progression event ran the whole tracker path twice. Removed the
   ViewModel's duplicate call (the service is the correct single owner).

3. LOGGING ON THE HOT PATH. onProgression()/handleSongChange() called L.d()
   on every ~100ms tick and every transition. In debug builds Timber formats
   strings 10x/s; in FORKED release builds CopyleftNoticeTree is planted and
   logs unconditionally too. Removed the per-tick / per-transition logs.

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

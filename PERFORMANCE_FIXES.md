# Performance fixes (foreground CPU 99–201% -> expected near-idle)

## v5.1.9 — NOT a hardware floor: VLC plays the same file at 18%

Critical control: VLC plays the SAME AAC file on the SAME device at a flat 18%,
no bump. So the ~45-51% + mid-playback doubling is NOT a device/codec floor
(previous conclusion RETRACTED) — it's the ExoPlayer path. Also confirmed: the
27->51 bump reproduces on Auxio 3.7.3 (no fork code, no plugins) and on the
SAME song with no track change, so it was never fork-specific or plugin-related.

Perfetto trace (spanning the jump at t=24s) showed: every AUDIO thread's CPU
~doubles (ExoPlayer:Playback 71->169ms/s, MediaCodec_loop 43->107ms/s, main
41->81), RenderThread stays 0, decoder WAKEUP RATE is flat (~135->120/s) — so
each decode op got ~2x more expensive, not more ops. Hardware MediaCodec IS in
use (MediaCodec_loop/HwBinder/CCodecWatchdog active). The delta vs VLC is
ExoPlayer's multi-thread buffer pipeline + the ASYNCHRONOUS MediaCodec adapter
(dedicated enqueuer + callback threads, cross-thread buffer hops).

Fix: force the synchronous adapter via
DefaultMediaCodecAdapterFactory(context).forceDisableAsynchronous()
(CORRECT method name this time — v5.1.5 used a non-existent
forceDisableAsynchronousBufferQueueing() and failed to build). Constructor
(context, adapterFactory, selector, enableDecoderFallback, handler, listener,
sink) verified against Media3 1.3.x.

CONFIDENCE: highest-probability lever from the trace, but ExoPlayer inherently
buffer-shuffles more than VLC, so this may land between 18% and 45%, not exactly
at VLC's 18%. Re-measure. If it helps but a gap remains, next lever is the
AudioProcessor chain / audio offload.

(Marquee scrolling left DISABLED from v5.1.8 for a clean measurement; it was
exonerated as the playback-bump cause, so SCROLLING_ENABLED can be flipped back
to true for UI once CPU is settled.)


## v5.1.8 — marquee scrolling fully disabled (static ellipsis)

User requested full marquee disable as a controlled test: current build shows a
flat 38% from the start of play (no on/off bump), so scrolling is no longer the
obvious variable — disabling it isolates whether the marquee contributes at all.

ThrottledMarqueeTextView: added SCROLLING_ENABLED master switch (default FALSE).
When false the view is a plain single-line end-ellipsis TextView — no
horizontal scroll, no ValueAnimator, no per-frame invalidate(); setSelected /
onTextChanged / onSizeChanged all early-return. Long titles truncate with "…".
Set SCROLLING_ENABLED=true to restore the brief scroll-once-then-rest reveal.

DIAGNOSTIC NOTE: this is a test build. If CPU during player-page playback drops
after this, the marquee redraw was contributing; if it stays at ~38-51%, the
cost is elsewhere and the pending "trace across the 27->51 jump" is still the
way to find the delayed-onset trigger.


## v5.1.7 — player-page bump is the scrolling title marquee (my own r3 code)

Diffing two same-audio traces (Settings page vs Player page) isolated the delta
cleanly: the player page adds RenderThread + libGLES_mali (GPU) +
CanvasContext::draw + SkiaOpenGLPipeline::draw + eglSwapBuffersWithDamageKHR —
all ZERO on Settings. I.e. the player page GPU-composites and swaps a full
frame EVERY vsync; Settings is static. That ~15% (main+RenderThread+GPU) is the
whole 27->51 bump, and it is NOT audio (audio is identical on both pages).

The driver is ThrottledMarqueeTextView (added earlier this session): its
ValueAnimator calls invalidate() every frame for the ENTIRE scroll of a long
title (~tens of seconds/song at 3 repeats), forcing continuous full-surface
redraws. My earlier "bitmap" optimization made each frame cheaper but the frame
COUNT is inherent to smooth scrolling — I moved the cost, didn't remove it.
Honest correction of my own prior fix.

Chosen tradeoff (user picked "scroll briefly then rest"): reveal the full title
ONCE per song, faster (48dp/s) with short holds, then go fully idle (zero
redraws) until the next track. Added revealedForCurrentText so play/pause
toggles or sheet-visibility changes on the SAME song don't re-trigger scrolling
(which would restart the redraw window); the flag resets on new text (new song).

RESULT: steady-state player-page CPU should now match the ~27% Settings/back-
ground baseline. A brief per-frame-redraw bump remains for the ~2-4s reveal
right after each song change, BY DESIGN — that's what "scroll briefly" means. To
eliminate even that, switch to a static ellipsis (no scroll). Constants at the
bottom of ThrottledMarqueeTextView tune the reveal length/speed.

## v5.1.6 — playback bump is the VISIBLE seekbar, not audio (reverted v5.1.5)

Decisive user observation: with the SAME track playing uninterrupted, CPU is
~51% only when the playback page is on screen; it drops to ~27% when the app is
backgrounded OR when a different page (Settings) is shown. Audio decode is
identical in all three, so the extra ~24% is entirely the playback PAGE
rendering, not audio. This also proves the v5.1.5 async-adapter theory wrong.

v5.1.5 REVERTED: forceDisableAsynchronousBufferQueueing() doesn't exist in the
vendored Media3 (build error) AND the premise was wrong — the async decoder
threads are a constant ~27% baseline present in every state, not the variable
cost.

Trace of the visible-page state: RenderThread draws a FULL frame every vsync
(RenderThread::threadLoop -> DrawFrameTask -> CanvasContext::draw ->
SkiaOpenGLPipeline::draw/swapBuffers), i.e. the surface recomposites
continuously. App-code frames inside performTraversals pointed at
com.google.android.material.slider.BaseSlider.updateLabels + a BaseSlider
OnGlobalLayout lambda — the Material Slider (seekbar) keeping the page live.

Fixes:
 - StyledSeekBar.positionDs: skip no-op value/label updates (was re-running the
   Slider's invalidate/label/animation machinery several times a second even
   when unchanged).
 - view_seek_bar.xml: Slider labelBehavior=gone — removes the redundant
   floating value bubble (updateLabels was in the hot path) whose animated
   indicator invalidates per frame. Auxio already shows position/duration in its
   own TextViews, so nothing is lost visually.

CAVEAT: the updateLabels signal was small in the sample, so this may not be the
FULL 24%. Both changes are safe reductions regardless; re-trace to confirm how
much of the visible-page cost remains (if any, the next suspect is per-frame
invalidation from the cover/rounded-outline or a stuck ripple).

## v5.1.5 — playback bump root cause: ASYNC MediaCodec adapter overhead

The v5.1.4 inset guard worked (no Auxio UI/inset frames in the next 51% trace)
but CPU stayed at 51% — so the inset storm was NOT the cause. ~69% of samples
were in audio threads. Decoding the trace by thread revealed the real cause:

The active decoder work is split across a dedicated ENQUEUER thread running
AsynchronousMediaCodecBufferEnqueuer.doQueueInputBuffer -> MediaCodec
.queueInputBuffer (~600 calls in the capture) PLUS a separate callback thread
looping on MessageQueue.next/epoll_pwait. This is Media3's ASYNCHRONOUS
MediaCodec adapter (default on API 31+). The async plumbing (extra threads +
message-passing per buffer) exists to smooth heavy VIDEO decode; for cheap
audio decode on a capable device the thread-hopping overhead dominates the
actual decode work and was the bulk of the 27%->51% bump.

Fix (ExoPlaybackStateHolder): force the SYNCHRONOUS MediaCodec adapter via
DefaultMediaCodecAdapterFactory(context).forceDisableAsynchronousBufferQueueing()
passed into MediaCodecAudioRenderer. The decode then runs inline on the
playback thread without the enqueuer/callback thread overhead.

BUILD CAVEAT (could not compile against the vendored :media-lib-exoplayer
here): the MediaCodecAudioRenderer constructor arg order
(context, adapterFactory, selector, enableDecoderFallback, handler, listener,
sink) and the forceDisableAsynchronousBufferQueueing() method name are the
standard recent-Media3 API. If the vendored Media3 differs, adjust the
constructor/method to match — the intent is simply "use the synchronous codec
adapter for the audio renderer." Verify with a fresh trace: the second
ExoPlayer:Media / enqueuer thread should disappear and playback CPU drop.

## v5.1.4 — playback CPU bump (27% -> 51%) analysis

Two sampled simpleperf traces at the two load levels during the SAME playback,
diffed by callchain-frame presence. What grew from 27% to 51%:
 - The MediaCodec/CCodec buffer pipeline roughly doubled (feedInputBuffer
   2.2->3.7, onReleaseOutputBuffer 1.2->2.5, sendOutputBuffers, C2 buffer
   alloc/map). NOTE: shouldContinueLoading/doSomeWork did NOT grow, so this is
   raw buffer THROUGHPUT (front-loading the buffer), not the LoadControl
   deciding to load more — i.e. framework buffer-fill, not app-fixable.
 - A window-insets + bottom-sheet LAYOUT pass appeared that is nearly absent at
   27%: MainActivity.setupEdgeToEdge inset listener, BottomSheetContentBehavior
   .onLayoutChild/layoutContent, {Playback,Queue,Base}BottomSheetBehavior
   .applyWindowInsets, EdgeFrameLayout/RecyclerView.onApplyWindowInsets,
   replaceSystemBarInsetsCompat. This is app-code and IS fixable.

Fix (MainActivity.setupEdgeToEdge): the OnApplyWindowInsetsListener called
view.updatePadding() on every inset dispatch, and updatePadding() triggers
requestLayout() — during sheet transitions the behaviors re-dispatch insets
repeatedly, so identical padding was re-applied over and over, each time kicking
a full layout+inset pass across the tree. Now padding is only updated when the
horizontal inset values actually change.

HONEST CAVEAT: a sampled profile shows where time goes, not what called
requestLayout. It's possible the 51% capture caught the sheet mid-transition
(legitimately busier) rather than a storm. This fix + the r8 onPreDraw early-out
both reduce per-frame UI work during playback; the decode doubling is framework
buffering. Re-measure to confirm the delta.

## Round 8 — DEFINITIVE: idle CPU root cause from a simpleperf sampled profile

A sampled (non-overflowing) simpleperf capture while idle finally named it.
7,316 samples: 69.5% on the main thread, 24.8% on RenderThread. Top of stack
was dominated by the UI RENDER pipeline running every vsync:
RenderNode::prepareTreeImpl, updateDisplayListIfDirty, onDescendantInvalidated,
Choreographer.doFrame, and repeated VectorDrawable.draw/getAlpha. The top
APP-CODE frame across all callchains was MainFragment.onPreDraw (by a wide
margin), followed by CoordinatorAppBarLayout.onPreDraw$lambda$0.

Root cause: MainFragment registers an OnPreDrawListener that recomputes ALL
bottom-sheet transition math (playback + queue slide offsets, alphas, corners,
scrims, translationZ) on EVERY frame and returns true (staying registered).
The fork had already guarded each setter with "if (x != last)", so at rest no
setters fire — but the method still ran every frame and kept the view tree
drawing, which kept RenderThread compositing (~25%) and re-drawing the vector
drawables in the tree every vsync. That's the constant ~20-28% idle cost.

Fix (MainFragment.onPreDraw): cache the playback + queue slide offsets; when
both are identical to the previous frame AND a full frame has already been
applied at this resting position, skip the entire body for that frame (still
return true so the next real interaction is caught). During any real sheet
animation the offsets change every frame, so the body runs normally; the
resting position is always fully applied exactly once before skipping begins.
Cache is reset in onBindingCreated so a fresh view always gets a full apply
(preserving the existing fresh-binding-must-reapply fix).

Combined with the round-7 CoordinatorAppBarLayout guard, this removes the
per-frame app-code work that was keeping the frame pipeline awake at idle. The
playing-indicator AnimationDrawable already stops correctly on pause; its
frames in the trace were the onPreDraw-forced full-tree redraws, so no change
there.

## Round 7 — THE idle-CPU bug, found in an ART method trace

User captured a method trace with NOTHING PLAYING but CPU pinned at 17-20%
(main thread 12.5%, RenderThread 5.3%). Parsed 30k events; the main thread's
exclusive time was dominated by two things:

1. A PER-FRAME UI RELAYOUT LOOP. CoordinatorAppBarLayout registered an
   OnPreDrawListener that ran a full CoordinatorLayout.onNestedPreScroll (rect
   math across the child tree, via ContinuousAppBarLayoutBehavior) on EVERY
   frame, unconditionally and forever. The trace showed onPreDraw$lambda$0 +
   CoordinatorLayout.getChildRect/getDescendantRect/offsetDescendantMatrix +
   AppBarLayout.onNestedPreScroll firing through Choreographer.onVsync while
   idle. Returning true from onPreDraw keeps the listener registered, so this
   was a permanent per-frame tax that also kept RenderThread busy.
   Fix (CoordinatorAppBarLayout): only run onNestedPreScroll when the scrolling
   child's scroll OFFSET or content RANGE actually changed (range covers
   data-driven lift changes, the original reason it ran every frame). Forced
   recomputes (setLiftOnScrollTargetViewId) reset the cache so they still fire.

2. EXOPLAYER DECODING WHILE PAUSED. Same trace showed dequeueInputBufferIndex
   (16.8%), MediaCodecRenderer.feedInputBuffer, DefaultAudioSink.drainOutput
   Buffer, and DefaultLoadControl.shouldContinueLoading active with nothing
   playing. The round-5 tiny buffer (5/15s) made this WORSE: too small a buffer
   refills constantly so the load control never lets the decoder sleep. Raised
   to 15/30s so the buffer fills once after start then goes quiet — lowest
   steady-state. (The per-frame loop in #1 also kept the message loop hot,
   which kept the load control being polled; fixing #1 helps here too.)

These are the real "constant CPU while idle" causes — distinct from the audio
decode floor. Both fixes are in app code and safe.

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

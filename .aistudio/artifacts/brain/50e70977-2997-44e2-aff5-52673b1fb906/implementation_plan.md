# Implementation Plan: CapCut-Style Performance & Export Stabilization for Vydra

## Summary
Stabilize Vydra (`com.boostlyte.vydra`) across Android 8.0+ (API 26) through Android 15 by fixing four critical performance and rendering flaws:
1. **Editor Lag on Large Projects**: Stop full layout inflation on 50ms ExoPlayer ticks; virtualize/diff timeline track views and debounce preview sync.
2. **Export Crashes on Large Projects**: Replace the fragile monolithic single-pass FFmpeg filtergraph with a **CapCut-style bounded chunked rendering pipeline** (8–12 clips per chunk) that bounds open file descriptors and RAM usage, followed by a fast concat demuxer pass.
3. **Stray Long Black Text at Top of Export**: Eliminate duplicate/unpositioned narration transcript text burned at `y=24` by strictly filtering `EditOperation.AddText` operations when subtitles are present or when text originates from Auto Assemble/transcription.
4. **Ken Burns Stutter & Transition Jitter**: Fix FFmpeg image input framerate `-framerate` and subpixel coordinate math in `zoompan` filters to match smooth 60fps preview, and pre-cache transition frames to eliminate preview hiccups.

---

## User Review & Critical Decisions

| Area | User Direction / Decision | Implementation Strategy |
| :--- | :--- | :--- |
| **Export Architecture** | "Like how big video editor do theirs like capcut so that it works both on low end devices and flagship devices" | Bounded-memory chunking: Split timeline into discrete chunks (max 10 clips per chunk) rendered with ultra-fast intermediate settings, then stream-copy concatenate via FFmpeg concat demuxer. Bounded memory prevents OOM and SIGSEGV on Android 8+ low-RAM devices. |
| **Editor Playback & Transitions** | "Like how big video editor do theirs like capcut that it runs smoothly on both low end devices and flagship devices" | Decouple timeline UI updates from ExoPlayer playback ticks (50ms). Use state diffing for timeline tracks so views are never repeatedly destroyed/inflated during playback. Pre-warm transition textures in memory. |
| **Stray Black Text** | Script text / narration transcript appearing at top of exported video | Suppress raw script/transcription `EditOperation.AddText` when subtitles are active, and sanitize text bounds/positions so unpositioned strings never default to top-screen burn-in. |
| **Ken Burns Effect** | Stuttering and jitter on exported images | Supply explicit `-loop 1 -framerate ${_exportFps.value}` to image inputs before filtergraph processing, and calculate subpixel zoom factor using floating-point time expressions (`time` or calibrated frame counts) rather than 25fps default stepped quantization. |

---

## Proposed Changes

### Zone 1: Subtitle & Transcript Deduplication (Fixes Stray Black Text)

#### [app/src/main/java/com/boostlyte/vydra/viewmodel/VideoEditingViewModel.kt]
- In `buildVideoFilterStages(...)`:
  - When `hasSubtitles` is true or `activeOperations.any { it is EditOperation.AddSubtitles }`:
    - Filter out ANY `EditOperation.AddText` where text matches the transcript, script text, or has `groupId == CAPTION_GROUP_ID` or empty/unpositioned geometry.
  - In `buildDrawtextExpr(...)`:
    - Ensure any unpositioned text card cannot fall back to arbitrary `y=24` at top-center.
    - Check for valid start/end timestamps so narration script cards with timeline-spanning or unbounded duration do not stay burned across the entire video.

---

### Zone 2: Ken Burns Smooth Zoom & Pan (Fixes Exported Animation Stutter)

#### [app/src/main/java/com/boostlyte/vydra/viewmodel/VideoEditingViewModel.kt] & [app/src/main/java/com/boostlyte/vydra/export/FFmpegFilterUtils.kt]
- Root Cause: FFmpeg's `zoompan` filter defaults to an internal input framerate of 25 fps (`fps=25`). When processing a static image input without `-framerate <fps>`, `zoompan` samples every 1/25th of a second and repeats frames to reach 30/60 fps, creating noticeable micro-stutter. Furthermore, `d=1` forces frame re-evaluation without subpixel anti-aliasing.
- Fix:
  1. Add `-framerate ${targetFps} -loop 1 -t ${durationSec}` to each image input specification.
  2. Configure `zoompan`:
     `zoompan=z='min(max(zoom,pzoom)+0.0015,1.2)':d=1:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=${width}x${height}:fps=${targetFps}`
     or use precise linear interpolation:
     `zoompan=z='1.0+0.15*(on/(fps*${durationSec}))':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=${width}x${height}:fps=${targetFps}`
  3. Guarantee that `fps` and `s` match the output container profile exactly, eliminating resampling judder.

---

### Zone 3: CapCut-Style Bounded Chunked Export (Fixes Large Project Export Crash & Slowness)

#### [app/src/main/java/com/boostlyte/vydra/export/ChunkPlanner.kt] & [app/src/main/java/com/boostlyte/vydra/viewmodel/VideoEditingViewModel.kt]
- **The Problem:** A 40-clip project builds a single FFmpeg command with 40 `-i` flags, 40 image/video filters, and 39 chained `xfade` filters. Android kills the process because:
  - Linux per-process file descriptor limits are reached.
  - Software video decoding/scaling buffers for dozens of inputs simultaneously exhaust Android's low-memory threshold (Low Memory Killer).
- **The CapCut Solution:**
  1. **Chunk Partitioning:**
     - For projects with $\le 16$ clips and no memory pressure: use optimized single-pass.
     - For projects $> 16$ clips: partition the timeline into sequential chunks of 8–10 clips each (with 0.5s transition overlap preserved at chunk boundaries).
  2. **Chunk Rendering:**
     - Render each chunk to a lightweight temporary MP4 file (`chunk_001.mp4`, `chunk_002.mp4`, etc.) using hardware/software fast preset (`-preset ultrafast -crf 18`).
     - Memory is released immediately after each chunk finishes.
  3. **Demuxer Stitching:**
     - Create a concat list file (`concat.txt`) and run FFmpeg stream-copy (`-c copy`) to stitch the chunks into the final video in under 2 seconds.
  4. **Audio & Subtitle Pass:**
     - Apply background audio, voiceover, and burned subtitles/overlays in a final fast pass over the unified video stream, or bake during chunk assembly.

---

### Zone 4: Timeline Playback & Transition Optimization (Fixes Editor Lag)

#### [app/src/main/java/com/boostlyte/vydra/ui/VideoEditingActivity.kt]
- **The Problem:** The playback ticker runs every 50ms and triggers:
  - `renderTracks(project)` which destroys and reinflates hundreds of views in the horizontal scroll container.
  - Redundant audio sync and matrix allocations.
- **The Solution:**
  1. **Decouple Playback from Layout:**
     - `updateProgress()` only updates the playhead scrubber position (`playheadLine.translationX = ...`) and timecode text.
     - `renderTracks()` is ONLY invoked when the clip list, durations, or order change (via a distinct structural version counter or StateFlow diff).
  2. **Transition Frame Pre-Caching:**
     - Transition preview views should reuse cached bitmaps rather than decoding/scaling images synchronously during playback transitions.
  3. **Touch & Slider Debouncing:**
     - Ensure all inspector panels (font size, transition duration, color adjustments) continue using the zero-allocation debounced pipeline established in Phase 16.

---

## Technical Architecture (CapCut-Style Pipeline)

```
[ Large Project Timeline: 40 Clips ]
              │
              ▼
    [ Chunk Partition Engine ]
    ├─ Chunk 1: Clips 01..10  ──► FFmpeg Sub-pass 1 ──► chunk_01.mp4 (RAM released)
    ├─ Chunk 2: Clips 10..20  ──► FFmpeg Sub-pass 2 ──► chunk_02.mp4 (RAM released)
    ├─ Chunk 3: Clips 20..30  ──► FFmpeg Sub-pass 3 ──► chunk_03.mp4 (RAM released)
    └─ Chunk 4: Clips 30..40  ──► FFmpeg Sub-pass 4 ──► chunk_04.mp4 (RAM released)
              │
              ▼
    [ Fast Concat Demuxer Pass (-c copy) ] ──► unified_timeline.mp4 (< 2s)
              │
              ▼
    [ Master Audio & Subtitle Composite ]
    (Narration + BGM mix + Subtitle filter without stray text)
              │
              ▼
    [ Final Export Output: Stable, Smooth, High Quality ]
```

---

## Verification Plan

### Automated Testing
- Run existing full test suite to guarantee zero regression:
  `gradle :app:testDebugUnitTest` (all 188 unit tests must pass).
- Add new unit tests in:
  - `ChunkPlannerTest.kt`: Validate chunk slicing, overlap math, and boundary duration integrity.
  - `TextOverlayDeduplicationTest.kt`: Verify that transcript/script text is never rendered as duplicate `EditOperation.AddText` when subtitles are active.
  - `KenBurnsFilterExpressionTest.kt`: Verify `zoompan` generated command contains explicit input `-framerate` and matching output `fps`.

### Manual / Integration Verification
- Verify editor timeline playback with 30+ clips: Verify 60fps smooth scrolling and zero view reinflation during playback.
- Verify exported video: Confirm absence of stray black transcript text at the top.
- Verify Ken Burns effect: Confirm exported zoom/pan is butter-smooth without frame-step stutter.
- Verify large project export: Confirm successful completion of 30+ clip export without out-of-memory or timeout crashes.

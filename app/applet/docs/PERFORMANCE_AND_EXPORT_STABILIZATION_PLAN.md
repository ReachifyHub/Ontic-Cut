# Performance & Export Stabilization Plan (CapCut Architecture)

## Context & Objectives
This document records the root-cause diagnosis and stabilization strategy for Vydra (`com.boostlyte.vydra`) on Android 8.0+ (API 26+) through Android 15.

The plan resolves four critical defects:
1. **Editor Lag on Large Projects**: When many images/videos with audio/voiceover are added, the editor lags severely once transitions, subtitles, or Auto Assemble are applied.
2. **Export Crashes on Large Projects**: Exports of large projects (25+ clips) crash midway and are very slow.
3. **Ken Burns Stutter & Stray Black Text on Export**: Exported Ken Burns zoom/pan judders heavily; an unexpected long black text containing the script/narration transcript appears at the top of the video (`y=24`).
4. **Editor Transition Stutter**: Preview playback intermittently stutters when transitioning between visual clips.

---

## 1. Root Cause Analysis

### A. Stray Long Black Text at Top of Export
- **File:** `VideoEditingViewModel.kt` (`buildVideoFilterStages`, `buildDrawtextExpr`)
- **Cause:** When Auto Assemble or transcription runs, narration script lines are added as operations. In `buildVideoFilterStages`, `EditOperation.AddText` was only suppressed if `op.groupId == CAPTION_GROUP_ID`. Any script text card or transcript operation that lacked this exact group ID remained active. In `buildDrawtextExpr`, operations with unconfigured custom positions fall back to `y = 24` (top center of the screen). Because its duration spans the clip or project and color is `#000000`, FFmpeg permanently burns the entire narration text across the top of the video in black.

### B. Ken Burns Stutter on Export
- **File:** `VideoEditingViewModel.kt` (`buildConsolidatedFFmpegCommand`)
- **Cause:** FFmpeg's `zoompan` filter internally operates at 25 fps by default. When an image is fed without an explicit `-framerate <fps>` and `-loop 1`, `zoompan` calculates discrete steps on 25 fps grid and converts to the output rate (30/60 fps) by duplicating arbitrary frames. This produces severe stutter and visible stepping.
- **Solution:** Add `-loop 1 -framerate ${_exportFps.value} -t $duration` to each image input and supply matching output dimensions and `fps` directly in the `zoompan` filter formula with subpixel linear interpolation.

### C. Large Project Export Crashes & Extreme Slowness
- **File:** `VideoEditingViewModel.kt`, `ExportService.kt`
- **Cause:** Monolithic single-pass export creates a filtergraph with $N$ inputs, $N$ zoompan filters, and $N-1$ `xfade` filters. For a 30–50 clip project, this opens 50 file descriptors simultaneously and demands enormous memory buffers for concurrent software scalers, triggering Android's Low Memory Killer (OOM) or SIGSEGV.
- **Solution:** CapCut-style bounded chunking. Projects with $> 16$ clips are split into sequential batches of 8–10 clips, rendered to intermediate MP4 chunks (`-preset ultrafast`), and then stitched together via FFmpeg's concat demuxer (`-c copy`) in under 2 seconds. Memory usage is bounded regardless of total project length.

### D. Editor Timeline Playback & Transition Lag
- **File:** `VideoEditingActivity.kt`
- **Cause:** `updateProgress()` executes every 50ms during ExoPlayer playback. In addition to advancing the playhead, it repeatedly calls layout-recreating functions (`renderTracks`) and runs redundant audio synchronization loops on the main thread, choking UI rendering.
- **Solution:** Decouple playback ticks from layout inflation. `updateProgress()` only translates the playhead scrubber; track layouts are re-rendered only when clip count, order, or durations structurally change. Transition frames are pre-cached in memory.

---

## 2. Implementation Strategy & Architecture

```
[ Project Timeline: e.g. 40 Clips ]
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

## 3. Strict Safety Protocol
- All changes maintain strict immunity for protected zones:
  - `app/src/main/java/com/boostlyte/vydra/autoassemble/*`
  - `app/src/main/java/com/boostlyte/vydra/transcription/*`
  - `AutoAssembleManager.kt`, `SceneFuzzyMatcher.kt`, `ScriptMapParser.kt`, `TimelineAssembler.kt`
  - `CaptionCueBuilder.kt`
- ABI packaging freeze (`arm64-v8a`) is preserved.
- Full JVM unit test suite (`gradle :app:testDebugUnitTest`) must pass before and after all modifications.

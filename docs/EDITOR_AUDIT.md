# Vydra Android Video Editor — Comprehensive Editing Tools Audit Report

**Date:** October 2026  
**Package:** `com.boostlyte.vydra` (forked from LibreCuts / Ontic Cut)  
**Scope:** Complete code-level and runtime audit of all 9 editing capability categories and tools.  
**Rule Adherence:** Zero code edits made to application source files.

---

## Executive Summary & Capability Status Matrix

| # | Capability Group | Tool / Feature | Status | Fix Effort | Primary Code Path / File |
|---|---|---|---|---|---|
| 1 | **Main Track Clips** | Trim | **WORKS** | S | `VideoEditingActivity.kt:showVideoSegmentTrimDialog()`, `TrackTrimView.kt` |
| 2 | Main Track Clips | Split | **UNSTABLE** | M | `VideoEditingActivity.kt:splitSelectedVideo()`, `VideoEditingViewModelExt.kt:splitVideoSegment()` |
| 3 | Main Track Clips | Speed | **WORKS** | S | `VideoEditingActivity.kt:applySpeedToSegment()`, `FFmpegRenderEngine.kt:generateSpeedProxy()` |
| 4 | Main Track Clips | Reverse | **WORKS** | S | `VideoEditingActivity.kt:reverseVideoSegment()`, `FFmpegRenderEngine.kt:reverseVideo()` |
| 5 | Main Track Clips | Freeze Frame | **WORKS** | S | `VideoEditingActivity.kt:freezeFrameAtCurrentPosition()` |
| 6 | Main Track Clips | Reorder Clips | **FEATURE GAP** | L | Missing UI drag-and-drop / adapter move logic on `sequenceTrackContainer` |
| 7 | Main Track Clips | Delete Clip | **UNSTABLE** | M | `VideoEditingActivity.kt:deleteSelectedVideo()`, `VideoEditingViewModelExt.kt:deleteSequenceSegment()` |
| 8 | Main Track Clips | Duplicate Clip | **FEATURE GAP** | M | Missing duplicate action for main sequence items in `video_editing_toolbar.xml` |
| 9 | **Text Overlays** | Add Text | **WORKS** | S | `VideoEditingActivity.kt:addTextAction()`, `DraggableTextOverlayView.kt:activate()` |
| 10 | Text Overlays | Edit Text (In-Place) | **WORKS** | S | `DraggableTextOverlayView.kt:activateForEdit()`, `VideoEditingActivity.kt:enterTextEditingMode()` |
| 11 | Text Overlays | Keyboard Input | **WORKS** | S | `DraggableTextOverlayView.kt:requestEditingFocus()` |
| 12 | Text Overlays | Text Color | **WORKS** | S | `VideoEditingActivity.kt:setupColorPicker()`, `HSVColorPickerView.kt` |
| 13 | Text Overlays | Font Selection | **WORKS** | S | `FontManager.kt`, `VideoEditingActivity.kt:setupTextFontSelector()` |
| 14 | Text Overlays | Opacity | **WORKS** | S | `DraggableTextOverlayView.kt:setOpacity()`, `VideoEditingViewModel.kt:buildDrawtextExpr()` |
| 15 | Text Overlays | Shadow | **BROKEN** | S | `text_format_settings.xml:seekBorderWidth`, `DraggableTextOverlayView.kt:setBorderThickness()` |
| 16 | Text Overlays | Letter Spacing | **UNSTABLE** | S | `DraggableTextOverlayView.kt:setLetterSpacing()`, `VideoEditingViewModel.kt:buildDrawtextExpr()` |
| 17 | Text Overlays | Line Spacing | **WORKS** | S | `DraggableTextOverlayView.kt:setLineSpacing()`, `VideoEditingViewModel.kt:buildDrawtextExpr()` |
| 18 | Text Overlays | Alignment | **WORKS** | S | `DraggableTextOverlayView.kt:setTextAlign()`, `VideoEditingViewModel.kt:buildDrawtextExpr()` |
| 19 | Text Overlays | Duplicate Text | **BROKEN** | M | `VideoEditingActivity.kt:duplicateSelectedOverlay()`, `DraggableTextOverlayView.kt:commitText()` |
| 20 | Text Overlays | Keyframes | **WORKS** | M | `VideoEditingActivity.kt:enterKeyframeEditingMode()`, `VideoEditingViewModel.kt:buildFFmpegInterpolationExpr()` |
| 21 | Text Overlays | Delete Text | **WORKS** | S | `VideoEditingActivity.kt:btnTextDelete`, `VideoEditingViewModel.kt:removeOperation()` |
| 22 | **Audio Tracks** | Volume Slider | **BROKEN** | M | `VideoEditingActivity.kt:1741-1766`, `VideoEditingActivity.kt:6715-6718`, `VideoEditingActivity.kt:4401-4416` |
| 23 | Audio Tracks | Trim Track | **WORKS** | S | `audio_editing_toolbar.xml:audioTrimTrack`, `VideoEditingActivity.kt:4353-4373` |
| 24 | Audio Tracks | Fade In / Fade Out | **UNSTABLE** | M | `VideoEditingActivity.kt:showFadeDialog()`, `VideoEditingViewModel.kt:1250-1265` |
| 25 | Audio Tracks | Audio Ducking | **UNSTABLE** | M | `VideoEditingActivity.kt:btnAudioDucking`, `VideoEditingViewModel.kt:1284-1305` |
| 26 | Audio Tracks | Beats Detection | **WORKS** | S | `AudioAnalyzer.kt:detectBeats()`, `TrackTrimView.kt` |
| 27 | Audio Tracks | Delete Audio | **WORKS** | S | `VideoEditingActivity.kt:btnAudioDelete`, `VideoEditingViewModel.kt:deleteOperation()` |
| 28 | Audio Tracks | Voice Recording | **WORKS** | S | `VideoEditingActivity.kt:showVoiceOverDialog()`, `MediaRecorder` |
| 29 | **Subtitles** | .SRT File Import | **WORKS** | S | `SubtitleParser.kt:parse()`, `VideoEditingActivity.kt:5380-5425` |
| 30 | Subtitles | Auto-Captions Display & Edit | **WORKS** | M | `CaptionTrackView.kt`, `CaptionCueBuilder.kt`, `VideoEditingActivity.kt:7677-7706` |
| 31 | **Transitions** | Apply Between Clips | **UNSTABLE** | M | `VideoEditingActivity.kt:8159-8173`, `VideoEditingViewModelExt.kt:addTransitionOperation()` |
| 32 | Transitions | Animated Toolbar Preview | **UNSTABLE** | S | `VideoEditingActivity.kt:8245-8280`, `dialog_transitions.xml` |
| 33 | Transitions | Preview Playback | **BROKEN** | L | `VideoEditingActivity.kt:6593-6640`, `TransitionPreviewOverlayView.kt` |
| 34 | Transitions | Export Rendering | **BROKEN** | L | `VideoEditingViewModel.kt:1083-1178` (`xfade` / `acrossfade` chaining) |
| 35 | Transitions | Duration Control | **UNSTABLE** | S | `VideoEditingActivity.kt:8182-8188`, `dialog_transitions.xml:sliderTransitionDuration` |
| 36 | **Overlays** | Image Overlays | **WORKS** | S | `DraggableImageOverlayView.kt`, `ImageOverlayView.kt`, `EditOperation.AddImageOverlay` |
| 37 | Overlays | Stickers Catalog | **FEATURE GAP** | M | No built-in stickers catalog or picker; relies exclusively on user importing custom image files |
| 38 | Overlays | GIFs | **WORKS** | S | `ImageOverlayView.kt:318-375`, `FFmpegRenderEngine.kt:generateVideoFromGif()` |
| 39 | Overlays | Video Overlays (PIP) | **WORKS** | M | `DraggableImageOverlayView.kt`, `ImageOverlayView.kt`, `VideoEditingViewModel.kt:841` |
| 40 | Overlays | Masking | **WORKS** | M | `VideoMaskOverlayView.kt`, `bottom_sheet_mask.xml`, `VideoEditingViewModel.kt:239-270` |
| 41 | Overlays | Chroma Key | **WORKS** | S | `HSVColorPickerView.kt`, `chroma_key_bottom_sheet_dialog.xml`, `VideoEditingViewModel.kt:488-494` |
| 42 | Overlays | Layer Reordering | **WORKS** | S | `VideoEditingActivity.kt:820-831`, `VideoEditingViewModelExt.kt:moveOverlayOperation()` |
| 43 | **Crop & Canvas** | Clip Crop | **WORKS** | S | `CropOverlayView.kt`, `crop_editing_toolbar.xml`, `VideoEditingViewModel.kt:174-190` |
| 44 | Crop & Canvas | Canvas Background | **WORKS** | S | `background_editing_toolbar.xml`, `VideoEditingActivity.kt:4893-4950`, `VideoEditingViewModel.kt:1045-1065` |
| 45 | Crop & Canvas | Project Canvas Aspect Ratio | **FEATURE GAP** | L | Canvas output aspect ratio cannot be changed; locked to primary input video aspect ratio |
| 46 | **Freehand Drawing** | Handwriting Canvas | **WORKS** | S | `HandwritingCanvasView.kt`, `layout_handwriting_panel.xml` |
| 47 | Freehand Drawing | Drawing Undo / Redo | **WORKS** | S | `HandwritingCanvasView.kt:undo()`, `HandwritingCanvasView.kt:redo()` |
| 48 | Freehand Drawing | Brush Size & Color | **WORKS** | S | `BrushSizeDotView.kt`, `HSVColorPickerView.kt`, `layout_handwriting_panel.xml` |
| 49 | Freehand Drawing | Export Overlay Incorporation | **WORKS** | S | `VideoEditingActivity.kt:saveAndCommitHandwriting()`, `AddImageOverlay` |
| 50 | **Export** | Resolution Options | **WORKS** | S | `export_quality_bottom_sheet_dialog.xml:cgResolution`, `VideoEditingViewModel.kt:setExportResolution()` |
| 51 | Export | Progress Reporting | **WORKS** | S | `ExportService.kt:77-84`, `FFmpegRenderEngine.kt:271-279`, `VideoEditingActivity.kt:210-213` |
| 52 | Export | Output Correctness | **UNSTABLE** | L | `VideoEditingViewModel.kt:buildConsolidatedFFmpegCommand()`, `FFmpegRenderEngine.kt:297-316` |

---

## Detailed Audit by Capability Category

### 1. Main Track Clips

#### 1.1 Trim
- **Status:** `WORKS`
- **Evidence:** `VideoEditingActivity.kt:1557-1564` launches `showVideoSegmentTrimDialog(index, item)`. Inside `showVideoSegmentTrimDialog` (lines 7911–8030), `TrackTrimView` is initialized with dynamic thumbnail tiles extracted via `FrameAdapter`. Adjusting handles calls `onTrimAdjustingWithDelta` which seeks the main ExoPlayer preview in real-time. Tapping Done (`btnDoneTrim`) invokes `viewModel.updateMainVideoTrim(start, end)` for primary clip (index 0) or `viewModel.updateMergeItemTrim(index - 1, start, end)` for subsequent clips.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S` if polish needed).

#### 1.2 Split
- **Status:** `UNSTABLE`
- **Evidence:** `VideoEditingActivity.kt:4992-5021` (`splitSelectedVideo()`) computes `localSplitTimeMs` based on `getGlobalPosition()` and calls `viewModel.splitVideoSegment(index, localSplitTimeMs, item.uri, item.durationMs)`.
- **Root-Cause Hypothesis:** 
  1. `VideoEditingViewModelExt.kt:321-367` splits clip 0 into a trimmed primary clip and prepends a new `MergeItem` to `EditOperation.Merge`. However, it does not re-index existing `EditOperation.Transition` operations. Because transitions reference clips by fixed integer `index` (gap between clip `i` and `i+1`), splitting any clip shifts subsequent clip indices, causing all following transitions to attach to the wrong cut points or duplicate transitions at the split boundary.
  2. Subtitle cues and audio offsets are not split or adjusted if split occurs across them.
- **Fix Effort:** `M`

#### 1.3 Speed
- **Status:** `WORKS`
- **Evidence:** `VideoEditingActivity.kt:1588-1594` opens `showSpeedEditingToolbar()`. Preset speeds (0.5x, 1.0x, 1.5x, 2.0x) and fine slider (0.1x to 10.0x) call `applySpeedToSegment()` (lines 4655–4790). When changed from 1.0x, it invokes `ffmpegEngine.generateSpeedProxy()` in the background with a loading dialog (`showLoading("Applying speed changes...")`), sets `proxyUri`, and adjusts timeline duration stretch in `renderTracks()` and ExoPlayer playlist.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 1.4 Reverse
- **Status:** `WORKS`
- **Evidence:** `VideoEditingActivity.kt:1596-1600` calls `reverseVideoSegment(index, item)` (lines 4792–4860). Generates `proxy_reverse_*.mp4` via `ffmpegEngine.reverseVideo()`, properly handling combination with speed proxies. Un-reversing cleanly restores or regenerates speed proxies.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 1.5 Freeze Frame
- **Status:** `WORKS`
- **Evidence:** `VideoEditingActivity.kt:1584-1586` calls `freezeFrameAtCurrentPosition()` (lines 4212–4320). Uses `MediaMetadataRetriever.getFrameAtTime()` to extract a PNG frame at the exact playhead position, executes FFmpeg to convert it into a 3-second `.mp4` video (`-loop 1 -t 3 -c:v h264_mediacodec`), splits the current segment at the playhead, and inserts the freeze frame into `sequenceItems`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 1.6 Reorder Clips
- **Status:** `FEATURE GAP`
- **Evidence:** `VideoEditingActivity.kt:7313-7370` renders main timeline sequence segments inside `sequenceTrackContainer` with hardcoded absolute left margins (`leftMargin = segmentLeft`). There is no `ItemTouchHelper`, drag-and-drop listener, or "Move Left / Move Right" action button in `video_editing_toolbar.xml`.
- **Root-Cause Hypothesis:** Architecture was originally built around a single main video with secondary merge clips; drag-to-reorder on the main timeline track was never implemented.
- **Fix Effort:** `L`

#### 1.7 Delete Clip
- **Status:** `UNSTABLE`
- **Evidence:** `VideoEditingActivity.kt:1565-1574` shows confirmation dialog and calls `deleteSelectedVideo()` (lines 4880–4930), which invokes `viewModel.deleteSequenceSegment(index)`.
- **Root-Cause Hypothesis:** 
  1. Deleting clip index `i` deletes the segment and promotes the next clip if index 0 was deleted (`VideoEditingViewModelExt.kt:370-420`). However, it fails to update the index of all subsequent `EditOperation.Transition` items (`transOp.index`), causing transitions to shift to the wrong boundaries.
  2. If the user deletes all clips, `clearProjectAndShowImportScreen()` is invoked, but background cached proxies for deleted clips are left orphaned in `cacheDir`.
- **Fix Effort:** `M`

#### 1.8 Duplicate Clip
- **Status:** `FEATURE GAP`
- **Evidence:** `video_editing_toolbar.xml` (lines 1–364) contains Cancel, Trim, Split, Mute, Filters, Mask, Adjust, Speed, Extract Audio, Freeze Frame, Reverse, Mirror, and Delete. There is no Duplicate button for main track video clips (unlike text/image overlays which have `btnTextDuplicate` / `btnImageDuplicate`).
- **Root-Cause Hypothesis:** Duplicate action was implemented only for overlays in `duplicateSelectedOverlay()`, never for timeline sequence clips.
- **Fix Effort:** `M`

---

### 2. Text Overlays

#### 2.1 Add Text
- **Status:** `WORKS`
- **Evidence:** `VideoEditingActivity.kt:2995-3001` (`addTextAction()`) calls `enterTextEditingMode()`, activating `DraggableTextOverlayView` with an in-place input field, defaulting to 3000ms duration at the current playhead position.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 2.2 Edit Text (In-Place)
- **Status:** `WORKS`
- **Evidence:** Double-tapping or clicking a text overlay or timeline track sets `selectedOperationId`, which fires `DraggableTextOverlayView.activateForEdit(op)` with text content, font size, position, and styling.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 2.3 Keyboard Input
- **Status:** `WORKS`
- **Evidence:** `btnTextKeyboardTab` (lines 1180–1195) invokes `draggableTextOverlay?.requestEditingFocus()`, which requests focus on `editText` and invokes `InputMethodManager.showSoftInput()`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 2.4 Text Color
- **Status:** `WORKS`
- **Evidence:** `setupColorPicker(toolbar)` wires color swatches and custom `HSVColorPickerView` to `draggableTextOverlay?.setTextColorString()`. In export (`VideoEditingViewModel.kt:236`), `fontcolor='${formatColorForFFmpeg(op.color)}'` formats the hex string into FFmpeg-compatible syntax.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 2.5 Font Selection
- **Status:** `WORKS`
- **Evidence:** `FontManager.getAllFonts()` loads bundled fonts (Roboto) and custom user TTF files from `cacheDir/fonts/`. Selecting a font calls `draggableTextOverlay?.setFontPath(selectedFont.path)` to update the preview typeface, and passes `fontfile='path'` to FFmpeg's `drawtext` filter in `buildDrawtextExpr()`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 2.6 Opacity
- **Status:** `WORKS`
- **Evidence:** `text_format_settings.xml:seekOpacity` connects to `draggableTextOverlay?.setOpacity(value / 100f)` (`DraggableTextOverlayView.kt:323-330`). In export (`VideoEditingViewModel.kt:224-231`), `:alpha='${op.opacity}'` or an interpolated alpha expression is passed to FFmpeg.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 2.7 Shadow
- **Status:** `BROKEN`
- **Evidence:** 
  1. In `text_format_settings.xml:81-102`, the slider is labeled `Shadow` with ID `seekBorderWidth`.
  2. In `VideoEditingActivity.kt:1230-1233`, moving this slider calls `draggableTextOverlay?.setBorderThickness(value.toInt())`.
  3. In `DraggableTextOverlayView.kt:332-345`, `setBorderThickness()` sets a blurred shadow layer on the TextView if thickness > 0 (`editText.setShadowLayer(...)`), but stores it in `currentBorderThickness`.
  4. In export (`VideoEditingViewModel.kt:232`), `borderThickness` maps to FFmpeg's `:borderw=${op.borderThickness}:bordercolor='...'`. In FFmpeg `drawtext`, `borderw` is a hard outline stroke, NOT a drop shadow (`shadowx`/`shadowy`/`shadowcolor`).
- **Root-Cause Hypothesis:** Complete semantic mismatch and miswiring. The UI promises "Shadow", the preview applies a pseudo-outline via `setShadowLayer`, and export produces a solid stroke outline via `borderw`. There is no parameter or UI for shadow offset or shadow color.
- **Fix Effort:** `S`

#### 2.8 Letter Spacing
- **Status:** `UNSTABLE` (Partially Broken)
- **Evidence:** 
  1. In `VideoEditingActivity.kt:1235-1238`, `seekLetterSpacing` calls `draggableTextOverlay?.setLetterSpacing((value - 50f) / 100f)`.
  2. In preview, `DraggableTextOverlayView.kt:347-350` sets `editText.letterSpacing = spacing`.
  3. In export (`VideoEditingViewModel.kt:192-237`), `buildDrawtextExpr()` accepts `op: EditOperation.AddText`, but `op.letterSpacing` is NEVER referenced or included in the returned FFmpeg `drawtext` string.
- **Root-Cause Hypothesis:** Missing export wiring. Letter spacing is visible during interactive preview, but completely stripped out upon rendering the final export.
- **Fix Effort:** `S`

#### 2.9 Line Spacing
- **Status:** `WORKS`
- **Evidence:** `seekLineSpacing` calls `draggableTextOverlay?.setLineSpacing((value - 50f) * 2f)` for preview, and `VideoEditingViewModel.kt:234` appends `:line_spacing=${op.lineSpacing.toInt()}` to the `drawtext` filter for export.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 2.10 Alignment
- **Status:** `WORKS`
- **Evidence:** `btnAlignLeft`, `btnAlignCenter`, `btnAlignRight` update `draggableTextOverlay?.setTextAlign()`. In export (`VideoEditingViewModel.kt:233`), `:text_align=${op.textAlign}` is correctly passed to FFmpeg.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 2.11 Duplicate Text
- **Status:** `BROKEN` (Seed Issue Confirmed)
- **Evidence:** 
  1. In `text_editing_toolbar.xml`, `btnTextDuplicate` calls `duplicateSelectedOverlay()` (`VideoEditingActivity.kt:3059-3102`).
  2. If the user adds a new text overlay and taps Duplicate before exiting, `viewModel.selectedOperationId.value` is null, so `duplicateSelectedOverlay()` immediately returns at line 3060 without doing anything.
  3. If an existing text overlay is selected, `duplicateSelectedOverlay()` creates `newOp`, adds it via `viewModel.addOperation(newOp)`, and immediately calls `viewModel.selectOperation(newOpId)`.
  4. The observer `selectedOperationId.observe` (line 2240) fires on `newOpId` and invokes `enterTextEditingMode(isReEditing = true)`.
  5. `enterTextEditingMode` calls `closeActiveEditingModes()` (line 3028), which observes `isTextEditingActive == true` and invokes `draggableTextOverlay?.commitText()`.
  6. `commitText()` calls `onTextCommitted` (lines 1089–1120), which queries `viewModel.selectedOperationId.value`. However, `selectedOperationId` was ALREADY set to `newOpId`. It therefore overwrites the newly created duplicate with the old text's state!
  7. `commitText()` then calls `deactivate()` (line 300), which sets `draggableTextOverlay.visibility = View.GONE`.
- **Root-Cause Hypothesis:** Race condition and lifecycle conflict. Selecting the new duplicate while an active text session is open forces `commitText()` to fire against the newly selected ID, destroying the duplicate's separate identity and deactivating the overlay view.
- **Fix Effort:** `M`

#### 2.12 Keyframes
- **Status:** `WORKS`
- **Evidence:** `btnTextKeyframe` opens `keyframe_editing_toolbar.xml`. Users can scrub timeline and add keyframe points for Position and Opacity. In export, `VideoEditingViewModel.kt:212-215` generates FFmpeg mathematical piecewise interpolation expressions (`buildFFmpegInterpolationExpr()`) mapping `t` against timeline millisecond coordinates.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`M`).

#### 2.13 Delete Text
- **Status:** `WORKS`
- **Evidence:** `btnTextDelete` prompts confirmation, deactivates `draggableTextOverlay`, calls `viewModel.removeOperation(id)`, and hides the toolbar.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

---

### 3. Audio Tracks

#### 3.1 Volume Slider
- **Status:** `BROKEN` (Seed Issue Confirmed)
- **Evidence:**
  1. **Preview Breakdown:** In `VideoEditingActivity.kt:1741-1753`, `audioVolumeSlider?.addOnChangeListener` sets `bgAudioPlayerMap[id]?.volume = pVol`. However, during playback, `updateProgress()` periodically invokes `syncBgAudio()` every 50ms (lines 6690–6740). At line 6715:
     ```kotlin
     val effectiveVol = if (isMasterMuted) 0f else op.volume.coerceIn(0f, 1f)
     if (bgPlayer.volume != effectiveVol) {
         bgPlayer.volume = effectiveVol
     }
     ```
     Because `addOnChangeListener` does NOT update `op.volume` in `viewModel.project` (only `onStopTrackingTouch` does), `syncBgAudio` continuously resets `bgPlayer.volume` back to the old `op.volume` (1.0f / 100%) every 50ms during preview playback!
  2. **Export Breakdown:** When the user closes the audio editing toolbar by tapping Done (`btnAudioDone`) or Cancel, `exitAudioEditingMode()` is executed (lines 4400–4416). Line 4416 immediately calls `viewModel.selectOperation(null)`. The observer `selectedOperationId.observe` (lines 2254–2260) detects the null ID and invokes `exitAudioEditingMode()` a second time. Inside `exitAudioEditingMode`:
     ```kotlin
     viewModel.selectedOperationId.value?.let { id ->
         val op = project?.operations?.find { ... }
         if (op != null && op.volume != value) {
             viewModel.updateOperation(op.copy(volume = value))
         }
     }
     ```
     Because `selectedOperationId.value` was already cleared to null by `selectOperation(null)`, the update block is completely bypassed, and the volume change is never persisted to the project!
  3. **Main Video Clip Volume:** There is no volume slider for main track video clips in `video_editing_toolbar.xml`; only a binary Mute toggle (`btnVideoMute`) exists.
- **Root-Cause Hypothesis:** 
  - Preview: `syncBgAudio` polls uncommitted model state rather than current slider value.
  - Export: `exitAudioEditingMode()` is called after `selectedOperationId` is cleared, dropping the dirty slider value.
- **Fix Effort:** `M`

#### 3.2 Trim Track
- **Status:** `WORKS`
- **Evidence:** `audioTrimTrack` (`TrackTrimView`) displays audio waveform extracted via `AudioWaveformExtractor.extractWaveform()`. Users drag left/right handles to set `internalStartMs` and `internalEndMs`. In export (`VideoEditingViewModel.kt:1243-1249`), `atrim=start=...:end=...` is applied.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 3.3 Fade In / Fade Out
- **Status:** `UNSTABLE`
- **Evidence:** 
  1. In `VideoEditingActivity.kt:4430-4485`, `showFadeDialog()` sets `fadeInDurationMs` and `fadeOutDurationMs` via dual sliders.
  2. In export (`VideoEditingViewModel.kt:1250-1265`), `afade=t=in:st=0:d=...` and `afade=t=out:st=...:d=...` are accurately added to the FFmpeg filter chain.
  3. In preview playback (`VideoEditingActivity.kt:6690-6740`), ExoPlayer does not apply fade curves; `bgPlayer` plays at static volume throughout the entire duration.
- **Root-Cause Hypothesis:** Missing preview simulation. Fades are exported correctly to FFmpeg, but are completely silent/unrendered in real-time timeline playback.
- **Fix Effort:** `M`

#### 3.4 Audio Ducking
- **Status:** `UNSTABLE`
- **Evidence:** 
  1. `btnAudioDucking` toggles `op.ducking` boolean.
  2. In export (`VideoEditingViewModel.kt:1284-1305` and `1517-1536`), sidechain compression (`sidechaincompress=threshold=0.03:ratio=4:attack=5:release=500`) is configured between the main video audio stream and background music.
  3. In preview playback (`syncBgAudio`), no volume ducking is implemented between `player` and `bgPlayer`.
- **Root-Cause Hypothesis:** Preview player lacks real-time sidechain volume attenuation. Works exclusively upon export.
- **Fix Effort:** `M`

#### 3.5 Beats Detection
- **Status:** `WORKS`
- **Evidence:** `btnAudioBeats` calls `AudioAnalyzer.detectBeats()`. `AudioAnalyzer.kt:16-117` converts audio to mono 8kHz PCM via FFmpeg, calculates energy windows, performs peak picking with moving averages, and returns millisecond timestamps. `TrackTrimView` renders yellow beat marker dots across the audio track.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 3.6 Delete Audio
- **Status:** `WORKS`
- **Evidence:** `btnAudioDelete` prompts user confirmation and calls `viewModel.deleteOperation(id)`, removing the track from the project and timeline view.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 3.7 Voice Recording
- **Status:** `WORKS`
- **Evidence:** `voiceOverAction()` checks `RECORD_AUDIO` permission, opens `dialog_voice_over`, records AAC/M4A audio via `MediaRecorder` while simultaneously playing the video timeline, and inserts the recorded file as an `AddBackgroundAudio` operation aligned with `voiceOverStartTimeMs`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

---

### 4. Subtitles

#### 4.1 .SRT File Import
- **Status:** `WORKS`
- **Evidence:** `VideoEditingActivity.kt:5380-5425` opens file picker for `.srt` files. `SubtitleParser.parse(content)` parses timecodes (`00:00:01,000 --> 00:00:04,000`) and text lines into `SubtitleCue` objects. Creates `EditOperation.AddSubtitles` and renders cues in `TextOverlayView`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 4.2 Auto-Captions Display & Edit
- **Status:** `WORKS`
- **Evidence:** Auto Assemble and `startAutoCaptionsFlow()` generate caption cues via `CaptionCueBuilder` using Whisper transcription timestamps. Auto-captions are assigned `groupId = CaptionCueBuilder.CAPTION_GROUP_ID` and rendered onto a dedicated `CaptionTrackView` (lines 7677–7706). Tapping a caption segment seeks to its start timestamp, selects the cue, and dragging trim handles invokes `onCaptionTrimChanged` to adjust start/end times.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`M`).

---

### 5. Transitions

#### 5.1 Apply Between Clips
- **Status:** `UNSTABLE` (Seed Issue Confirmed)
- **Evidence:** `showTransitionDialog(transitionIndex)` (`VideoEditingActivity.kt:8148-8230`) lists 20 transition types. Selecting a transition calls `applyTransitionToIndex(idx, transType, activeDurationMs)`.
- **Root-Cause Hypothesis:** Transitions store cut location as a raw `index` integer (`transOp.index`). When clips are split, deleted, or re-assembled, these indices do not shift, causing transitions to be applied to wrong boundaries or become orphaned.
- **Fix Effort:** `M`

#### 5.2 Animated Toolbar Preview
- **Status:** `UNSTABLE`
- **Evidence:** In `VideoEditingActivity.kt:8255-8280`, each transition option attempts to create an `AnimationDrawable` by dynamically querying resources via `resources.getIdentifier("trans_preview_${type}_1", "drawable", packageName)`.
- **Root-Cause Hypothesis:** 
  1. While webp preview assets exist for many transitions, name mismatches exist between dialog keys (`smoothleft`, `distance`) and asset names, resulting in missing frames (`frame1Res == 0`) and falling back to a plain text label.
  2. Calling `getIdentifier()` repeatedly in a loop on the UI thread causes layout hitching.
- **Fix Effort:** `S`

#### 5.3 Preview Playback
- **Status:** `BROKEN` (Seed Issue Confirmed)
- **Evidence:** 
  1. In `VideoEditingActivity.kt:6593-6640`, during playback progress updates:
     ```kotlin
     val windowMs = 1000L // HARDCODED! Ignores transOp.durationMs completely!
     ```
  2. Lines 6613–6621 attempt to grab a bitmap from ExoPlayer's `TextureView` on the fly when the playhead enters the window:
     ```kotlin
     cachedTransitionBitmap = textureView.getBitmap(textureView.width / 2, textureView.height / 2)
     ```
     If the user scrubs or if the frame has already transitioned to clip B, `cachedTransitionBitmap` captures clip B rather than clip A, resulting in jarring visual flashes or null bitmaps.
  3. Line 6634 calls `cachedTransitionBitmap?.recycle()` while `TransitionPreviewOverlayView.onDraw()` may be in the middle of executing on the UI thread, risking `Canvas: trying to use a recycled bitmap` crashes.
  4. The timeline sequence seeker does not compress sequence duration for transition overlap; hence, timeline progress drifts out of sync with actual playback.
- **Root-Cause Hypothesis:** Transition preview relies on fragile `TextureView.getBitmap()` screen-grabbing and hardcoded 1000ms duration instead of a dual-player or shader-based composition pipeline.
- **Fix Effort:** `L`

#### 5.4 Export Rendering
- **Status:** `BROKEN` (Seed Issue Confirmed)
- **Evidence:** 
  In `VideoEditingViewModel.kt:1083-1178`:
  1. Consecutive transitions chain FFmpeg `xfade` filters:
     ```kotlin
     filterParts.add("${baseStream.vLabel}${nextVLabel}xfade=transition=${sanitizedTransType}:duration=${transDuration}:offset=${offset}${xfvOut}")
     ```
     When two or more transitions occur in sequence, `baseStream.vLabel` is the output of the previous `xfade` (e.g. `[xfv0]`). However, unlike `flushPendingGroup` (which normalizes timestamps with `settb=1/30,setpts=N`), the output of an `xfade` filter does NOT have its timestamps reset before being piped into the next `xfade`.
  2. Chaining multiple `xfade` and `acrossfade` filters without timestamp normalization causes non-monotonic PTS errors in FFmpeg, triggering encoding failure (`Conversion failed!` or empty video stream).
- **Root-Cause Hypothesis:** FFmpeg `xfade` filter graph does not normalize presentation timestamps between chained transitions.
- **Fix Effort:** `L`

#### 5.5 Duration Control
- **Status:** `UNSTABLE`
- **Evidence:** 
  1. In `dialog_transitions.xml`, `sliderTransitionDuration` has an `addOnChangeListener` (line 8182) that calls `applyTransitionToIndex(...)` on every micro-tick from touch drag (`fromUser == true`).
  2. Every micro-tick generates a new `UpdateOperationCommand` in the ViewModel undo stack (`_undoStack.value + historyState`), filling the entire 30-entry undo stack with minor duration adjustments in under a second.
  3. As proven in 5.3, the preview player ignores this duration completely (`windowMs = 1000L`).
- **Root-Cause Hypothesis:** Lack of slider debouncing / failure to use `addOnSliderTouchListener`, combined with hardcoded preview playback duration.
- **Fix Effort:** `S`

---

### 6. Overlays

#### 6.1 Image Overlays
- **Status:** `WORKS`
- **Evidence:** `DraggableImageOverlayView.kt` and `ImageOverlayView.kt` support positioning, multi-touch pinch-to-scale, rotation slider, mirror toggle, and start/end trimming. Exported via FFmpeg `overlay` filter in `VideoEditingViewModel.kt:538`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 6.2 Stickers Catalog
- **Status:** `FEATURE GAP`
- **Evidence:** No sticker catalog, emojis picker, or bundled sticker drawables exist in the codebase. Tapping overlay only permits picking user files from device storage via `MediaPickerBottomSheet`.
- **Root-Cause Hypothesis:** Feature was never implemented.
- **Fix Effort:** `M`

#### 6.3 GIFs
- **Status:** `WORKS`
- **Evidence:** Supported in `DraggableImageOverlayView.kt:353-365` (using Android P `ImageDecoder` / `AnimatedImageDrawable`) and `ImageOverlayView.kt:318-375`. Loops seamlessly during preview. Exported using FFmpeg `-stream_loop -1` or `generateVideoFromGif()`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 6.4 Video Overlays (PIP)
- **Status:** `WORKS`
- **Evidence:** Video files (`.mp4`, `.mov`, `.mkv`) imported as overlays are handled by `ImageOverlayView.kt` (using texture playback and frame decoders) and positioned with drag/scale/rotation handles. Exported via FFmpeg overlay inputs with `-stream_loop` if looping is active.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`M`).

#### 6.5 Masking
- **Status:** `WORKS`
- **Evidence:** `VideoMaskOverlayView.kt` and `bottom_sheet_mask.xml` support Linear Split, Shutter, Ellipse, Rectangle, Heart, and Star masks with inversion toggle and feathering slider. Export translates mask bounds to FFmpeg filter graph expressions in `VideoEditingViewModel.kt:239-270` (`buildFFmpegMaskAlphaExpr`).
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`M`).

#### 6.6 Chroma Key
- **Status:** `WORKS`
- **Evidence:** `showChromaKeyDialog()` (`VideoEditingActivity.kt:9276-9360`) allows picking key color (Green screen, Blue screen, or custom color via HSV picker and eyedropper) and tuning similarity threshold. Export applies FFmpeg `colorkey=${color}:${similarity}:0.1` (`VideoEditingViewModel.kt:492`).
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 6.7 Layer Reordering
- **Status:** `WORKS`
- **Evidence:** `btnMoveLayerUp` and `btnMoveLayerDown` (`VideoEditingActivity.kt:820-831`) call `viewModel.moveOverlayOperation(id, moveUp)`. `VideoEditingViewModelExt.kt:445-476` swaps the overlay's position in `project.operations`, reordering both the Compose/View z-order and FFmpeg overlay chaining order.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

---

### 7. Crop & Canvas Background

#### 7.1 Clip Crop
- **Status:** `WORKS`
- **Evidence:** `CropOverlayView.kt` provides draggable corner handles and presets (16:9, 9:16, 1:1, Custom). Preview uses `applyCropPreview()`. Export uses FFmpeg `crop=w:h:x:y` in `VideoEditingViewModel.kt:174-190`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 7.2 Canvas Background
- **Status:** `WORKS`
- **Evidence:** `background_editing_toolbar.xml` supports Color, Blur, and Image backgrounds. `updateCanvasBackgroundPreview()` sets background behind letterboxed video in preview. Export generates solid color pads (`color=c=...`), blurred background video (`boxblur=...`), or background image input in `VideoEditingViewModel.kt:1045-1065`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 7.3 Project Canvas Aspect Ratio
- **Status:** `FEATURE GAP` (Seed Issue Confirmed)
- **Evidence:** 
  The app allows cropping *individual clips*, but there is no feature or UI to set the global canvas aspect ratio of the project (e.g. converting a 16:9 landscape project into a 9:16 vertical TikTok/Reels project with letterbox/fit/fill canvas). Output resolution scale in `buildConsolidatedFFmpegCommand` (`scale=-2:${_exportResolution.value}`) is constrained to the source video dimensions.
- **Root-Cause Hypothesis:** The project model lacks a `canvasAspectRatio` property and corresponding UI selector.
- **Fix Effort:** `L`

---

### 8. Freehand Drawing

#### 8.1 Handwriting Canvas
- **Status:** `WORKS`
- **Evidence:** `HandwritingCanvasView.kt` intercepts touch events to draw smooth quadratic Bezier curves with customizable stroke width and color.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 8.2 Drawing Undo / Redo
- **Status:** `WORKS`
- **Evidence:** `HandwritingCanvasView.kt:undo()` and `redo()` maintain undo/redo path stacks and update button enabled states and alpha.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 8.3 Brush Size & Color
- **Status:** `WORKS`
- **Evidence:** `layout_handwriting_panel.xml` provides `sliderBrushSize` accompanied by `BrushSizeDotView` (dynamic live dot showing stroke diameter) and palette swatches with HSV color picker.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 8.4 Export Overlay Incorporation
- **Status:** `WORKS`
- **Evidence:** `saveAndCommitHandwriting()` (`VideoEditingActivity.kt:2935-2985`) bakes the drawn paths into a transparent PNG at video resolution, saves to `cacheDir/drawings/`, and adds an `EditOperation.AddImageOverlay` with `relativeWidth = 1.0f` and `relativeHeight = 1.0f`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

---

### 9. Export Pipeline

#### 9.1 Resolution Options
- **Status:** `WORKS`
- **Evidence:** `export_quality_bottom_sheet_dialog.xml` allows selecting 360p, 480p, 720p, 1080p, 1440p (2K), and 2160p (4K), plus FPS (24, 25, 30, 50, 60) and Audio-only toggle. Values are passed to `buildConsolidatedFFmpegCommand()`.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 9.2 Progress Reporting
- **Status:** `WORKS`
- **Evidence:** `ExportService.kt` hooks into `FFmpegKit` statistics callback (`statistics.time`), calculates progress against `totalDurationSecs`, and broadcasts percentage to foreground notification and `VideoEditingActivity` progress dialog.
- **Root-Cause Hypothesis:** Working as intended.
- **Fix Effort:** N/A (`S`).

#### 9.3 Output Correctness
- **Status:** `UNSTABLE`
- **Evidence:**
  1. Basic projects (single or merged clips, text, simple audio) render and export reliably to MediaStore gallery.
  2. When multiple transitions are used, export fails or produces a video without a visual stream due to chained `xfade` PTS desynchronization.
  3. When hardware encoder `h264_mediacodec` fails on certain chipsets, the software fallback to `libx264` triggers correctly (`FFmpegRenderEngine.kt:307-316`), but if complex filter graphs exceed shell command length, filter script fallback (`filter_script_*.txt`) can occasionally fail if temp files are cleaned up prematurely.
- **Root-Cause Hypothesis:** Filter graph complexity issues around transitions and PTS timestamps.
- **Fix Effort:** `L`

---

## Top 10 Most Damaging Items to Creator Workflow (Prioritized)

1. **Transitions Export Failure (PTS desync in chained `xfade`) [BROKEN - Effort: L]**
   - *Why #1:* When a creator adds transitions between assembled scenes, the final export fails completely or exports a frozen/black video stream due to missing timestamp normalization (`setpts=N`) between chained FFmpeg `xfade` filters. This renders multi-scene auto-assembled projects unexportable.

2. **Audio Volume Slider Ignored in Preview & Dropped on Exit [BROKEN - Effort: M]**
   - *Why #2:* Balancing background music against voiceover is an essential editing step. Because `syncBgAudio` continuously resets player volume to model volume every 50ms, and `exitAudioEditingMode` drops the uncommitted slider value due to `selectOperation(null)`, creators are completely unable to change background music volume in either preview or export.

3. **Transitions Preview Broken & TextureView Snapping [BROKEN - Effort: L]**
   - *Why #3:* The in-app transition preview hardcodes transition length to 1000ms (ignoring the user's duration slider) and attempts real-time screen-grabbing via `TextureView.getBitmap()`. Scrubbing or playing back across cuts causes wrong-frame snapshots, black flashes, and recycled bitmap crashes.

4. **Text Overlay Duplicate Destroys Selection & Hides Overlay [BROKEN - Effort: M]**
   - *Why #4:* Creators duplicating title styles or subtitle callouts find that clicking Duplicate either does nothing (on newly placed text) or overwrites the duplicate with the original's data and deactivates the overlay view due to a race condition between `addOperation`, `selectOperation`, and `closeActiveEditingModes`.

5. **Split Main Track Breaks Transition Indices [UNSTABLE - Effort: M]**
   - *Why #5:* Splitting a clip on the timeline shifts sequence indices without updating existing transition operations. Any cuts made after transitions have been configured silently detach transitions from their intended scene boundaries.

6. **Canvas Aspect Ratio Cannot Be Changed [FEATURE GAP - Effort: L]**
   - *Why #6:* Creators editing vertical content (Shorts, TikTok, Reels) from landscape footage or vice-versa cannot set a 9:16 canvas with letterboxing/background blur at the project level; the project is strictly locked to the primary input clip's aspect ratio.

7. **Reorder Clips on Main Track Missing [FEATURE GAP - Effort: L]**
   - *Why #7:* If Auto Assemble or manual import places scenes in suboptimal order, creators cannot drag or rearrange clips on the main timeline. They must delete and re-import clips manually from scratch.

8. **Text "Shadow" Slider Applies Outline Stroke Instead [BROKEN - Effort: S]**
   - *Why #8:* The format panel promises "Shadow", but wires to outline stroke (`borderThickness` / FFmpeg `borderw`). Creators attempting to make subtitles legible over bright backgrounds get an unintended solid border rather than a drop shadow.

9. **Letter Spacing Dropped on Export [UNSTABLE - Effort: S]**
   - *Why #9:* Creators tuning typography see custom letter spacing in the interactive preview, but the setting is omitted from `buildDrawtextExpr()`, resulting in exported videos that look noticeably different from the editor preview.

10. **Audio Fade & Ducking Inaudible in Preview [UNSTABLE - Effort: M]**
    - *Why #10:* Audio fades and sidechain ducking are implemented in export filter graphs, but completely absent during playback in ExoPlayer. Creators cannot audition music ducking under voiceover before exporting.

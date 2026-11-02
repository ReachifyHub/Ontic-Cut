package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.captions.CaptionCue
import com.boostlyte.vydra.captions.CaptionCueBuilder
import com.boostlyte.vydra.models.CanvasAspectRatio
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.SubtitleCue
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.utils.AppLogger
import com.boostlyte.vydra.utils.FFmpegFilterUtils
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for DEFECT A (resolution-independent cover/crop pipeline)
 * and DEFECT B (centralized drawtext validation, deduplication, and filter graph logging).
 */
class CoverCropAndCaptionGraphTest {

    @Test
    fun testComputeCoverCropChain_1376x768_againstAllExportResolutions() {
        val srcW = 1376
        val srcH = 768

        val resolutions = listOf(360, 480, 720, 1080, 1440, 2160)
        val ratios = listOf(
            CanvasAspectRatio.LANDSCAPE_16_9,
            CanvasAspectRatio.PORTRAIT_9_16,
            CanvasAspectRatio.SQUARE_1_1
        )

        for (ratio in ratios) {
            for (res in resolutions) {
                val (exportW, exportH) = ratio.getTargetDimensions(res)
                val chain = FFmpegFilterUtils.computeCoverCropChain(
                    srcW = srcW,
                    srcH = srcH,
                    canvasRatio = ratio,
                    exportW = exportW,
                    exportH = exportH,
                    fps = 30
                )

                assertEquals(4, chain.size)

                // (a) Scale to COVER canvas box
                val scaleStage = chain[0]
                assertTrue("Stage a must scale to cover: $scaleStage", scaleStage.startsWith("scale=w="))
                assertTrue("Stage a must use force_original_aspect_ratio=increase", scaleStage.contains("force_original_aspect_ratio=increase"))
                assertTrue("Stage a must use export dimensions: $scaleStage", scaleStage.contains("w=$exportW:h=$exportH"))

                // (b) Crop to EXACT canvas box dimensions (centered)
                val cropStage = chain[1]
                assertTrue("Stage b must crop: $cropStage", cropStage.startsWith("crop="))
                assertTrue("Stage b must crop to targetW:targetH: $cropStage", cropStage.startsWith("crop=$exportW:$exportH"))
                assertTrue("Stage b must be centered: $cropStage", cropStage.contains("(in_w-$exportW)/2:(in_h-$exportH)/2"))

                // (c) Scale to final export resolution
                val finalScaleStage = chain[2]
                assertEquals("scale=$exportW:$exportH", finalScaleStage)

                // (d) setsar=1, fps normalization
                val normStage = chain[3]
                assertEquals("setsar=1,fps=30,format=yuv420p", normStage)
            }
        }
    }

    @Test
    fun testCoverCropChainPreventsCropExceedingFrame() {
        // Source input image: 1376x768 (as reported in device logs)
        val srcW = 1376
        val srcH = 768
        val exportW = 1920
        val exportH = 1080

        val chain = FFmpegFilterUtils.computeCoverCropChain(
            srcW = srcW,
            srcH = srcH,
            canvasRatio = CanvasAspectRatio.LANDSCAPE_16_9,
            exportW = exportW,
            exportH = exportH,
            fps = 60
        )

        // Stage 0 scales with increase: iw becomes 1935, ih becomes 1080 (both >= 1920x1080)
        // Stage 1 crops to 1920x1080, which is strictly <= 1935x1080
        val cropStage = chain[1]
        val regex = Regex("""crop=(\d+):(\d+)""")
        val match = regex.find(cropStage)
        assertNotNull(match)
        val cropW = match!!.groupValues[1].toInt()
        val cropH = match.groupValues[2].toInt()

        assertEquals(exportW, cropW)
        assertEquals(exportH, cropH)
        assertTrue("FPS normalization must use specified fps", chain[3].contains("fps=60"))
    }

    @Test
    fun testBuildValidatedDrawtextFilter_containsAllRequiredParameters() {
        val filter = FFmpegFilterUtils.buildValidatedDrawtextFilter(
            text = "Hello Vydra!",
            fontFilePath = "/path/to/Roboto-Regular.ttf",
            fontSize = 24,
            fontColor = "white",
            x = "(w-tw)/2",
            y = "h-th-40",
            startTimeMs = 1000L,
            endTimeMs = 3000L,
            boxColor = "black@0.5",
            boxBorderW = 4
        )

        // Must satisfy all criteria from Defect B:
        assertTrue("Filter must start with drawtext=expansion=none", filter.startsWith("drawtext=expansion=none:"))
        assertTrue("Filter must contain fontfile=", filter.contains("fontfile='/path/to/Roboto-Regular.ttf'"))
        assertTrue("Filter must contain text=", filter.contains("text='Hello Vydra!'"))
        assertTrue("Filter must contain fontcolor=", filter.contains("fontcolor=white"))
        assertTrue("Filter must contain fontsize=", filter.contains("fontsize=24"))
        assertTrue("Filter must contain explicit x=", filter.contains(":x=(w-tw)/2"))
        assertTrue("Filter must contain explicit y=", filter.contains(":y=h-th-40"))
        assertTrue("Filter must contain escaped enable=", filter.contains(":enable='between(t\\,1.000\\,3.000)'"))
        assertTrue("Filter validation method must pass", FFmpegFilterUtils.validateDrawtextFilter(filter))
    }

    @Test
    fun testValidateDrawtextFilter_rejectsIncompleteFilters() {
        // Incomplete filter missing x and y (the root cause of stray top-left text)
        val brokenFilter = "drawtext=expansion=none:text='Missing pos':fontcolor=white:fontsize=24"
        assertFalse(FFmpegFilterUtils.validateDrawtextFilter(brokenFilter))

        // Incomplete filter missing fontfile
        val missingFont = "drawtext=expansion=none:text='No font':fontcolor=white:fontsize=24:x=0:y=0:enable='gte(t,0)'"
        assertFalse(FFmpegFilterUtils.validateDrawtextFilter(missingFont))

        // Incomplete filter missing enable
        val missingEnable = "drawtext=expansion=none:fontfile='font.ttf':text='No enable':fontcolor=white:fontsize=24:x=0:y=0"
        assertFalse(FFmpegFilterUtils.validateDrawtextFilter(missingEnable))
    }

    @Test
    fun testCaptionDeduplicationInExportPipeline() {
        // Project containing both an SRT subtitle track AND auto-caption AddText overlays
        val srtCues = listOf(
            SubtitleCue(0L, 2000L, "Subtitle Cue 1"),
            SubtitleCue(2000L, 4000L, "Subtitle Cue 2")
        )
        val addSubtitlesOp = EditOperation.AddSubtitles(
            subtitlesUri = DummyUri.fromPath("/tmp/sub.srt"),
            srtContent = "dummy",
            fileName = "sub.srt",
            cues = srtCues,
            fontSize = 24
        )

        val autoCaptionOps = CaptionCueBuilder.cuesToEditOperations(
            listOf(
                CaptionCue(0L, 2000L, "Subtitle Cue 1"),
                CaptionCue(2000L, 4000L, "Subtitle Cue 2")
            )
        )

        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/video.mp4"),
            sourceName = "video.mp4",
            operations = listOf(addSubtitlesOp) + autoCaptionOps
        )

        val viewModel = VideoEditingViewModel()
        viewModel.loadProject(project)

        val cmd = viewModel.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/tmp/video.mp4",
            outputFilePath = "/tmp/output.mp4",
            fontFilePath = "/tmp/Roboto.ttf"
        )
        assertNotNull(cmd)

        // Count how many drawtext filters exist in the generated command
        val drawtextMatches = Regex("""drawtext=expansion=none:""").findAll(cmd!!)
        val count = drawtextMatches.count()

        // Exactly 2 drawtext filters (one for each subtitle cue), not 4 (duplicate auto-captions deduplicated)
        assertEquals(2, count)

        // Verify every emitted drawtext filter contains fontfile, fontsize, fontcolor, x, y, and enable
        val filterMatches = Regex("""drawtext=expansion=none:[^;\[]+""").findAll(cmd)
        for (match in filterMatches) {
            val filterString = match.value.trimEnd(']')
            assertTrue(
                "Emitted drawtext filter must be fully validated: $filterString",
                FFmpegFilterUtils.validateDrawtextFilter(filterString)
            )
        }
    }

    @Test
    fun testOnlyEmitPresetForLibx264AndNotHardwareEncoder() {
        val viewModel = VideoEditingViewModel()
        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/video.mp4"),
            sourceName = "video.mp4"
        )
        viewModel.loadProject(project)

        val cmd = viewModel.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/tmp/video.mp4",
            outputFilePath = "/tmp/output.mp4"
        )
        assertNotNull(cmd)

        if (cmd!!.contains("h264_mediacodec")) {
            assertFalse("h264_mediacodec must NOT include -preset", cmd.contains("-preset"))
        } else if (cmd.contains("libx264")) {
            assertTrue("libx264 must include -preset veryfast", cmd.contains("-preset veryfast"))
        }
    }
}

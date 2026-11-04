package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.captions.CaptionCue
import com.boostlyte.vydra.captions.CaptionCueBuilder
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.SubtitleCue
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Export Filter Stabilization:
 * 1. Stray transcript / script text suppression when subtitles are active.
 * 2. Unpositioned unbounded long script text card suppression at top of screen.
 * 3. Ken Burns image input framerate and zoompan formula matching export FPS (24, 30, 60 fps).
 */
class ExportFilterStabilizationTest {

    @Test
    fun testExportFilterGraph_suppressesStrayTranscriptTextMatchingSubtitles() {
        val srtCues = listOf(
            SubtitleCue(0L, 2500L, "Welcome to the video narration transcript"),
            SubtitleCue(2500L, 5000L, "Here is the second line of the transcript")
        )
        val subtitlesOp = EditOperation.AddSubtitles(
            subtitlesUri = DummyUri.fromPath("/tmp/subs.srt"),
            srtContent = "dummy",
            fileName = "subs.srt",
            cues = srtCues,
            fontSize = 24
        )

        // Stray transcript text card matching the narration transcript
        val strayTranscriptText = EditOperation.AddText(
            text = "Welcome to the video narration transcript",
            fontSize = 24,
            position = TextPosition.TOP_CENTER,
            startTimeMs = 0L,
            endTimeMs = 2500L,
            groupId = null
        )

        // Legitimate user overlay with custom positioning and non-transcript text
        val legitOverlay = EditOperation.AddText(
            text = "Episode 1",
            fontSize = 32,
            position = TextPosition.TOP_LEFT,
            startTimeMs = 500L,
            endTimeMs = 3000L,
            relativeX = 0.1f,
            relativeY = 0.1f,
            groupId = null
        )

        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source.mp4"),
            sourceName = "source.mp4",
            operations = listOf(subtitlesOp, strayTranscriptText, legitOverlay)
        )

        val viewModel = VideoEditingViewModel()
        viewModel.loadProject(project)

        val cmd = viewModel.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/tmp/source.mp4",
            outputFilePath = "/tmp/output.mp4",
            fontFilePath = "/tmp/Roboto.ttf"
        )
        assertNotNull(cmd)

        // 2 subtitle cues + 1 legit overlay = 3 drawtext filters total.
        // The duplicate stray transcript text overlay must be completely suppressed!
        val drawtextCount = Regex("""drawtext=expansion=none:""").findAll(cmd!!).count()
        assertEquals(3, drawtextCount)
        assertTrue("Legitimate overlay must be preserved", cmd.contains("text='Episode 1'"))
    }

    @Test
    fun testExportFilterGraph_suppressesUnpositionedLongScriptCardAtTop() {
        // An unpositioned long narration script card with no bounds (spans full video)
        val longScriptCard = EditOperation.AddText(
            text = "This is a very long narration script that was automatically transcribed and has no custom position or start/end timestamp bounds",
            fontSize = 24,
            position = TextPosition.TOP_CENTER,
            startTimeMs = null,
            endTimeMs = null,
            groupId = null
        )

        // A legitimate title card with explicit custom position
        val legitTitle = EditOperation.AddText(
            text = "MY TRAVEL VLOG",
            fontSize = 36,
            position = TextPosition.CENTER,
            startTimeMs = 0L,
            endTimeMs = 4000L,
            relativeX = 0.5f,
            relativeY = 0.5f,
            groupId = null
        )

        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source.mp4"),
            sourceName = "source.mp4",
            operations = listOf(longScriptCard, legitTitle)
        )

        val viewModel = VideoEditingViewModel()
        viewModel.loadProject(project)

        val cmd = viewModel.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/tmp/source.mp4",
            outputFilePath = "/tmp/output.mp4",
            fontFilePath = "/tmp/Roboto.ttf"
        )
        assertNotNull(cmd)

        // Only the legitimate title should be drawn, not the long unbounded script card
        val drawtextCount = Regex("""drawtext=expansion=none:""").findAll(cmd!!).count()
        assertEquals(1, drawtextCount)
        assertTrue("Legit title must be present", cmd.contains("text='MY TRAVEL VLOG'"))
        assertFalse("Long script card must not be burned at top", cmd.contains("automatically transcribed"))
    }

    @Test
    fun testExportFilterGraph_kenBurnsFramerateMatchesExportFps() {
        val viewModel = VideoEditingViewModel()
        viewModel.setExportSettings(resolution = 1080, fps = 60, audioOnly = false)

        val imageMergeItem = EditOperation.MergeItem(
            uri = DummyUri.fromPath("/tmp/photo.jpg"),
            durationMs = 4000L,
            isImage = true,
            motionEffect = "zoom_in"
        )
        val mergeOp = EditOperation.Merge(items = listOf(imageMergeItem))
        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source.jpg"),
            sourceName = "source.jpg",
            operations = listOf(mergeOp)
        )
        viewModel.loadProject(project)

        val cmd = viewModel.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/tmp/source.jpg",
            outputFilePath = "/tmp/output.mp4"
        )
        assertNotNull(cmd)

        // Verify image input framerate is explicitly 60 fps (matching _exportFps.value)
        assertTrue("Image inputs must use export framerate 60: $cmd", cmd!!.contains("-framerate 60"))
        assertFalse("Image inputs must NOT fall back to hardcoded 30 fps when 60 is selected", cmd.contains("-framerate 30"))

        // Verify zoompan formula uses 60 fps to calculate linear zoom progression without judder
        assertTrue("zoompan formula must use export fps 60", cmd.contains("(60*"))
        assertTrue("zoompan output must specify fps=60", cmd.contains("fps=60"))
    }

    @Test
    fun testChunkExportPlan_largeProjectActivatesChunkingAndPadsDuration() {
        val viewModel = VideoEditingViewModel()
        viewModel.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)

        // 30 clips (triggers chunking because 30 > 24)
        val clips = (1..30).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("/tmp/clip_$it.mp4"), durationMs = 2000L)
        }
        val mergeOp = EditOperation.Merge(
            items = clips
        )

        // Add background audio that extends beyond video (e.g. 70 seconds total, while 30 clips * 2s = 60s)
        val bgAudio = EditOperation.AddBackgroundAudio(
            audioUri = DummyUri.fromPath("/tmp/audio.mp3"),
            startTimeMs = 0L,
            endTimeMs = 70000L,
            originalDurationMs = 70000L
        )

        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source.mp4"),
            sourceName = "source.mp4",
            operations = listOf(mergeOp, bgAudio)
        )
        viewModel.loadProject(project)

        val plan = viewModel.buildChunkExportPlan(
            sourceFilePath = "/tmp/source.mp4",
            outputFilePath = "/tmp/final_export.mp4"
        )

        assertNotNull("Chunk export plan must be built", plan)
        assertTrue("Large project must be marked chunked", plan!!.isChunked)
        assertTrue("Chunk commands must not be empty", plan.chunkCommands.isNotEmpty())
        assertNotNull("Final pass command must be present", plan.finalPassCommand)

        // Verify total duration includes audio extension (padded to 70s, not cut short at 60s)
        assertEquals("Total export duration must match audio duration (70s)", 70.0, plan.totalDurationSecs, 0.5)

        // Verify final pass includes video padding for audio
        assertTrue("Final pass filtergraph must contain tpad to freeze last frame", plan.finalPassCommand!!.contains("tpad=stop_mode=clone"))
        assertTrue("Final pass must output to /tmp/final_export.mp4", plan.finalPassCommand!!.contains("/tmp/final_export.mp4"))
    }
}


package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.SubtitleCue
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.services.ChunkPlanner
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.*
import org.junit.Test

class ResolutionAndAssemblyFixTest {

    private val viewModel = VideoEditingViewModel()

    @Test
    fun testKenBurnsResolutionScaling_smoothMotionAt720pAnd480p() {
        val ops = listOf(
            EditOperation.MotionEffect(index = 0, motionType = "zoom_in")
        )
        val dummyItem = EditOperation.MergeItem(uri = DummyUri.fromPath("/clip0.jpg"), durationMs = 3000L, isImage = true)
        val chunkSpec = com.boostlyte.vydra.services.ChunkSpec(
            chunkIndex = 0,
            clips = listOf(com.boostlyte.vydra.services.ChunkClip(0, 0, dummyItem, "/clip0.jpg", "/clip0.jpg")),
            internalTransitions = emptyList(),
            globalStartMs = 0L,
            globalEndMs = 3000L,
            durationMs = 3000L
        )

        // 1080p
        val cmd1080 = viewModel.buildChunkPassCommand(
            chunkSpec = chunkSpec,
            sourceFilePath = "/clip0.jpg",
            chunkOutputPath = "/out1080.mp4",
            fontFilePath = null,
            context = null,
            totalDurationMs = 3000L,
            density = 2.0f,
            targetWidth = 1920,
            targetHeight = 1080,
            operations = ops
        )
        // 720p
        val cmd720 = viewModel.buildChunkPassCommand(
            chunkSpec = chunkSpec,
            sourceFilePath = "/clip0.jpg",
            chunkOutputPath = "/out720.mp4",
            fontFilePath = null,
            context = null,
            totalDurationMs = 3000L,
            density = 2.0f,
            targetWidth = 1280,
            targetHeight = 720,
            operations = ops
        )
        // 480p
        val cmd480 = viewModel.buildChunkPassCommand(
            chunkSpec = chunkSpec,
            sourceFilePath = "/clip0.jpg",
            chunkOutputPath = "/out480.mp4",
            fontFilePath = null,
            context = null,
            totalDurationMs = 3000L,
            density = 2.0f,
            targetWidth = 854,
            targetHeight = 480,
            operations = ops
        )

        // All 3 resolutions must use high-density coordinate space (>= 2208x1242) for zoompan
        assertTrue("1080p command must use motion dimensions", cmd1080.command.contains("scale=w=2208:h=1242") || cmd1080.command.contains("2208"))
        assertTrue("720p command must preserve 1080p coordinate density (motionW >= 2208)", cmd720.command.contains("scale=w=2208:h=1242") || cmd720.command.contains("2208"))
        assertTrue("480p command must preserve 1080p coordinate density (motionW >= 2208)", cmd480.command.contains("scale=w=2208:h=1242") || cmd480.command.contains("2208"))

        // And zoompan outputs to exact target resolution
        assertTrue("1080p zoompan outputs to 1920x1080", cmd1080.command.contains("s=1920x1080"))
        assertTrue("720p zoompan outputs to 1280x720", cmd720.command.contains("s=1280x720"))
        assertTrue("480p zoompan outputs to 854x480", cmd480.command.contains("s=854x480"))
    }

    @Test
    fun testSubtitleScalingProportionAcrossResolutions() {
        val cue = SubtitleCue(0L, 2000L, "Hello world subtitles")
        val subOp = EditOperation.AddSubtitles(
            subtitlesUri = DummyUri.fromPath("/dummy.srt"),
            srtContent = "1\n00:00:00,000 --> 00:00:02,000\nHello world subtitles",
            fileName = "captions.srt",
            cues = listOf(cue),
            fontSize = 22
        )

        val (stages1080, _) = viewModel.buildVideoFilterStages(
            operations = listOf(subOp),
            fontFilePath = null,
            density = 2.0f,
            sourceVideoHeight = 1080
        )
        val (stages720, _) = viewModel.buildVideoFilterStages(
            operations = listOf(subOp),
            fontFilePath = null,
            density = 2.0f,
            sourceVideoHeight = 720
        )
        val (stages480, _) = viewModel.buildVideoFilterStages(
            operations = listOf(subOp),
            fontFilePath = null,
            density = 2.0f,
            sourceVideoHeight = 480
        )

        val expr1080 = stages1080.first()
        val expr720 = stages720.first()
        val expr480 = stages480.first()

        val sizeRegex = Regex("fontsize=([0-9]+)")
        val size1080 = sizeRegex.find(expr1080)?.groupValues?.get(1)?.toInt() ?: 0
        val size720 = sizeRegex.find(expr720)?.groupValues?.get(1)?.toInt() ?: 0
        val size480 = sizeRegex.find(expr480)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue("1080p font size ($size1080) must be larger than 720p font size ($size720)", size1080 > size720)
        assertTrue("720p font size ($size720) must be larger than 480p font size ($size480)", size720 > size480)

        // Check relative proportions: fontSize / height should be approximately constant (~0.04)
        val ratio1080 = size1080.toFloat() / 1080f
        val ratio720 = size720.toFloat() / 720f
        val ratio480 = size480.toFloat() / 480f

        assertEquals("Ratio between 1080p and 720p must match within 0.005", ratio1080, ratio720, 0.005f)
        assertEquals("Ratio between 720p and 480p must match within 0.005", ratio720, ratio480, 0.005f)
    }

    @Test
    fun testUnwantedTopTextSuppressionWhenSubtitlesActive() {
        val cue = SubtitleCue(0L, 2000L, "Hello world subtitles")
        val subOp = EditOperation.AddSubtitles(
            subtitlesUri = DummyUri.fromPath("/dummy.srt"),
            srtContent = "1\n00:00:00,000 --> 00:00:02,000\nHello world subtitles",
            fileName = "captions.srt",
            cues = listOf(cue),
            fontSize = 22
        )
        // Full transcript or unpositioned top text
        val strayTranscriptText = EditOperation.AddText(
            text = "Hello world subtitles this is the full voiceover transcript that should not appear at top",
            fontSize = 22,
            position = TextPosition.TOP_CENTER,
            startTimeMs = 0L,
            endTimeMs = 5000L
        )
        val legacyAutoCaptionText = EditOperation.AddText(
            text = "Hello world subtitles",
            fontSize = 22,
            position = TextPosition.BOTTOM_CENTER,
            groupId = com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID,
            startTimeMs = 0L,
            endTimeMs = 2000L
        )
        // Legitimate user text with custom position
        val userCustomText = EditOperation.AddText(
            text = "User Watermark Title",
            fontSize = 18,
            position = TextPosition.TOP_RIGHT,
            relativeX = 0.8f,
            relativeY = 0.1f,
            startTimeMs = 0L,
            endTimeMs = 5000L
        )

        val (stages, _) = viewModel.buildVideoFilterStages(
            operations = listOf(subOp, strayTranscriptText, legacyAutoCaptionText, userCustomText),
            fontFilePath = null,
            sourceVideoHeight = 1080
        )

        val filterStr = stages.joinToString(";")
        // Subtitle cue must be present
        assertTrue("Subtitles must be rendered", filterStr.contains("Hello world subtitles"))
        // User custom positioned text must be present
        assertTrue("User custom positioned text must be rendered", filterStr.contains("User Watermark Title"))
        // Stray transcript text must be SUPPRESSED
        assertFalse("Stray transcript text must NOT be rendered at the top", filterStr.contains("full voiceover transcript"))
    }

    @Test
    fun testLargeProject64ImagesFinalPassFlatConcat() {
        val items64 = (1..64).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///clip_$it.jpg"), durationMs = 3000L, isImage = true)
        }
        val plan = ChunkPlanner.plan(items64, emptyList())
        assertEquals(7, plan.chunks.size)

        val tempPaths = (0 until 7).map { "/temp/chunk_$it.mp4" }
        val finalCmd = viewModel.buildFinalPassCommand(
            chunkPlan = plan,
            chunkTempPaths = tempPaths,
            outputFilePath = "/out/final.mp4",
            context = null,
            targetWidth = 1920,
            targetHeight = 1080,
            operations = emptyList()
        )

        // Verify flat concat filter is generated (not chained concat=n=2)
        assertTrue("Final pass must use single flat concat=n=7", finalCmd.command.contains("concat=n=7:v=1:a=0"))
        assertFalse("Final pass must not contain chained concat=n=2", finalCmd.command.contains("concat=n=2"))
    }
}

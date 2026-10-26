package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.captions.CaptionBuildResult
import com.boostlyte.vydra.captions.CaptionCueBuilder
import com.boostlyte.vydra.captions.CaptionTimestampMode
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.EditRecipe
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.transcription.models.TranscriptionResponse
import com.boostlyte.vydra.transcription.models.TranscriptionSegment
import com.boostlyte.vydra.transcription.models.TranscriptionWord
import com.boostlyte.vydra.utils.ProjectSerializer
import org.junit.Assert.*
import org.junit.Test

class AutoCaptionsTest {

    @Test
    fun testBuildCues_wordLevelTimestamps_extractsAccurateCues() {
        val words = listOf(
            TranscriptionWord("Welcome", start = 0.5, end = 0.9),
            TranscriptionWord("to", start = 1.0, end = 1.2),
            TranscriptionWord("our", start = 1.25, end = 1.4),
            TranscriptionWord("channel.", start = 1.45, end = 1.9),
            TranscriptionWord("Today", start = 2.2, end = 2.6),
            TranscriptionWord("we", start = 2.65, end = 2.8),
            TranscriptionWord("will", start = 2.85, end = 3.0),
            TranscriptionWord("discuss", start = 3.05, end = 3.4),
            TranscriptionWord("history.", start = 3.45, end = 3.9)
        )

        val transcription = TranscriptionResponse(
            text = "Welcome to our channel. Today we will discuss history.",
            words = words
        )

        val result = CaptionCueBuilder.buildCues(transcription, audioDurationMs = 5000L)
        assertTrue(result is CaptionBuildResult.Success)

        val success = result as CaptionBuildResult.Success
        assertEquals(CaptionTimestampMode.WORD_LEVEL, success.mode)
        assertEquals(2, success.cues.size)

        // Cue 1: "Welcome to our channel."
        assertEquals(500L, success.cues[0].startMs)
        assertEquals(1900L, success.cues[0].endMs)
        assertEquals("Welcome to our channel.", success.cues[0].text)

        // Cue 2: "Today we will discuss history." (wrapped to 2 lines)
        assertEquals(2200L, success.cues[1].startMs)
        assertEquals(3900L, success.cues[1].endMs)
        assertEquals("Today we will\ndiscuss history.", success.cues[1].text)
        assertEquals("Today we will discuss history.", success.cues[1].text.replace("\n", " "))
    }

    @Test
    fun testBuildCues_segmentBasedFallback_distributesTimingProportionally() {
        val segments = listOf(
            TranscriptionSegment(
                id = 1,
                start = 0.0,
                end = 6.0,
                text = "In this video we are going to explore space and time."
            )
        )

        val transcription = TranscriptionResponse(
            text = "In this video we are going to explore space and time.",
            segments = segments
        )

        val result = CaptionCueBuilder.buildCues(transcription, audioDurationMs = 6000L)
        assertTrue(result is CaptionBuildResult.Success)

        val success = result as CaptionBuildResult.Success
        assertEquals(CaptionTimestampMode.SEGMENT_BASED, success.mode)
        assertTrue(success.cues.size >= 2)

        // Verify timing continuity inside segment
        assertEquals(0L, success.cues.first().startMs)
        assertEquals(6000L, success.cues.last().endMs)

        // Verify no overlapping cues
        for (i in 0 until success.cues.size - 1) {
            assertTrue(success.cues[i].endMs <= success.cues[i + 1].startMs)
            assertTrue(success.cues[i].durationMs >= CaptionCueBuilder.MIN_CUE_DURATION_MS)
        }
    }

    @Test
    fun testBuildCues_emptyTranscript_returnsClearError() {
        val transcription = TranscriptionResponse(
            text = "   ",
            segments = emptyList(),
            words = emptyList()
        )

        val result = CaptionCueBuilder.buildCues(transcription, audioDurationMs = 5000L)
        assertTrue(result is CaptionBuildResult.Error)
        val err = (result as CaptionBuildResult.Error).message
        assertTrue(err.contains("empty", ignoreCase = true))
    }

    @Test
    fun testBuildCues_noUsableTimestamps_returnsClearError() {
        val transcription = TranscriptionResponse(
            text = "This transcript has plain text but absolutely zero timing info.",
            segments = null,
            words = null
        )

        val result = CaptionCueBuilder.buildCues(transcription, audioDurationMs = 5000L)
        assertTrue(result is CaptionBuildResult.Error)
        val err = (result as CaptionBuildResult.Error).message
        assertTrue(err.contains("no usable timing data", ignoreCase = true))
    }

    @Test
    fun testBuildCues_overlapEliminationAndAudioClamping() {
        val words = listOf(
            TranscriptionWord("Fast", start = 1.0, end = 2.5),
            TranscriptionWord("speech", start = 2.0, end = 3.5), // Overlaps with previous end
            TranscriptionWord("end", start = 4.0, end = 8.0)     // Extends past audio duration (5000ms)
        )

        val transcription = TranscriptionResponse(
            text = "Fast speech end",
            words = words
        )

        val result = CaptionCueBuilder.buildCues(transcription, audioDurationMs = 5000L)
        assertTrue(result is CaptionBuildResult.Success)

        val cues = (result as CaptionBuildResult.Success).cues
        assertTrue(cues.isNotEmpty())

        // Verify non-overlap
        for (i in 0 until cues.size - 1) {
            assertTrue(cues[i].endMs <= cues[i + 1].startMs)
        }

        // Verify all cues are clamped to audio duration
        for (cue in cues) {
            assertTrue(cue.endMs <= 5000L)
            assertTrue(cue.startMs >= 0L)
            assertTrue(cue.durationMs > 0L)
        }
    }

    @Test
    fun testCuesToEditOperations_defaultCaptionStyleProperties() {
        val cues = listOf(
            com.boostlyte.vydra.captions.CaptionCue(500L, 2000L, "Hello world")
        )

        val ops = CaptionCueBuilder.cuesToEditOperations(cues)
        assertEquals(1, ops.size)

        val op = ops[0]
        assertEquals("Hello world", op.text)
        assertEquals(CaptionCueBuilder.DEFAULT_FONT_SIZE, op.fontSize)
        assertEquals(TextPosition.BOTTOM_CENTER, op.position)
        assertEquals(0.5f, op.relativeX ?: 0f, 0.01f)
        assertEquals(0.85f, op.relativeY ?: 0f, 0.01f)
        assertEquals("#FFFFFF", op.color)
        assertEquals(3, op.borderThickness)
        assertEquals("#000000", op.borderColor)
        assertEquals("center", op.textAlign)
        assertEquals(500L, op.startTimeMs)
        assertEquals(2000L, op.endTimeMs)
        assertEquals("auto-captions", op.groupId)
    }

    @Test
    fun testWrapTextToTwoLines_wrapsAtCenterWordBoundary() {
        val singleShort = "Short caption"
        assertEquals("Short caption", CaptionCueBuilder.wrapTextToTwoLines(singleShort, thresholdChars = 24))

        val longText = "This is a longer sentence that needs to wrap cleanly"
        val wrapped = CaptionCueBuilder.wrapTextToTwoLines(longText, thresholdChars = 24)
        assertTrue(wrapped.contains("\n"))
        val lines = wrapped.split("\n")
        assertEquals(2, lines.size)
        assertEquals(longText, lines.joinToString(" "))
    }

    @Test
    fun testIdempotentRegeneration_preservesManualTextAndSubtitles() {
        val manualText = EditOperation.AddText(
            text = "User Watermark",
            fontSize = 18,
            position = TextPosition.TOP_RIGHT,
            groupId = null // Manual user overlay
        )

        val manualSubtitles = EditOperation.AddSubtitles(
            subtitlesUri = DummyUri.fromPath("file:///subtitles.srt"),
            srtContent = "1\n00:00:01,000 --> 00:00:02,000\nHello",
            fileName = "subtitles.srt",
            cues = listOf(com.boostlyte.vydra.models.SubtitleCue(1000L, 2000L, "Hello"))
        )

        val oldAutoCaption = EditOperation.AddText(
            text = "Old Caption",
            fontSize = 22,
            position = TextPosition.BOTTOM_CENTER,
            startTimeMs = 0L,
            endTimeMs = 1500L,
            groupId = "auto-captions"
        )

        val initialProject = VideoProject(
            sourceUri = DummyUri.fromPath("file:///video.mp4"),
            sourceName = "video.mp4",
            operations = listOf(manualText, manualSubtitles, oldAutoCaption)
        )

        // Regenerate new captions
        val newCues = listOf(
            com.boostlyte.vydra.captions.CaptionCue(0L, 1800L, "New Caption 1"),
            com.boostlyte.vydra.captions.CaptionCue(1800L, 3500L, "New Caption 2")
        )
        val newOps = CaptionCueBuilder.cuesToEditOperations(newCues)

        val cleanOps = initialProject.operations.filterNot {
            it is EditOperation.AddText && it.groupId == CaptionCueBuilder.CAPTION_GROUP_ID
        }
        val regeneratedProject = initialProject.copy(operations = cleanOps + newOps)

        // Assert: manual text overlay is preserved
        val textOps = regeneratedProject.operations.filterIsInstance<EditOperation.AddText>()
        assertEquals(3, textOps.size) // 1 manual watermark + 2 new auto captions
        assertTrue(textOps.any { it.text == "User Watermark" && it.groupId == null })

        // Assert: old auto caption was removed
        assertFalse(textOps.any { it.text == "Old Caption" })

        // Assert: new auto captions were injected
        assertTrue(textOps.any { it.text == "New Caption 1" && it.groupId == "auto-captions" })
        assertTrue(textOps.any { it.text == "New Caption 2" && it.groupId == "auto-captions" })

        // Assert: manual SRT subtitles are untouched
        val subOps = regeneratedProject.operations.filterIsInstance<EditOperation.AddSubtitles>()
        assertEquals(1, subOps.size)
        assertEquals("subtitles.srt", subOps[0].fileName)
    }

    @Test
    fun testRemoveAutoCaptions_removesOnlyAutoCaptions() {
        val manualText = EditOperation.AddText(
            text = "Title Card",
            fontSize = 32,
            position = TextPosition.CENTER,
            groupId = null
        )

        val autoCaption = EditOperation.AddText(
            text = "Spoken text",
            fontSize = 22,
            position = TextPosition.BOTTOM_CENTER,
            groupId = "auto-captions"
        )

        val project = VideoProject(
            sourceUri = DummyUri.fromPath("file:///video.mp4"),
            sourceName = "video.mp4",
            operations = listOf(manualText, autoCaption)
        )

        val cleaned = project.copy(
            operations = project.operations.filterNot {
                it is EditOperation.AddText && it.groupId == CaptionCueBuilder.CAPTION_GROUP_ID
            }
        )

        assertEquals(1, cleaned.operations.size)
        assertEquals("Title Card", (cleaned.operations[0] as EditOperation.AddText).text)
    }

    @Test
    fun testProjectSerialization_preservesCaptionGroupId() {
        val captionOp = EditOperation.AddText(
            text = "Serialized Caption",
            fontSize = 22,
            position = TextPosition.BOTTOM_CENTER,
            startTimeMs = 1000L,
            endTimeMs = 3000L,
            borderThickness = 3,
            borderColor = "#000000",
            groupId = "auto-captions"
        )

        val project = VideoProject(
            sourceUri = DummyUri.fromPath("file:///source.mp4"),
            sourceName = "source.mp4",
            operations = listOf(captionOp)
        )

        val recipe = EditRecipe.fromVideoProject("Project With Captions", project, durationMs = 5000L, clipCount = 1)
        val json = ProjectSerializer.serialize(recipe)
        assertNotNull(json)
        assertTrue(json.contains("\"groupId\":\"auto-captions\""))
        assertTrue(json.contains("\"text\":\"Serialized Caption\""))
        assertTrue(json.contains("\"borderThickness\":3"))
    }

    @Test
    fun testStress_300CuesPerformance() {
        val words = (1..1500).map { i ->
            TranscriptionWord(
                word = "word$i",
                start = (i - 1) * 0.4,
                end = i * 0.4
            )
        }

        val transcription = TranscriptionResponse(
            text = words.joinToString(" ") { it.word },
            words = words
        )

        val startNs = System.nanoTime()
        val result = CaptionCueBuilder.buildCues(transcription, audioDurationMs = 600000L)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        assertTrue(result is CaptionBuildResult.Success)
        val cues = (result as CaptionBuildResult.Success).cues
        assertTrue(cues.size >= 250)
        assertTrue("Expected 300 cues processing to take < 50ms, took ${elapsedMs}ms", elapsedMs < 50)
    }
}

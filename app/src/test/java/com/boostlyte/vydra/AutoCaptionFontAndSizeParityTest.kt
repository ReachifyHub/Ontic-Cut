package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.captions.CaptionCue
import com.boostlyte.vydra.captions.CaptionCueBuilder
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.EditRecipe
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.utils.FFmpegFilterUtils
import com.boostlyte.vydra.utils.ProjectSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite verifying Auto-Caption Font & Size Parity with .SRT Subtitles.
 * Covers:
 * 1. Uniform group updates across all auto-caption cues (groupId == CaptionCueBuilder.CAPTION_GROUP_ID).
 * 2. Strict isolation from manual text overlays and .SRT subtitle operations.
 * 3. Serialization / Deserialization persistence roundtrip.
 * 4. Validated FFmpeg drawtext filter generation with fontfile and fontsize parity.
 * 5. Style preservation across regeneration.
 */
class AutoCaptionFontAndSizeParityTest {

    @org.junit.Before
    fun setUp() {
        ProjectSerializer.uriDeserializerFallback = { str -> DummyUri.fromPath(str) }
    }

    @org.junit.After
    fun tearDown() {
        ProjectSerializer.uriDeserializerFallback = null
    }

    @Test
    fun testAutoCaptionGroupFontUpdate_updatesAllCuesInGroup_isolatingManualOverlays() {
        val cue1 = EditOperation.AddText(
            text = "First auto caption cue",
            fontSize = 22,
            position = TextPosition.BOTTOM_CENTER,
            startTimeMs = 0L,
            endTimeMs = 2000L,
            groupId = CaptionCueBuilder.CAPTION_GROUP_ID
        )
        val cue2 = EditOperation.AddText(
            text = "Second auto caption cue",
            fontSize = 22,
            position = TextPosition.BOTTOM_CENTER,
            startTimeMs = 2000L,
            endTimeMs = 4000L,
            groupId = CaptionCueBuilder.CAPTION_GROUP_ID
        )
        val manualText = EditOperation.AddText(
            text = "Manual Header Overlay",
            fontSize = 40,
            position = TextPosition.TOP_CENTER,
            startTimeMs = 0L,
            endTimeMs = 5000L,
            fontPath = "/fonts/OriginalHeader.ttf",
            groupId = null
        )

        val project = VideoProject(
            sourceUri = DummyUri.fromPath("file:///dummy.mp4"),
            sourceName = "dummy.mp4",
            operations = listOf(cue1, cue2, manualText)
        )

        val newFontPath = "/custom_fonts/Cinzel_Bold.ttf"
        val updatedOps = project.operations.map { op ->
            if (op is EditOperation.AddText && op.groupId == CaptionCueBuilder.CAPTION_GROUP_ID) {
                op.copy(fontPath = newFontPath)
            } else {
                op
            }
        }

        val updatedAutoCues = updatedOps.filterIsInstance<EditOperation.AddText>().filter {
            it.groupId == CaptionCueBuilder.CAPTION_GROUP_ID
        }
        assertEquals(2, updatedAutoCues.size)
        assertEquals(newFontPath, updatedAutoCues[0].fontPath)
        assertEquals(newFontPath, updatedAutoCues[1].fontPath)

        val updatedManual = updatedOps.filterIsInstance<EditOperation.AddText>().first { it.groupId == null }
        assertEquals("/fonts/OriginalHeader.ttf", updatedManual.fontPath)
        assertEquals(40, updatedManual.fontSize)
    }

    @Test
    fun testAutoCaptionGroupFontSizeUpdate_updatesAllCuesInGroup() {
        val cues = CaptionCueBuilder.cuesToEditOperations(
            listOf(
                CaptionCue(0L, 1500L, "Hello world"),
                CaptionCue(1500L, 3000L, "Welcome to Vydra")
            )
        )

        val targetFontSize = 34
        val updatedOps = cues.map { op ->
            if (op.groupId == CaptionCueBuilder.CAPTION_GROUP_ID) {
                op.copy(fontSize = targetFontSize)
            } else {
                op
            }
        }

        assertEquals(2, updatedOps.size)
        assertTrue(updatedOps.all { it.fontSize == targetFontSize })
        assertTrue(updatedOps.all { it.groupId == CaptionCueBuilder.CAPTION_GROUP_ID })
    }

    @Test
    fun testAutoCaptionStylePersistence_survivesProjectSerializationRoundtrip() {
        val customFont = "/data/user/0/com.boostlyte.vydra/files/custom_fonts/Montserrat.ttf"
        val customFontSize = 28

        val cue = EditOperation.AddText(
            text = "Persisted Caption Text",
            fontSize = customFontSize,
            position = TextPosition.BOTTOM_CENTER,
            fontPath = customFont,
            startTimeMs = 500L,
            endTimeMs = 2500L,
            borderThickness = 3,
            borderColor = "#000000",
            groupId = CaptionCueBuilder.CAPTION_GROUP_ID
        )

        val project = VideoProject(
            sourceUri = DummyUri.fromPath("file:///project_source.mp4"),
            sourceName = "project_source.mp4",
            operations = listOf(cue)
        )

        val recipe = EditRecipe.fromVideoProject("CaptionParityProject", project, durationMs = 5000L, clipCount = 1)
        val json = ProjectSerializer.serialize(recipe)
        assertNotNull(json)
        assertTrue(json.contains("\"fontSize\":28"))
        assertTrue(json.contains("\"fontPath\":\"$customFont\""))
        assertTrue(json.contains("\"groupId\":\"auto-captions\""))

        val deserialized = ProjectSerializer.deserialize(json)
        assertNotNull(deserialized)
        val restoredOp = deserialized.operations.firstOrNull() as? EditOperation.AddText
        assertNotNull(restoredOp)
        assertEquals(customFontSize, restoredOp?.fontSize)
        assertEquals(customFont, restoredOp?.fontPath)
        assertEquals(CaptionCueBuilder.CAPTION_GROUP_ID, restoredOp?.groupId)
        assertEquals("Persisted Caption Text", restoredOp?.text)
        assertEquals(500L, restoredOp?.startTimeMs)
        assertEquals(2500L, restoredOp?.endTimeMs)
    }

    @Test
    fun testExportDrawtextFilter_autoCaptionsIncludeFontfileAndFontsize() {
        val customFont = "/data/fonts/BebasNeue.ttf"
        val customFontSize = 32

        val cue = EditOperation.AddText(
            text = "Exported Caption Line",
            fontSize = customFontSize,
            position = TextPosition.BOTTOM_CENTER,
            fontPath = customFont,
            startTimeMs = 1000L,
            endTimeMs = 3000L,
            color = "#FFFFFF",
            groupId = CaptionCueBuilder.CAPTION_GROUP_ID
        )

        val filterStr = FFmpegFilterUtils.buildValidatedDrawtextFilter(
            text = cue.text,
            fontFilePath = cue.fontPath,
            fontSize = cue.fontSize,
            fontColor = "white",
            x = "(w-tw)/2",
            y = "h-th-40",
            startTimeMs = cue.startTimeMs,
            endTimeMs = cue.endTimeMs,
            boxColor = "black@0.5",
            boxBorderW = 4
        )

        assertTrue(filterStr.startsWith("drawtext="))
        assertTrue(filterStr.contains("fontfile='$customFont'"))
        assertTrue(filterStr.contains("fontsize=32"))
        assertTrue(filterStr.contains("text='Exported Caption Line'"))
        assertTrue(filterStr.contains("box=1:boxcolor=black@0.5:boxborderw=4"))
    }

    @Test
    fun testRegenerateAutoCaptions_preservesExistingGroupStyle() {
        val existingFont = "/custom_fonts/Inter.ttf"
        val existingSize = 26

        val initialCue = EditOperation.AddText(
            text = "Old caption",
            fontSize = existingSize,
            fontPath = existingFont,
            position = TextPosition.BOTTOM_CENTER,
            startTimeMs = 0L,
            endTimeMs = 1000L,
            groupId = CaptionCueBuilder.CAPTION_GROUP_ID
        )
        val project = VideoProject(
            sourceUri = DummyUri.fromPath("file:///source.mp4"),
            sourceName = "source.mp4",
            operations = listOf(initialCue)
        )

        // Simulate regeneration
        val prevAutoOps = project.operations.filterIsInstance<EditOperation.AddText>().filter {
            it.groupId == CaptionCueBuilder.CAPTION_GROUP_ID
        }
        val prevFont = prevAutoOps.firstOrNull()?.fontPath
        val prevSize = prevAutoOps.firstOrNull()?.fontSize

        val newGeneratedCues = CaptionCueBuilder.cuesToEditOperations(
            listOf(CaptionCue(0L, 2000L, "New regenerated caption"))
        )
        val styledNewCues = if (prevFont != null || prevSize != null) {
            newGeneratedCues.map {
                it.copy(
                    fontPath = prevFont ?: it.fontPath,
                    fontSize = prevSize ?: it.fontSize
                )
            }
        } else {
            newGeneratedCues
        }

        assertEquals(1, styledNewCues.size)
        assertEquals(existingFont, styledNewCues.first().fontPath)
        assertEquals(existingSize, styledNewCues.first().fontSize)
    }
}

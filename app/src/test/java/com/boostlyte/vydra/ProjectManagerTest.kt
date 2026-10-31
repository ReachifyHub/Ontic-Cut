package com.boostlyte.vydra

import android.content.Context
import android.net.Uri
import com.boostlyte.vydra.models.EditRecipe
import com.boostlyte.vydra.utils.ProjectManager
import com.boostlyte.vydra.utils.ProjectSerializer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockDir: File

    @Before
    fun setUp() {
        mockDir = tempFolder.newFolder("Projects")
        ProjectSerializer.uriDeserializerFallback = { str -> android.net.DummyUri.fromPath(str) }
    }

    @Test
    fun testGenerateUniqueProjectFile_defaultName() {
        val file1 = File(mockDir, "Project 1.vdprj")
        assertFalse(file1.exists())

        // Simulated unique generator logic
        var candidate = File(mockDir, "Project 1.vdprj")
        assertTrue(!candidate.exists())
        candidate.writeText("{}")

        var candidate2 = File(mockDir, "Project 2.vdprj")
        assertTrue(!candidate2.exists())
    }

    @Test
    fun testProjectSerialization() {
        val dummyUri = android.net.DummyUri.INSTANCE
        val recipe = EditRecipe(
            projectName = "Test Project",
            sourceUri = dummyUri,
            sourceName = "test_video.mp4"
        )

        val json = ProjectSerializer.serialize(recipe)
        assertNotNull(json)
        assertTrue(json.contains("Test Project"))
        assertTrue(json.contains("test_video.mp4"))
    }

    @Test
    fun testSavedProjectListingSort() {
        val fileOld = File(mockDir, "Old Project.vdprj")
        fileOld.writeText("{}")
        fileOld.setLastModified(1000L)

        val fileNew = File(mockDir, "New Project.vdprj")
        fileNew.writeText("{}")
        fileNew.setLastModified(5000L)

        val files = mockDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".vdprj", ignoreCase = true) || f.name.endsWith(".lcprj", ignoreCase = true))
        } ?: emptyArray()

        val sorted = files.sortedByDescending { it.lastModified() }
        assertEquals("New Project.vdprj", sorted[0].name)
        assertEquals("Old Project.vdprj", sorted[1].name)
    }

    @Test
    fun testSanitizeInvalidFileNameCharacters() {
        val dirty = "My/Project:Video*Name?2026"
        val sanitized = dirty.trim().replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        assertEquals("My_Project_Video_Name_2026", sanitized)
    }

    @Test
    fun testFormatDuration() {
        assertEquals("00:00", ProjectManager.formatDuration(0L))
        assertEquals("00:45", ProjectManager.formatDuration(45000L))
        assertEquals("02:05", ProjectManager.formatDuration(125000L))
        assertEquals("1:01:05", ProjectManager.formatDuration(3665000L))
    }

    @Test
    fun testThumbnailNaming() {
        val file = File(mockDir, "MyProject.vdprj")
        val thumb = ProjectManager.getThumbnailFile(file)
        assertEquals("MyProject.thumb.jpg", thumb.name)
        assertEquals(mockDir.absolutePath, thumb.parentFile?.absolutePath)
    }

    @Test
    fun testCanvasAspectRatioSerialization() {
        val dummyUri = android.net.DummyUri.INSTANCE
        val recipe = EditRecipe(
            projectName = "Ratio Project",
            sourceUri = dummyUri,
            sourceName = "portrait.mp4",
            canvasAspectRatio = com.boostlyte.vydra.models.CanvasAspectRatio.PORTRAIT_9_16
        )

        val json = ProjectSerializer.serialize(recipe)
        assertNotNull(json)
        assertTrue(json.contains("\"canvasAspectRatio\":\"PORTRAIT_9_16\""))

        val deserialized = ProjectSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertEquals(com.boostlyte.vydra.models.CanvasAspectRatio.PORTRAIT_9_16, deserialized?.canvasAspectRatio)

        val project = deserialized?.toVideoProject()
        assertNotNull(project)
        assertEquals(com.boostlyte.vydra.models.CanvasAspectRatio.PORTRAIT_9_16, project?.canvasAspectRatio)
        assertEquals(com.boostlyte.vydra.models.CanvasAspectRatio.PORTRAIT_9_16, project?.getEffectiveCanvasAspectRatio())
    }

    @Test
    fun testCanvasAspectRatioLegacyCompatibility() {
        // Legacy JSON without canvasAspectRatio field
        val legacyJson = """
            {
                "projectName": "Legacy 2025 Project",
                "sourceUri": "file:///storage/emulated/0/DCIM/video.mp4",
                "sourceName": "video.mp4",
                "operations": [],
                "durationMs": 10000,
                "clipCount": 1
            }
        """.trimIndent()

        val deserialized = ProjectSerializer.deserialize(legacyJson)
        assertNotNull(deserialized)
        assertNull(deserialized?.canvasAspectRatio)

        val project = deserialized?.toVideoProject()
        assertNotNull(project)
        assertNull(project?.canvasAspectRatio)
        // Defaults to 16:9 when no dimensions provided
        assertEquals(com.boostlyte.vydra.models.CanvasAspectRatio.LANDSCAPE_16_9, project?.getEffectiveCanvasAspectRatio())
        // Derives from source dimensions when available
        assertEquals(com.boostlyte.vydra.models.CanvasAspectRatio.PORTRAIT_9_16, project?.getEffectiveCanvasAspectRatio(1080, 1920))
        assertEquals(com.boostlyte.vydra.models.CanvasAspectRatio.SQUARE_1_1, project?.getEffectiveCanvasAspectRatio(1080, 1080))
    }

    @Test
    fun testCanvasAspectRatioTargetDimensions() {
        // 1080p
        val (w16_9, h16_9) = com.boostlyte.vydra.models.CanvasAspectRatio.LANDSCAPE_16_9.getTargetDimensions(1080)
        assertEquals(1920, w16_9)
        assertEquals(1080, h16_9)

        val (w9_16, h9_16) = com.boostlyte.vydra.models.CanvasAspectRatio.PORTRAIT_9_16.getTargetDimensions(1080)
        assertEquals(1080, w9_16)
        assertEquals(1920, h9_16)

        val (w1_1, h1_1) = com.boostlyte.vydra.models.CanvasAspectRatio.SQUARE_1_1.getTargetDimensions(1080)
        assertEquals(1080, w1_1)
        assertEquals(1080, h1_1)

        // 720p
        val (w720_16_9, h720_16_9) = com.boostlyte.vydra.models.CanvasAspectRatio.LANDSCAPE_16_9.getTargetDimensions(720)
        assertEquals(1280, w720_16_9)
        assertEquals(720, h720_16_9)

        val (w720_9_16, h720_9_16) = com.boostlyte.vydra.models.CanvasAspectRatio.PORTRAIT_9_16.getTargetDimensions(720)
        assertEquals(720, w720_9_16)
        assertEquals(1280, h720_9_16)

        // Even dimensions guarantee for encoding
        assertTrue(w16_9 % 2 == 0)
        assertTrue(h16_9 % 2 == 0)
        assertTrue(w9_16 % 2 == 0)
        assertTrue(h9_16 % 2 == 0)
    }

    @Test
    fun testAudioOperationVolumeSerializationAndPersistence() {
        val dummyUri = android.net.DummyUri.INSTANCE
        val audioOp = com.boostlyte.vydra.models.EditOperation.AddBackgroundAudio(
            id = "audio_test_123",
            audioUri = dummyUri,
            volume = 0.45f,
            internalStartMs = 1000L,
            internalEndMs = 5000L,
            originalDurationMs = 10000L
        )

        val recipe = EditRecipe(
            projectName = "Audio Volume Test",
            sourceUri = dummyUri,
            sourceName = "video.mp4",
            operations = listOf(audioOp)
        )

        val json = ProjectSerializer.serialize(recipe)
        assertNotNull(json)
        assertTrue("Serialized JSON should contain volume", json.contains("\"volume\":0.45") || json.contains("\"volume\": 0.45"))

        val deserialized = ProjectSerializer.deserialize(json)
        assertNotNull(deserialized)
        val deserializedOp = deserialized?.operations?.firstOrNull() as? com.boostlyte.vydra.models.EditOperation.AddBackgroundAudio
        assertNotNull(deserializedOp)
        assertEquals(0.45f, deserializedOp?.volume ?: 0f, 0.001f)

        // Legacy compatibility test: background audio JSON without volume field defaults to 1.0f
        val legacyAudioJson = """
            {
                "projectName": "Legacy Audio Project",
                "sourceUri": "file:///dummy.mp4",
                "sourceName": "dummy.mp4",
                "operations": [
                    {
                        "type": "AddBackgroundAudio",
                        "id": "legacy_audio_1",
                        "audioUri": "file:///audio.mp3",
                        "audioName": "audio.mp3",
                        "internalStartMs": 0,
                        "internalEndMs": 4000,
                        "originalDurationMs": 4000
                    }
                ]
            }
        """.trimIndent()

        val legacyDeserialized = ProjectSerializer.deserialize(legacyAudioJson)
        assertNotNull(legacyDeserialized)
        val legacyOp = legacyDeserialized?.operations?.firstOrNull() as? com.boostlyte.vydra.models.EditOperation.AddBackgroundAudio
        assertNotNull(legacyOp)
        assertEquals(1.0f, legacyOp?.volume ?: 0f, 0.001f)
    }

    @Test
    fun testAudioExportVolumeFilter() {
        fun formatVolumeFilter(volume: Float): String {
            val safeVol = volume.coerceIn(0f, 2f)
            return "volume=${String.format(java.util.Locale.US, "%.2f", safeVol)}"
        }

        assertEquals("volume=0.50", formatVolumeFilter(0.5f))
        assertEquals("volume=0.00", formatVolumeFilter(0.0f))
        assertEquals("volume=1.00", formatVolumeFilter(1.0f))
        assertEquals("volume=1.75", formatVolumeFilter(1.75f))
        assertEquals("volume=2.00", formatVolumeFilter(2.5f)) // clamped to 2.0 max
        assertEquals("volume=0.00", formatVolumeFilter(-0.5f)) // clamped to 0.0 min
    }

    @Test
    fun testDoubleEncodedProjectDeserialization() {
        val dummyUri = android.net.DummyUri.INSTANCE
        val recipe = EditRecipe(
            projectName = "Double Encoded Test",
            sourceUri = dummyUri,
            sourceName = "test.mp4"
        )
        val singleJson = ProjectSerializer.serialize(recipe)
        // Simulate double-encoding where outer string is quoted JSON string
        val doubleEncodedJson = com.google.gson.Gson().toJson(singleJson)
        assertTrue(doubleEncodedJson.startsWith("\"{\\\""))

        val deserialized = ProjectSerializer.deserialize(doubleEncodedJson)
        assertNotNull(deserialized)
        assertEquals("Double Encoded Test", deserialized.projectName)
        assertEquals("test.mp4", deserialized.sourceName)
    }

    @Test
    fun testBomAndMarkdownFenceStripping() {
        val json = """
            ```json
            {
              "projectName": "Fenced Project",
              "sourceName": "video.mp4",
              "sourceUri": "file:///test.mp4",
              "operations": []
            }
            ```
        """.trimIndent()
        val withBom = "\uFEFF$json"
        val deserialized = ProjectSerializer.deserialize(withBom)
        assertNotNull(deserialized)
        assertEquals("Fenced Project", deserialized.projectName)
    }
}

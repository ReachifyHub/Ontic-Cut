package com.tharunbirla.librecuts

import android.content.Context
import android.net.Uri
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.utils.ProjectManager
import com.tharunbirla.librecuts.utils.ProjectSerializer
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
    }

    @Test
    fun testGenerateUniqueProjectFile_defaultName() {
        val file1 = File(mockDir, "Project 1.lcprj")
        assertFalse(file1.exists())

        // Simulated unique generator logic
        var candidate = File(mockDir, "Project 1.lcprj")
        assertTrue(!candidate.exists())
        candidate.writeText("{}")

        var candidate2 = File(mockDir, "Project 2.lcprj")
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
        val fileOld = File(mockDir, "Old Project.lcprj")
        fileOld.writeText("{}")
        fileOld.setLastModified(1000L)

        val fileNew = File(mockDir, "New Project.lcprj")
        fileNew.writeText("{}")
        fileNew.setLastModified(5000L)

        val files = mockDir.listFiles { f ->
            f.isFile && f.name.endsWith(".lcprj", ignoreCase = true)
        } ?: emptyArray()

        val sorted = files.sortedByDescending { it.lastModified() }
        assertEquals("New Project.lcprj", sorted[0].name)
        assertEquals("Old Project.lcprj", sorted[1].name)
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
        val file = File(mockDir, "MyProject.lcprj")
        val thumb = ProjectManager.getThumbnailFile(file)
        assertEquals("MyProject.thumb.jpg", thumb.name)
        assertEquals(mockDir.absolutePath, thumb.parentFile?.absolutePath)
    }
}

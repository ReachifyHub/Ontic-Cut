package com.boostlyte.vydra

import android.net.Uri
import com.boostlyte.vydra.utils.FolderMediaScanner
import com.boostlyte.vydra.utils.NaturalOrderComparator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NaturalOrderComparatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testNaturalOrderComparator_standardExample() {
        // As specified in the objectives:
        // "image_1.jpg", "image_2.jpg", "image_10.jpg", "image_11.jpg"
        // should sort numerically (2 before 10 and 11), NOT ASCII alphabetical
        val input = listOf("image_11.jpg", "image_2.jpg", "image_1.jpg", "image_10.jpg")
        val sorted = input.sortedWith(NaturalOrderComparator)

        assertEquals(
            listOf("image_1.jpg", "image_2.jpg", "image_10.jpg", "image_11.jpg"),
            sorted
        )
    }

    @Test
    fun testNaturalOrderComparator_interleavedPrefixes() {
        val input = listOf("video_10.mp4", "image_2.jpg", "image_11.jpg", "image_1.jpg")
        val sorted = input.sortedWith(NaturalOrderComparator)

        assertEquals(
            listOf("image_1.jpg", "image_2.jpg", "image_11.jpg", "video_10.mp4"),
            sorted
        )
    }

    @Test
    fun testNaturalOrderComparator_largeNumbersAndLeadingZeros() {
        val input = listOf(
            "frame_1000.png",
            "frame_20.png",
            "frame_05.png",
            "frame_5.png",
            "frame_1.png",
            "frame_200.png"
        )
        val sorted = input.sortedWith(NaturalOrderComparator)

        assertEquals("frame_1.png", sorted[0])
        assertTrue(sorted.indexOf("frame_5.png") < sorted.indexOf("frame_20.png"))
        assertTrue(sorted.indexOf("frame_20.png") < sorted.indexOf("frame_200.png"))
        assertTrue(sorted.indexOf("frame_200.png") < sorted.indexOf("frame_1000.png"))
    }

    @Test
    fun testNaturalOrderComparator_caseInsensitive() {
        val input = listOf("img_10.PNG", "IMG_2.jpg", "img_1.JPG")
        val sorted = input.sortedWith(NaturalOrderComparator)

        assertEquals(listOf("img_1.JPG", "IMG_2.jpg", "img_10.PNG"), sorted)
    }

    @Test
    fun testFolderMediaScanner_isSupportedFile() {
        assertTrue(FolderMediaScanner.isSupportedFile("photo.jpg"))
        assertTrue(FolderMediaScanner.isSupportedFile("photo.JPEG"))
        assertTrue(FolderMediaScanner.isSupportedFile("photo.png"))
        assertTrue(FolderMediaScanner.isSupportedFile("photo.webp"))
        assertTrue(FolderMediaScanner.isSupportedFile("clip.mp4"))
        assertTrue(FolderMediaScanner.isSupportedFile("clip.mkv"))

        assertFalse(FolderMediaScanner.isSupportedFile("document.pdf"))
        assertFalse(FolderMediaScanner.isSupportedFile("notes.txt"))
        assertFalse(FolderMediaScanner.isSupportedFile("archive.zip"))
        assertFalse(FolderMediaScanner.isSupportedFile("style.css"))
    }

    @Test
    fun testFolderMediaScanner_scanAndSortMedia_fileDirectory() {
        val folder = tempFolder.newFolder("ImportBatch")

        // Create supported files in random order
        File(folder, "image_11.jpg").writeText("data11")
        File(folder, "image_2.jpg").writeText("data2")
        File(folder, "image_1.png").writeText("data1")
        File(folder, "video_10.mp4").writeText("video10")
        File(folder, "image_3.webp").writeText("data3")

        // Create non-supported files (should be ignored)
        File(folder, "notes.txt").writeText("ignore me")
        File(folder, "info.pdf").writeText("ignore me too")
        File(folder, ".hidden_image.jpg").writeText("hidden")

        // Create a subfolder (should be ignored)
        val subDir = File(folder, "subfolder")
        subDir.mkdir()
        File(subDir, "inner_image.jpg").writeText("inner")

        // Scan folder using scanAndSortDirectory with DummyUri for JVM test
        val items = FolderMediaScanner.scanAndSortDirectory(folder) {
            android.net.DummyUri.INSTANCE
        }

        assertEquals(5, items.size)
        val fileNames = items.map { it.displayName }
        assertEquals(
            listOf("image_1.png", "image_2.jpg", "image_3.webp", "image_11.jpg", "video_10.mp4"),
            fileNames
        )
    }

    @Test
    fun testNaturalOrderComparator_userPatterns() {
        // Pattern 1: scene_001, scene_002, scene_010, scene_100
        val p1 = listOf("scene_010.jpg", "scene_002.jpg", "scene_100.jpg", "scene_001.jpg")
        assertEquals(
            listOf("scene_001.jpg", "scene_002.jpg", "scene_010.jpg", "scene_100.jpg"),
            p1.sortedWith(NaturalOrderComparator)
        )

        // Pattern 2: scene_01, scene_02, scene_10
        val p2 = listOf("scene_10.png", "scene_01.png", "scene_02.png")
        assertEquals(
            listOf("scene_01.png", "scene_02.png", "scene_10.png"),
            p2.sortedWith(NaturalOrderComparator)
        )

        // Pattern 3: 001, 002, 010, 100 (numeric-only with 3 digits)
        val p3 = listOf("010.jpg", "002.jpg", "100.jpg", "001.jpg")
        assertEquals(
            listOf("001.jpg", "002.jpg", "010.jpg", "100.jpg"),
            p3.sortedWith(NaturalOrderComparator)
        )

        // Pattern 4: 01, 02, 10 (numeric-only with 2 digits)
        val p4 = listOf("10.jpg", "01.jpg", "02.jpg")
        assertEquals(
            listOf("01.jpg", "02.jpg", "10.jpg"),
            p4.sortedWith(NaturalOrderComparator)
        )

        // Pattern 5: unpadded single digits mixed with double digits: 1, 2, 9, 10, 20
        val p5 = listOf("10.png", "2.png", "1.png", "20.png", "9.png")
        assertEquals(
            listOf("1.png", "2.png", "9.png", "10.png", "20.png"),
            p5.sortedWith(NaturalOrderComparator)
        )
    }
}


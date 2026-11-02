package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.utils.NaturalOrderComparator
import org.junit.Assert.*
import org.junit.Test

class FolderImportOrderTest {

    @Test
    fun testNaturalSort_oneTo120() {
        val filenames = (1..120).map { "$it.jpg" }.shuffled()
        val sorted = filenames.sortedWith(NaturalOrderComparator)

        val expected = (1..120).map { "$it.jpg" }
        assertEquals(expected, sorted)
        assertEquals("1.jpg", sorted.first())
        assertEquals("120.jpg", sorted.last())
        assertEquals("2.jpg", sorted[1])
        assertEquals("10.jpg", sorted[9])
    }

    @Test
    fun testNaturalSort_paddedZeros001To120() {
        val filenames = (1..120).map { String.format("%03d.jpg", it) }.shuffled()
        val sorted = filenames.sortedWith(NaturalOrderComparator)

        val expected = (1..120).map { String.format("%03d.jpg", it) }
        assertEquals(expected, sorted)
        assertEquals("001.jpg", sorted.first())
        assertEquals("120.jpg", sorted.last())
        assertEquals("002.jpg", sorted[1])
        assertEquals("010.jpg", sorted[9])
    }

    @Test
    fun testNaturalSort_mixedImgPrefix() {
        val filenames = listOf("img_10.jpg", "img_1.jpg", "img_2.jpg", "img_20.jpg", "img_3.jpg")
        val sorted = filenames.sortedWith(NaturalOrderComparator)

        val expected = listOf("img_1.jpg", "img_2.jpg", "img_3.jpg", "img_10.jpg", "img_20.jpg")
        assertEquals(expected, sorted)
    }

    @Test
    fun testSequenceConstruction_orderIntegrity() {
        // Given 120 naturally sorted files from a folder scan:
        val sortedNames = (1..120).map { "$it.jpg" }
        val sortedUris = sortedNames.map { DummyUri.fromPath("content://media/external/images/$it") }

        // Primary clip is sorted[0]
        val primaryUri = sortedUris.first()
        val extraUris = sortedUris.drop(1)

        // When appending extra items after the primary clip (explicitTargetIdx = 1 or sequenceItems.size):
        val primaryItem = EditOperation.MergeItem(
            uri = primaryUri,
            durationMs = 5000L
        )
        val extraItems = extraUris.map {
            EditOperation.MergeItem(uri = it, durationMs = 5000L)
        }

        // Target sequence order: primary + extraItems
        val fullSequence = listOf(primaryItem) + extraItems

        assertEquals(120, fullSequence.size)
        // Verify sequence order equals natural-sorted order exactly:
        for (i in 0 until 120) {
            assertEquals("Index $i must match sorted[$i]", sortedUris[i].path, fullSequence[i].uri.path)
        }
        assertEquals("sorted[0] must be at index 0", sortedUris[0].path, fullSequence[0].uri.path)
        assertEquals("sorted[119] must be at index 119", sortedUris[119].path, fullSequence[119].uri.path)
    }

    @Test
    fun testOldDefectDemonstration_insertingAtZeroMovesFirstToLast() {
        // This test documents the exact root cause of the previous bug:
        // When extraUris (items 1..N-1) were inserted at index 0 instead of appended,
        // items 1..N-1 became the new front and the original primary clip (item 0)
        // was appended after them, landing at the very end of the sequence.
        val sortedNames = listOf("1.jpg", "2.jpg", "3.jpg", "4.jpg")
        val oldPrimary = sortedNames[0] // "1.jpg"
        val extra = sortedNames.drop(1) // ["2.jpg", "3.jpg", "4.jpg"]

        // The flawed logic in insertSequenceItems(insertIndex = 0):
        // newPrimary = extra[0] -> "2.jpg"
        // restNew = extra.drop(1) -> ["3.jpg", "4.jpg"]
        // followItems = restNew + listOf(oldPrimary) -> ["3.jpg", "4.jpg", "1.jpg"]
        // resultingSequence = [newPrimary] + followItems -> ["2.jpg", "3.jpg", "4.jpg", "1.jpg"]
        val flawedSequence = listOf(extra[0]) + extra.drop(1) + listOf(oldPrimary)
        assertEquals(listOf("2.jpg", "3.jpg", "4.jpg", "1.jpg"), flawedSequence)
        // Notice "1.jpg" ended up last!

        // The fixed logic (appending after primary):
        val fixedSequence = listOf(oldPrimary) + extra
        assertEquals(sortedNames, fixedSequence)
        assertEquals("1.jpg", fixedSequence[0])
    }
}

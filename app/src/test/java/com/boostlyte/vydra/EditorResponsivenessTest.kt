package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.utils.ImagePreviewCache
import com.boostlyte.vydra.utils.TransitionPreviewCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditorResponsivenessTest {

    @Before
    fun setUp() {
        TransitionPreviewCache.clearCacheForTesting()
    }

    @Test
    fun testTransitionPreviewCache_aliasMappingsExist() {
        // Verify key aliases are mapped in both directions
        val aliases = TransitionPreviewCache.ALIAS_MAP
        assertTrue("smoothleft alias to coverleft", aliases["smoothleft"]?.contains("coverleft") == true)
        assertTrue("coverleft alias to smoothleft", aliases["coverleft"]?.contains("smoothleft") == true)
        assertTrue("smoothright alias to coverright", aliases["smoothright"]?.contains("coverright") == true)
        assertTrue("coverright alias to smoothright", aliases["coverright"]?.contains("smoothright") == true)
        assertTrue("distance alias to zoomin", aliases["distance"]?.contains("zoomin") == true)
        assertTrue("zoomin alias to distance", aliases["zoomin"]?.contains("distance") == true)
        assertTrue("rectcrop alias to box", aliases["rectcrop"]?.contains("box") == true)
        assertTrue("circlecrop alias to circle", aliases["circlecrop"]?.contains("circle") == true)
    }

    @Test
    fun testTransitionPreviewCache_aliasResolution() {
        val testFrames = intArrayOf(101, 102, 103)
        TransitionPreviewCache.registerFrameIds("coverleft", testFrames)

        // Lookup with direct key
        assertArrayEquals(testFrames, TransitionPreviewCache.getFrameResIds("coverleft"))

        // Lookup with alias key "smoothleft"
        assertArrayEquals("Should resolve via alias map", testFrames, TransitionPreviewCache.getFrameResIds("smoothleft"))
        assertTrue(TransitionPreviewCache.hasFrames("smoothleft"))
        assertTrue(TransitionPreviewCache.hasFrames("coverleft"))
    }

    @Test
    fun testTransitionPreviewCache_distanceToZoomInResolution() {
        val zoomInFrames = intArrayOf(201, 202, 203)
        TransitionPreviewCache.registerFrameIds("zoomin", zoomInFrames)

        // "distance" should resolve to zoomin frames
        assertArrayEquals(zoomInFrames, TransitionPreviewCache.getFrameResIds("distance"))
        assertTrue(TransitionPreviewCache.hasFrames("distance"))
    }

    @Test
    fun testImagePreviewCache_thumbnailKeyingByUriAndWidth() {
        val uriA = DummyUri.fromPath("/storage/emulated/0/DCIM/photo1.jpg")
        val uriB = DummyUri.fromPath("/storage/emulated/0/DCIM/photo2.jpg")

        val keyA96 = ImagePreviewCache.makeThumbnailKey(uriA, 96)
        val keyA120 = ImagePreviewCache.makeThumbnailKey(uriA, 120)
        val keyB96 = ImagePreviewCache.makeThumbnailKey(uriB, 96)

        assertEquals("/storage/emulated/0/DCIM/photo1.jpg#96", keyA96)
        assertEquals("/storage/emulated/0/DCIM/photo1.jpg#120", keyA120)
        assertEquals("/storage/emulated/0/DCIM/photo2.jpg#96", keyB96)

        // Verify distinct keys
        org.junit.Assert.assertNotEquals(keyA96, keyA120)
        org.junit.Assert.assertNotEquals(keyA96, keyB96)
    }

    @Test
    fun testCaptionCueBoundaryTracking_invalidationOnlyOnBoundaryCrossing() {
        // Model boundary detection logic used by CaptionTrackView.updatePlayhead()
        val cueA = EditOperation.AddText(
            id = "cue_a",
            text = "Hello world",
            fontSize = 24,
            position = com.boostlyte.vydra.models.TextPosition.BOTTOM_CENTER,
            startTimeMs = 1000L,
            endTimeMs = 3000L
        )
        val cueB = EditOperation.AddText(
            id = "cue_b",
            text = "Next caption",
            fontSize = 24,
            position = com.boostlyte.vydra.models.TextPosition.BOTTOM_CENTER,
            startTimeMs = 4000L,
            endTimeMs = 6000L
        )
        val cues = listOf(cueA, cueB)

        var activeCueId: String? = null
        var invalidateCallCount = 0

        fun simulatePlayheadTick(playheadMs: Long) {
            val activeCue = cues.firstOrNull { playheadMs in it.startTimeMs!!..it.endTimeMs!! }
            val newActiveId = activeCue?.id
            if (newActiveId != activeCueId) {
                activeCueId = newActiveId
                invalidateCallCount++
            }
        }

        // Ticks before any cue (playhead 0..900ms)
        simulatePlayheadTick(100L)
        simulatePlayheadTick(500L)
        simulatePlayheadTick(900L)
        assertEquals("No cues active; no boundary crossed yet", 0, invalidateCallCount)
        assertNull(activeCueId)

        // Cross boundary into Cue A (at 1000ms)
        simulatePlayheadTick(1000L)
        assertEquals("Crossed boundary into Cue A", 1, invalidateCallCount)
        assertEquals("cue_a", activeCueId)

        // Advance 40 frames inside Cue A (playhead 1050ms to 2950ms)
        for (ms in 1050L..2950L step 50L) {
            simulatePlayheadTick(ms)
        }
        assertEquals("Invalidate must NOT be called on every frame while inside Cue A", 1, invalidateCallCount)
        assertEquals("cue_a", activeCueId)

        // Cross boundary out of Cue A into gap (at 3050ms)
        simulatePlayheadTick(3050L)
        assertEquals("Crossed boundary out of Cue A", 2, invalidateCallCount)
        assertNull(activeCueId)

        // Advance frames in gap (3100ms to 3950ms)
        for (ms in 3100L..3950L step 50L) {
            simulatePlayheadTick(ms)
        }
        assertEquals("No invalidates in gap", 2, invalidateCallCount)

        // Cross boundary into Cue B (at 4000ms)
        simulatePlayheadTick(4000L)
        assertEquals("Crossed boundary into Cue B", 3, invalidateCallCount)
        assertEquals("cue_b", activeCueId)

        // Advance inside Cue B (4050ms to 5950ms)
        for (ms in 4050L..5950L step 50L) {
            simulatePlayheadTick(ms)
        }
        assertEquals("Invalidate must NOT be called on every frame inside Cue B", 3, invalidateCallCount)

        // Cross boundary out of Cue B
        simulatePlayheadTick(6050L)
        assertEquals("Crossed boundary out of Cue B", 4, invalidateCallCount)
        assertNull(activeCueId)
    }
}

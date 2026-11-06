package com.boostlyte.vydra.render.gl

import android.net.DummyUri
import com.boostlyte.vydra.models.CanvasAspectRatio
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.services.ChunkPlanner
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit tests verifying GlMotionRenderer integration and the silent fallback chain.
 * Covers Milestone M1, M2, and M3 requirements.
 */
class GlMotionIntegrationTest {

    @Test
    fun testPreRenderGlMotionIntermediates_whenNoMotion_preservesItemsAndReturnsLegacy() {
        val viewModel = VideoEditingViewModel()
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")

        val items = listOf(
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/vid1.mp4"),
                durationMs = 5000L,
                isImage = false
            ),
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/img1.jpg"),
                durationMs = 3000L,
                isImage = true,
                motionEffect = null
            )
        )

        val (updatedItems, renderPath) = viewModel.preRenderGlMotionIntermediates(
            items = items,
            operations = emptyList(),
            cacheDir = tempDir,
            targetWidth = 1080,
            targetHeight = 1920,
            fps = 30
        )

        assertEquals(2, updatedItems.size)
        assertEquals("legacy", renderPath)
        assertEquals(items[0].uri, updatedItems[0].uri)
        assertEquals(items[1].uri, updatedItems[1].uri)
    }

    @Test
    fun testPreRenderGlMotionIntermediates_whenGlFailsOnMissingSource_gracefullyFallsBackToLegacy() {
        val viewModel = VideoEditingViewModel()
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")

        val items = listOf(
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/nonexistent/image.jpg"),
                durationMs = 4000L,
                isImage = true,
                motionEffect = "zoom_in"
            )
        )

        val (updatedItems, renderPath) = viewModel.preRenderGlMotionIntermediates(
            items = items,
            operations = emptyList(),
            cacheDir = tempDir,
            targetWidth = 1920,
            targetHeight = 1080,
            fps = 30
        )

        // Must not crash, falls back to legacy zoompan
        assertEquals(1, updatedItems.size)
        assertEquals("legacy", renderPath)
        assertTrue(updatedItems[0].isImage)
        assertEquals("zoom_in", updatedItems[0].motionEffect)
    }

    @Test
    fun testBuildChunkExportPlan_includesRenderPathForSinglePass() {
        val viewModel = VideoEditingViewModel()
        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source.mp4"),
            sourceName = "source.mp4"
        )
        viewModel.loadProject(project)

        val plan = viewModel.buildChunkExportPlan(
            sourceFilePath = "/tmp/source.mp4",
            outputFilePath = "/tmp/output.mp4"
        )

        assertNotNull(plan)
        assertFalse(plan!!.isChunked)
        assertNotNull(plan.renderPath)
        assertTrue(listOf("gl_hw", "gl_sw", "legacy").contains(plan.renderPath))
    }

    @Test
    fun testBuildChunkExportPlan_includesRenderPathForChunkedPasses() {
        val viewModel = VideoEditingViewModel()
        // Create 26 items to trigger chunking (> 24)
        val mergeItems = (1..25).map { i ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/clip_$i.mp4"),
                durationMs = 2000L,
                isImage = false
            )
        }
        val mergeOp = EditOperation.Merge(
            items = mergeItems
        )
        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source.mp4"),
            sourceName = "source.mp4",
            operations = listOf(mergeOp),
            canvasAspectRatio = CanvasAspectRatio.PORTRAIT_9_16
        )
        viewModel.loadProject(project)

        val plan = viewModel.buildChunkExportPlan(
            sourceFilePath = "/tmp/source.mp4",
            outputFilePath = "/tmp/output.mp4"
        )

        assertNotNull(plan)
        assertTrue(plan!!.isChunked)
        assertEquals("legacy", plan.renderPath)
        assertTrue(plan.chunkCommands.isNotEmpty())
        for (chunkCmd in plan.chunkCommands) {
            assertEquals("legacy", chunkCmd.renderPath)
        }
    }
}

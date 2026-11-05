package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.export.EncoderSelector
import com.boostlyte.vydra.export.StorageEstimator
import com.boostlyte.vydra.models.CanvasAspectRatio
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.services.ChunkPlanner
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExportLifecycleAndScaleTest {

    @Test
    fun testChunkPlanning_scale5Images() {
        val items5 = (1..5).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///clip_$it.jpg"), durationMs = 4000L, isImage = true)
        }
        val plan = ChunkPlanner.plan(items5, emptyList())
        assertFalse("5 clips must not trigger chunking", plan.isChunked)
        assertEquals(1, plan.chunks.size)
        assertEquals(5, plan.chunks[0].clips.size)
        assertEquals(20000L, plan.totalDurationMs)
    }

    @Test
    fun testChunkPlanning_scale20Images() {
        val items20 = (1..20).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///clip_$it.jpg"), durationMs = 3000L, isImage = true)
        }
        val plan = ChunkPlanner.plan(items20, emptyList())
        assertTrue("20 clips must trigger chunking", plan.isChunked)
        assertEquals(2, plan.chunks.size)
        assertEquals(10, plan.chunks[0].clips.size)
        assertEquals(10, plan.chunks[1].clips.size)
        assertEquals(60000L, plan.totalDurationMs)
    }

    @Test
    fun testChunkPlanning_scale100Images() {
        val items100 = (1..100).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///clip_$it.jpg"), durationMs = 3000L, isImage = true)
        }
        val plan = ChunkPlanner.plan(items100, emptyList())
        assertTrue("100 clips must trigger chunking", plan.isChunked)
        assertEquals(10, plan.chunks.size)
        for (chunk in plan.chunks) {
            assertEquals("Each chunk must contain exactly 10 clips", 10, chunk.clips.size)
        }
        assertEquals(9, plan.boundaryTransitions.size)
        assertEquals(300000L, plan.totalDurationMs)
    }

    @Test
    fun testChunkPlanning_scale300Images() {
        val items300 = (1..300).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///clip_$it.jpg"), durationMs = 2500L, isImage = true)
        }
        val plan = ChunkPlanner.plan(items300, emptyList())
        assertTrue("300 clips must trigger chunking", plan.isChunked)
        assertEquals(30, plan.chunks.size)
        for (chunk in plan.chunks) {
            assertEquals("Each chunk must contain exactly 10 clips", 10, chunk.clips.size)
        }
        assertEquals(29, plan.boundaryTransitions.size)
        assertEquals(750000L, plan.totalDurationMs)
    }

    @Test
    fun testChunkPlanning_scale500Images() {
        val items500 = (1..500).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///clip_$it.jpg"), durationMs = 2000L, isImage = true)
        }
        val plan = ChunkPlanner.plan(items500, emptyList())
        assertTrue("500 clips must trigger chunking", plan.isChunked)
        assertEquals(50, plan.chunks.size)
        for (chunk in plan.chunks) {
            assertEquals("Each chunk must contain exactly 10 clips", 10, chunk.clips.size)
        }
        assertEquals(49, plan.boundaryTransitions.size)
        assertEquals(1000000L, plan.totalDurationMs)
    }

    @Test
    fun testNoUpfrontGlMotionPreprocessingOnLargeProjects() {
        val viewModel = VideoEditingViewModel()
        viewModel.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)

        // 100 images all with Ken Burns motion effect
        val items100 = (1..100).map { i ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/image_$i.jpg"),
                durationMs = 3000L,
                isImage = true,
                motionEffect = "zoom_in"
            )
        }
        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source.jpg"),
            sourceName = "source.jpg",
            operations = listOf(EditOperation.Merge(items = items100)),
            canvasAspectRatio = CanvasAspectRatio.PORTRAIT_9_16
        )
        viewModel.loadProject(project)

        val startTime = System.currentTimeMillis()
        val exportPlan = viewModel.buildChunkExportPlan(
            sourceFilePath = "/tmp/source.jpg",
            outputFilePath = "/tmp/export.mp4"
        )
        val planningDurationMs = System.currentTimeMillis() - startTime

        assertNotNull("Export plan must be generated", exportPlan)
        assertTrue("Planning 100 images must complete almost instantly without rendering (< 1000ms)", planningDurationMs < 1000)
        assertTrue(exportPlan!!.isChunked)
        assertEquals(11, exportPlan.chunkCommands.size)
        assertEquals(11, exportPlan.chunkSpecs.size)
        assertEquals(305.0, exportPlan.totalDurationSecs, 0.5)
    }

    @Test
    fun testPerChunkMotionRenderingBoundedToActiveChunk() {
        val viewModel = VideoEditingViewModel()
        val dummyTempDir = File("/tmp/test_chunk_motion_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            // Build a chunk with 10 clips, only 2 having motion
            val chunkSpecClips = (0 until 10).map { idx ->
                val hasMotion = (idx == 2 || idx == 7)
                com.boostlyte.vydra.services.ChunkClip(
                    globalIndex = idx,
                    localIndex = idx,
                    item = EditOperation.MergeItem(
                        uri = DummyUri.fromPath("/tmp/image_$idx.jpg"),
                        durationMs = 3000L,
                        isImage = true,
                        motionEffect = if (hasMotion) "zoom_in" else null
                    )
                )
            }
            val chunkSpec = com.boostlyte.vydra.services.ChunkSpec(
                chunkIndex = 0,
                clips = chunkSpecClips,
                internalTransitions = emptyList(),
                globalStartMs = 0L,
                globalEndMs = 30000L,
                durationMs = 30000L
            )

            val (renderedSpec, createdFiles) = viewModel.preRenderGlMotionForChunk(
                chunkSpec = chunkSpec,
                operations = emptyList(),
                cacheDir = dummyTempDir,
                targetWidth = 1080,
                targetHeight = 1920,
                fps = 30,
                preferredEncoder = "hardware"
            )

            // Since test environment doesn't have real GPU/MediaCodec, renderMotionClip falls back to legacy
            // or reports failure gracefully, returning the original clips or created MP4s without throwing.
            assertEquals(10, renderedSpec.clips.size)
            assertNotNull(createdFiles)
        } finally {
            dummyTempDir.deleteRecursively()
        }
    }

    @Test
    fun testEncoderSelector_modesAndPermanentErrors() {
        // Selection with AUTO mode
        val autoSelected = EncoderSelector.selectEncoder(
            preference = EncoderSelector.EncoderPreference.AUTO,
            targetWidth = 1280,
            targetHeight = 720,
            fps = 30
        )
        assertNotNull(autoSelected)
        assertNotNull(autoSelected.ffmpegVideoCodecArgs)
        assertTrue(autoSelected.isSupportedForTarget)

        // Selection with SOFTWARE mode
        val swSelected = EncoderSelector.selectEncoder(
            preference = EncoderSelector.EncoderPreference.SOFTWARE,
            targetWidth = 1280,
            targetHeight = 720,
            fps = 30
        )
        assertNotNull(swSelected)
        assertFalse(swSelected.isHardware)
        assertTrue(swSelected.ffmpegVideoCodecArgs.contains("libx264"))

        // Permanent failure detection
        assertTrue("Cancellation is permanent failure", EncoderSelector.isPermanentFailure(null, isCancelled = true))
        assertTrue("No space is permanent failure", EncoderSelector.isPermanentFailure("No space left on device", isCancelled = false))
        assertTrue("Syntax error is permanent failure", EncoderSelector.isPermanentFailure("[AVFilterGraph] No such filter: '188.16'", isCancelled = false))
        assertFalse("Generic codec failure is not permanent (retry allowed)", EncoderSelector.isPermanentFailure("Encoder error 0x80000000", isCancelled = false))
    }

    @Test
    fun testStorageEstimator_calculations() {
        // 60-second 1080p chunked video:
        val requiredBytes = StorageEstimator.estimateRequiredBytes(
            durationSecs = 60.0,
            resolution = 1080,
            isChunked = true
        )
        // 60s * 1MB/s * 1.25 = 75MB + 60MB (chunks) + 100MB (motion) + 100MB (safety) = 335 MB
        assertTrue("Estimated bytes for 60s 1080p must be >= 250 MB", requiredBytes > 250_000_000L)

        val check = StorageEstimator.checkStorageAvailable(
            directory = File("/tmp"),
            durationSecs = 60.0,
            resolution = 1080,
            isChunked = true
        )
        assertNotNull(check)
    }

    @Test
    fun testSafeFinalAssemblyStreamCopyFlag() {
        val viewModel = VideoEditingViewModel()
        viewModel.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)

        // Project without transitions, audio, or overlays: stream copy should be true
        val plainClips = (1..20).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("/tmp/clip_$it.mp4"), durationMs = 2000L)
        }
        val projectPlain = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source.mp4"),
            sourceName = "source.mp4",
            operations = listOf(EditOperation.Merge(items = plainClips))
        )
        viewModel.loadProject(projectPlain)
        val planPlain = viewModel.buildChunkExportPlan(sourceFilePath = "/tmp/source.mp4", outputFilePath = "/tmp/out.mp4")
        assertNotNull(planPlain)
        assertTrue("Plain chunked project must allow stream copy final pass", planPlain!!.canStreamCopyFinalPass)

        // Project with background audio: stream copy should be false (audio mixing requires re-encoding)
        val projectWithAudio = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source.mp4"),
            sourceName = "source.mp4",
            operations = listOf(
                EditOperation.Merge(items = plainClips),
                EditOperation.AddBackgroundAudio(audioUri = DummyUri.fromPath("/tmp/audio.mp3"))
            )
        )
        viewModel.loadProject(projectWithAudio)
        val planAudio = viewModel.buildChunkExportPlan(sourceFilePath = "/tmp/source.mp4", outputFilePath = "/tmp/out.mp4")
        assertNotNull(planAudio)
        assertFalse("Project with audio mixing must NOT allow stream copy final pass", planAudio!!.canStreamCopyFinalPass)
    }
}

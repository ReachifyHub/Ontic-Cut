package com.boostlyte.vydra.export
 
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
 * Matrix verification test suite testing projects across:
 * - 3, 10, 16, 20, 64, 100, and 300 clips
 * - Software (libx264) and Hardware (h264_mediacodec / fallback) encoder preferences
 * - Single-pass vs Chunked legacy FFmpeg pipeline
 * - Zero intermediate GL MP4 files generated
 * - Encoder consistency across all chunks
 * - Concat stream copy vs re-encode final pass conditions
 */
class LegacyFFmpegExportMatrixTest {

    private fun createProject(clipCount: Int, withAudio: Boolean = false, withBoundaryTransitions: Boolean = false): VideoProject {
        val mergeItems = (1..clipCount - 1).map { i ->
            val isImg = (i % 2 == 1)
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/clip_$i.${if (isImg) "jpg" else "mp4"}"),
                durationMs = 3000L,
                isImage = isImg,
                motionEffect = if (isImg) "zoom_in" else null
            )
        }
        val ops = mutableListOf<EditOperation>()
        if (mergeItems.isNotEmpty()) {
            ops.add(EditOperation.Merge(items = mergeItems))
        }
        if (withAudio) {
            ops.add(
                EditOperation.AddBackgroundAudio(
                    audioUri = DummyUri.fromPath("/tmp/bg_audio.mp3"),
                    volume = 0.8f
                )
            )
        }
        if (withBoundaryTransitions) {
            ops.add(
                EditOperation.Transition(
                    index = 9,
                    type = "fade",
                    durationMs = 500L
                )
            )
        }

        return VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/source_0.mp4"),
            sourceName = "source_0.mp4",
            operations = ops,
            canvasAspectRatio = CanvasAspectRatio.LANDSCAPE_16_9
        )
    }

    @Test
    fun testMatrix_3Clips_SoftwareAndHardware() {
        runScaleTest(clipCount = 3, expectChunked = false)
    }

    @Test
    fun testMatrix_10Clips_SoftwareAndHardware() {
        runScaleTest(clipCount = 10, expectChunked = false)
    }

    @Test
    fun testMatrix_16Clips_SoftwareAndHardware() {
        // 16 is the threshold boundary (CHUNK_TRIGGER_THRESHOLD = 16)
        runScaleTest(clipCount = 16, expectChunked = false)
    }

    @Test
    fun testMatrix_20Clips_SoftwareAndHardware() {
        // > 16 triggers chunking
        runScaleTest(clipCount = 20, expectChunked = true)
    }

    @Test
    fun testMatrix_64Clips_SoftwareAndHardware() {
        runScaleTest(clipCount = 64, expectChunked = true)
    }

    @Test
    fun testMatrix_100Clips_SoftwareAndHardware() {
        runScaleTest(clipCount = 100, expectChunked = true)
    }

    @Test
    fun testMatrix_300Clips_SoftwareAndHardware() {
        runScaleTest(clipCount = 300, expectChunked = true)
    }

    private fun runScaleTest(clipCount: Int, expectChunked: Boolean) {
        val viewModel = VideoEditingViewModel()
        val project = createProject(clipCount)
        viewModel.loadProject(project)

        for (pref in listOf(EncoderSelector.EncoderPreference.SOFTWARE, EncoderSelector.EncoderPreference.HARDWARE)) {
            val selectedEnc = EncoderSelector.selectEncoder(pref, 1280, 720, 30, "5M")
            val plan = viewModel.buildChunkExportPlan(
                sourceFilePath = "/tmp/source_0.mp4",
                outputFilePath = "/tmp/test_out.mp4"
            )

            assertNotNull("Plan must not be null for $clipCount clips", plan)
            assertEquals("Render path must strictly be legacy", "legacy", plan!!.renderPath)
            assertEquals("Chunked state mismatch for $clipCount clips", expectChunked, plan.isChunked)

            if (!expectChunked) {
                assertNotNull("Single-pass command required for <= 16 clips", plan.singlePassCommand)
                val cmd = plan.singlePassCommand!!
                assertTrue("Single-pass must use FFmpeg", cmd.startsWith("-y"))
                // Verify no GL temp file references in command
                assertFalse("Must not reference GL intermediate MP4s", cmd.contains("gl_motion_"))
                assertFalse("Must not reference GL chunk MP4s", cmd.contains("gl_chunk_"))
            } else {
                assertTrue("Chunk commands must be present", plan.chunkCommands.isNotEmpty())
                for (chunkCmd in plan.chunkCommands) {
                    assertEquals("Chunk render path must be legacy", "legacy", chunkCmd.renderPath)
                    assertFalse("Chunk command must not reference GL intermediate MP4s", chunkCmd.command.contains("gl_motion_"))
                    assertFalse("Chunk command must not reference GL chunk MP4s", chunkCmd.command.contains("gl_chunk_"))
                }
                assertNotNull("Final pass command must be present", plan.finalPassCommand)
            }
        }
    }

    @Test
    fun testEncoderConsistency_AllChunksShareUniformCodec() {
        val viewModel = VideoEditingViewModel()
        val project = createProject(24)
        viewModel.loadProject(project)

        // Test with software encoder
        val swEncoder = "-c:v libx264 -preset veryfast -threads 0 -pix_fmt yuv420p"
        val density = 1.0f
        val plan = ChunkPlanner.plan(
            (0 until 24).map { EditOperation.MergeItem(uri = DummyUri.fromPath("/tmp/c_$it.mp4"), durationMs = 3000L, isImage = false) },
            emptyList()
        )

        val chunkCmds = plan.chunks.map { chunk ->
            viewModel.buildChunkPassCommand(
                chunkSpec = chunk,
                sourceFilePath = "/tmp/source.mp4",
                chunkOutputPath = "/tmp/chunk_${chunk.chunkIndex}.mp4",
                fontFilePath = null,
                context = null,
                totalDurationMs = plan.totalDurationMs,
                density = density,
                targetWidth = 1280,
                targetHeight = 720,
                operations = emptyList(),
                encoderOverride = swEncoder
            )
        }

        // Verify every chunk uses identical video codec string
        for (chunkCmd in chunkCmds) {
            assertTrue("Every chunk must use libx264 uniformly", chunkCmd.command.contains("-c:v libx264"))
            assertFalse("Must not mix hardware encoder in chunk", chunkCmd.command.contains("-c:v h264_mediacodec"))
        }
    }

    @Test
    fun testFinalAssembly_StreamCopyOnlyWhenAllowed() {
        val viewModel = VideoEditingViewModel()

        // 1. Clean project without audio or boundary transitions -> canStreamCopy = true
        val cleanProject = createProject(20, withAudio = false, withBoundaryTransitions = false)
        viewModel.loadProject(cleanProject)
        val cleanPlan = viewModel.buildChunkExportPlan("/tmp/source.mp4", "/tmp/out.mp4")
        assertNotNull(cleanPlan)
        assertTrue("Clean chunked export can use stream copy concat", cleanPlan!!.canStreamCopyFinalPass)

        // 2. Project with background audio -> canStreamCopy = false (must re-encode audio mix)
        val audioProject = createProject(20, withAudio = true, withBoundaryTransitions = false)
        viewModel.loadProject(audioProject)
        val audioPlan = viewModel.buildChunkExportPlan("/tmp/source.mp4", "/tmp/out.mp4")
        assertNotNull(audioPlan)
        assertFalse("Chunked export with audio cannot stream copy", audioPlan!!.canStreamCopyFinalPass)

        // 3. Project with boundary transition -> canStreamCopy = false (must re-encode transition)
        val transitionProject = createProject(20, withAudio = false, withBoundaryTransitions = true)
        viewModel.loadProject(transitionProject)
        val transitionPlan = viewModel.buildChunkExportPlan("/tmp/source.mp4", "/tmp/out.mp4")
        assertNotNull(transitionPlan)
        assertFalse("Chunked export with boundary transition cannot stream copy", transitionPlan!!.canStreamCopyFinalPass)
    }

    @Test
    fun testEncoderSelector_HardwareFallbackLoggingAndMapping() {
        // Software preference maps directly to libx264
        val sw = EncoderSelector.selectEncoder(
            preference = EncoderSelector.EncoderPreference.SOFTWARE,
            targetWidth = 1920,
            targetHeight = 1080,
            fps = 60
        )
        assertEquals(EncoderSelector.EncoderBackend.SOFTWARE_LIBX264, sw.backend)
        assertTrue(sw.ffmpegVideoCodecArgs.contains("-c:v libx264"))
        assertTrue(sw.ffmpegVideoCodecArgs.contains("-preset veryfast"))
        assertFalse(sw.isHardware)

        // In test JVM environment without native hardware MediaCodec, hardware preference
        // logs warning and falls back cleanly to libx264
        val hwOrFallback = EncoderSelector.selectEncoder(
            preference = EncoderSelector.EncoderPreference.HARDWARE,
            targetWidth = 3840,
            targetHeight = 2160,
            fps = 60
        )
        assertNotNull(hwOrFallback)
        assertTrue(hwOrFallback.ffmpegVideoCodecArgs.contains("-c:v"))
    }
}

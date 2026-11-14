package com.boostlyte.vydra.export

import android.net.DummyUri
import com.boostlyte.vydra.models.CanvasAspectRatio
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.services.ChunkPlanner
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.*
import org.junit.Test

class ExportOptimizationTest {

    @Test
    fun testBoundaryTransitionContainment_20_64_100Clips_ZeroBoundaryTransitions() {
        for (clipCount in listOf(20, 64, 100)) {
            val items = (0 until clipCount).map { idx ->
                EditOperation.MergeItem(
                    uri = DummyUri.fromPath("/tmp/img_$idx.jpg"),
                    durationMs = 3000L,
                    isImage = true,
                    motionEffect = "zoom_in"
                )
            }
            val transitions = (0 until clipCount - 1).map { gap ->
                EditOperation.Transition(index = gap, type = "fade", durationMs = 500L)
            }

            val plan = ChunkPlanner.plan(
                sequenceItems = items,
                transitions = transitions,
                containBoundaryTransitions = true,
                fps = 30
            )

            assertTrue("Must be chunked for $clipCount clips", plan.isChunked)
            assertTrue(
                "Contained chunk plan for $clipCount clips must have zero boundary transitions",
                plan.boundaryTransitions.all { it.transition == null }
            )
            val chunkDurSum = plan.chunks.sumOf { it.durationMs }
            assertEquals(
                "Total duration must be preserved exactly after boundary split ($clipCount clips)",
                plan.totalDurationMs,
                chunkDurSum
            )
            assertTrue(
                "Every chunk must remain bounded (<= 11 clips)",
                plan.chunks.all { it.clips.size <= 11 }
            )
        }
    }

    @Test
    fun testKenBurnsContinuationOnSplitTailSlice() {
        val items = (0 until 20).map { idx ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/img_$idx.jpg"),
                durationMs = 3000L,
                isImage = true,
                motionEffect = "zoom_in"
            )
        }
        val transitions = (0 until 19).map { gap ->
            EditOperation.Transition(index = gap, type = "fade", durationMs = 500L)
        }

        val plan = ChunkPlanner.plan(
            sequenceItems = items,
            transitions = transitions,
            containBoundaryTransitions = true,
            fps = 30
        )

        // With dense transitions, chunk 0 splits a clip so chunk 1's first clip has sliceStartInClipMs > 0
        assertTrue("Must produce multiple chunks", plan.chunks.size >= 2)
        val tailClip = plan.chunks[1].clips.first()
        assertTrue("Second chunk first clip must be a tail slice (sliceStartInClipMs > 0)", tailClip.sliceStartInClipMs > 0L)
        assertTrue("Full duration must be preserved on split clip", tailClip.fullClipDurationMs == 3000L)

        val vm = VideoEditingViewModel()
        vm.setExportSettings(resolution = 720, fps = 30, audioOnly = false)
        val tailChunkCmd = vm.buildChunkPassCommand(
            chunkSpec = plan.chunks[1],
            sourceFilePath = "/tmp/img_0.jpg",
            chunkOutputPath = "/tmp/chunk_1.mp4",
            fontFilePath = null,
            context = null,
            totalDurationMs = plan.totalDurationMs,
            density = 1.0f,
            targetWidth = 1280,
            targetHeight = 720,
            operations = emptyList(),
            encoderOverride = "-c:v libx264 -preset veryfast -crf 23 -threads 0 -pix_fmt yuv420p"
        )

        val expectedStartFrame = ((tailClip.sliceStartInClipMs / 1000.0) * 30).toInt()
        assertTrue(
            "Split tail slice must offset zoompan frame counter by +$expectedStartFrame",
            tailChunkCmd.command.contains("(on+$expectedStartFrame)")
        )
    }

    @Test
    fun testAudioMixPlanEnablesStreamCopyVideoWithAudioMux() {
        val mergeItems = (1..23).map { idx ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/clip_$idx.jpg"),
                durationMs = 3000L,
                isImage = true,
                motionEffect = "zoom_in"
            )
        }
        val transitions = (0..22).map { gap ->
            EditOperation.Transition(index = gap, type = "dissolve", durationMs = 500L)
        }
        val bgAudio = EditOperation.AddBackgroundAudio(
            audioUri = DummyUri.fromPath("/tmp/voiceover.mp3"),
            volume = 1.0f,
            internalStartMs = 0L,
            internalEndMs = 20_000L,
            startTimeMs = 0L,
            endTimeMs = 20_000L,
            originalDurationMs = 20_000L
        )

        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/clip_0.jpg"),
            sourceName = "clip_0.jpg",
            operations = listOf(EditOperation.Merge(items = mergeItems), bgAudio) + transitions,
            canvasAspectRatio = CanvasAspectRatio.LANDSCAPE_16_9
        )

        val vm = VideoEditingViewModel()
        vm.setExportSettings(resolution = 360, fps = 30, audioOnly = false)
        vm.loadProject(project)

        val plan = vm.buildChunkExportPlan(
            sourceFilePath = "/tmp/clip_0.jpg",
            outputFilePath = "/tmp/final.mp4"
        )
        assertNotNull(plan)
        assertTrue(plan!!.isChunked)
        assertFalse("Existing contract: canStreamCopyFinalPass stays false when audio ops exist", plan.canStreamCopyFinalPass)
        assertTrue("Stream-copy video + audio mux must be enabled", plan.canStreamCopyVideoWithAudioMux)
        assertNotNull("Pre-mix audio FFmpeg command must be built", plan.audioMixCommand)
        assertNotNull("Mixed audio output path must be planned", plan.mixedAudioTempPath)
        assertEquals("360p audio bitrate should be 96k", "96k", plan.audioBitrate)
    }

    @Test
    fun testImagePipelineAvoidsLoop1ForStaticAndKenBurns() {
        val mergeItems = (1..2).map { idx ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/clip_$idx.jpg"),
                durationMs = 4000L,
                isImage = true,
                motionEffect = if (idx == 1) "zoom_in" else "none"
            )
        }
        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/clip_0.jpg"),
            sourceName = "clip_0.jpg",
            operations = listOf(
                EditOperation.Trim(startMs = 0L, endMs = 4000L),
                EditOperation.Merge(items = mergeItems)
            ),
            canvasAspectRatio = CanvasAspectRatio.LANDSCAPE_16_9
        )
        val vm = VideoEditingViewModel()
        vm.setExportSettings(resolution = 360, fps = 30, audioOnly = false)
        vm.loadProject(project)

        val singlePassCmd = vm.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/tmp/clip_0.jpg",
            outputFilePath = "/tmp/out.mp4"
        )
        assertNotNull(singlePassCmd)
        assertFalse("Single-pass static/KenBurns images must not use -loop 1", singlePassCmd!!.contains("-loop 1"))
        assertTrue("Static images must use single-frame loop filter", singlePassCmd.contains("loop=loop=-1:size=1:start=0"))

        val singleImageOnly = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/only.jpg"),
            sourceName = "only.jpg",
            operations = listOf(EditOperation.Trim(startMs = 0L, endMs = 5000L)),
            canvasAspectRatio = CanvasAspectRatio.PORTRAIT_9_16
        )
        vm.loadProject(singleImageOnly)
        val singleImageCmd = vm.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/tmp/only.jpg",
            outputFilePath = "/tmp/only_out.mp4"
        )
        assertNotNull(singleImageCmd)
        assertFalse("Standalone single image export must not use -loop 1", singleImageCmd!!.contains("-loop 1"))
    }

    @Test
    fun testEncoderSelectorResolutionBitratesAndSoftwareCrf() {
        assertEquals("650k", EncoderSelector.getTargetVideoBitrate(360, 30))
        assertEquals("1500k", EncoderSelector.getTargetVideoBitrate(480, 30))
        assertEquals("4M", EncoderSelector.getTargetVideoBitrate(720, 30))
        assertEquals("8M", EncoderSelector.getTargetVideoBitrate(1080, 30))

        assertEquals(24, EncoderSelector.getTargetSoftwareCrf(360))
        assertEquals(24, EncoderSelector.getTargetSoftwareCrf(480))
        assertEquals(23, EncoderSelector.getTargetSoftwareCrf(720))
        assertEquals(23, EncoderSelector.getTargetSoftwareCrf(1080))

        assertEquals("96k", EncoderSelector.getTargetAudioBitrate(360))
        assertEquals("128k", EncoderSelector.getTargetAudioBitrate(720))

        val sw360 = EncoderSelector.selectEncoder(
            preference = EncoderSelector.EncoderPreference.SOFTWARE,
            targetWidth = 640,
            targetHeight = 360,
            fps = 30
        )
        assertTrue("Software libx264 must use CRF rate control", sw360.ffmpegVideoCodecArgs.contains("-crf 24"))
        assertFalse("Software libx264 must not force MediaCodec -b:v", sw360.ffmpegVideoCodecArgs.contains("-b:v"))

        val rewrittenFallback = EncoderSelector.buildSoftwareFallbackCommand(
            "-y -i in.mp4 -c:v h264_mediacodec -pix_fmt yuv420p -b:v 650k out.mp4",
            crf = 24
        )
        assertTrue(rewrittenFallback.contains("-c:v libx264 -preset veryfast -crf 24 -threads 0"))
        assertFalse("Software fallback command must strip -b:v", rewrittenFallback.contains("-b:v"))
    }
}

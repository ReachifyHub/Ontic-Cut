package com.boostlyte.vydra.export

import android.net.DummyUri
import com.boostlyte.vydra.models.CanvasAspectRatio
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.SubtitleCue
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.services.ChunkPlanner
import com.boostlyte.vydra.utils.AppLogger
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class LowMemoryAndSyncVerificationTest {

    private val MB = 1024L * 1024L
    private val GB = 1024L * MB

    private fun buildImageProject(
        imageCount: Int,
        durationsMs: List<Long>? = null,
        transitionsCount: Int = 0,
        transitionDurationMs: Long = 500L,
        captionCuesCount: Int = 0,
        audioDurationMs: Long? = null,
        audioPath: String = "/tmp/voiceover.m4a",
        motionPattern: (Int) -> String = { idx -> if (idx % 2 == 0) "zoom_in" else "zoom_out" }
    ): Pair<VideoProject, List<EditOperation>> {
        val defaultDurs = durationsMs ?: List(imageCount) { 2300L }
        val trimOp = EditOperation.Trim(startMs = 0L, endMs = defaultDurs[0])
        val mergeItems = (1 until imageCount).map { idx ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/img_$idx.jpg"),
                durationMs = defaultDurs[idx],
                isImage = true,
                motionEffect = motionPattern(idx)
            )
        }
        val ops = mutableListOf<EditOperation>(
            trimOp,
            EditOperation.MotionEffect(index = 0, motionType = motionPattern(0)),
            EditOperation.Merge(items = mergeItems)
        )
        for (g in 0 until minOf(transitionsCount, imageCount - 1)) {
            ops.add(EditOperation.Transition(index = g, type = "fade", durationMs = transitionDurationMs))
        }
        if (captionCuesCount > 0) {
            val cues = (0 until captionCuesCount).map { c ->
                val st = c * 2800L
                val en = st + 2400L
                SubtitleCue(startTimeMs = st, endTimeMs = en, text = "Caption line ${c + 1}")
            }
            ops.add(
                EditOperation.AddSubtitles(
                    subtitlesUri = DummyUri.fromPath("/tmp/captions.srt"),
                    srtContent = "",
                    fileName = "captions.srt",
                    cues = cues,
                    position = TextPosition.BOTTOM_CENTER
                )
            )
        }
        if (audioDurationMs != null) {
            ops.add(
                EditOperation.AddBackgroundAudio(
                    audioUri = DummyUri.fromPath(audioPath),
                    volume = 1.0f,
                    internalStartMs = 0L,
                    internalEndMs = audioDurationMs,
                    startTimeMs = 0L,
                    endTimeMs = audioDurationMs,
                    originalDurationMs = audioDurationMs
                )
            )
        }
        val project = VideoProject(
            sourceUri = DummyUri.fromPath("/tmp/img_0.jpg"),
            sourceName = "img_0.jpg",
            operations = ops,
            canvasAspectRatio = CanvasAspectRatio.LANDSCAPE_16_9
        )
        return project to ops
    }

    @Test
    fun testExportMemoryProfileTiersAndNeverLowersResolution() {
        // Simulated Infinix Smart 7 HD: 2GB RAM, 128 MiB Java heap, isLowRamDevice=true
        val low360 = ExportMemoryProfile.fromMetrics(
            totalMemBytes = 1950L * MB,
            availMemBytes = 420L * MB,
            isLowMemoryFlag = false,
            isLowRamDevice = true,
            targetWidth = 640,
            targetHeight = 360,
            javaHeapMaxBytes = 128L * MB,
            javaHeapUsedBytes = 32L * MB,
            nativeHeapAllocatedBytes = 48L * MB
        )
        assertTrue(low360.isLowMemoryTier)
        assertEquals(3, low360.maxChunkSize)
        assertEquals(1, low360.softwareEncoderThreads)
        assertEquals(720.0, low360.motionSpaceShortEdge, 0.01)

        val low1080 = ExportMemoryProfile.fromMetrics(
            totalMemBytes = 1950L * MB,
            availMemBytes = 420L * MB,
            isLowMemoryFlag = false,
            isLowRamDevice = true,
            targetWidth = 1920,
            targetHeight = 1080,
            javaHeapMaxBytes = 128L * MB,
            javaHeapUsedBytes = 40L * MB,
            nativeHeapAllocatedBytes = 64L * MB
        )
        assertTrue(low1080.isLowMemoryTier)
        assertEquals(2, low1080.maxChunkSize)
        assertEquals(1, low1080.softwareEncoderThreads)
        assertEquals(1080.0, low1080.motionSpaceShortEdge, 0.01)
        assertTrue("1080p prep max edge must stay >= 2208", low1080.imagePrepMaxEdge >= 2208)

        // Constrained Java heap (128 MiB) must trigger LOW tier even if totalMem looks higher
        val heapConstrained = ExportMemoryProfile.fromMetrics(
            totalMemBytes = 4L * GB,
            availMemBytes = 1600L * MB,
            isLowMemoryFlag = false,
            isLowRamDevice = false,
            targetWidth = 1920,
            targetHeight = 1080,
            javaHeapMaxBytes = 128L * MB,
            javaHeapUsedBytes = 110L * MB // free < 24MB -> CRITICAL_LOW
        )
        assertTrue(heapConstrained.isLowMemoryTier)
        assertTrue(heapConstrained.isJavaHeapConstrained)
        assertEquals("CRITICAL_LOW", heapConstrained.memoryTierLabel)
        assertEquals(2, heapConstrained.maxChunkSize)
        assertEquals(1, heapConstrained.softwareEncoderThreads)

        // Mid 3.8GB RAM device
        val mid1080 = ExportMemoryProfile.fromMetrics(
            totalMemBytes = 3800L * MB,
            availMemBytes = 1100L * MB,
            isLowMemoryFlag = false,
            isLowRamDevice = false,
            targetWidth = 1920,
            targetHeight = 1080,
            javaHeapMaxBytes = 512L * MB,
            javaHeapUsedBytes = 48L * MB
        )
        assertTrue(mid1080.isMidMemoryTier)
        assertEquals(5, mid1080.maxChunkSize)
        assertEquals(2, mid1080.softwareEncoderThreads)

        // High 8GB RAM device
        val high1080 = ExportMemoryProfile.fromMetrics(
            totalMemBytes = 8L * GB,
            availMemBytes = 4L * GB,
            isLowMemoryFlag = false,
            isLowRamDevice = false,
            targetWidth = 1920,
            targetHeight = 1080,
            javaHeapMaxBytes = 512L * MB,
            javaHeapUsedBytes = 48L * MB
        )
        assertFalse(high1080.isLowMemoryTier)
        assertFalse(high1080.isMidMemoryTier)
        assertEquals("HIGH", high1080.memoryTierLabel)
        assertEquals(10, high1080.maxChunkSize)
        assertEquals(0, high1080.softwareEncoderThreads)

        // Verify EncoderSelector honors thread cap
        val swLow = EncoderSelector.selectEncoder(
            preference = EncoderSelector.EncoderPreference.SOFTWARE,
            targetWidth = 1920,
            targetHeight = 1080,
            fps = 30,
            softwareThreads = low1080.softwareEncoderThreads
        )
        assertTrue(swLow.ffmpegVideoCodecArgs.contains("-threads 1"))
        val fb = EncoderSelector.buildSoftwareFallbackCommand(
            command = "-y -i in.mp4 -c:v h264_mediacodec -pix_fmt yuv420p -b:v 8M out.mp4",
            crf = 23,
            softwareThreads = 1
        )
        assertTrue(fb.contains("-threads 1"))
        assertFalse(fb.contains("-b:v"))
    }

    @Test
    fun testAppLoggerBreadcrumbDetectionAndExitReasonClassification() {
        val unfinishedLines = listOf(
            "EXPORT_STARTED 2026-11-17T00:00:00.000+0000 mode=chunked",
            "STAGE_TIMING 2026-11-17T00:00:01.000+0000 stage=chunk_1",
            "SYNC_PROBE 2026-11-17T00:00:02.000+0000 chunk=1 drift=+0.000s",
            "MEMORY_EVENT 2026-11-17T00:00:03.000+0000 stage=after_chunk_1"
        )
        val breadcrumb = AppLogger.findUnfinishedExportBreadcrumb(unfinishedLines)
        assertNotNull("Must detect died midway even when trailing lines are telemetry logs", breadcrumb)
        assertTrue(breadcrumb!!.startsWith("MEMORY_EVENT"))

        val finishedLines = unfinishedLines + "EXPORT_ENDED 2026-11-17T00:00:10.000+0000 returnCode=0"
        assertNull(AppLogger.findUnfinishedExportBreadcrumb(finishedLines))

        val failedLines = unfinishedLines + "EXPORT_FAILED 2026-11-17T00:00:10.000+0000 reason=err"
        assertNull(AppLogger.findUnfinishedExportBreadcrumb(failedLines))

        assertEquals("LMK_OR_LOW_MEMORY", AppLogger.classifyExitReason(3, 0))
        assertEquals("SIGKILL_LMK_PROBABLE", AppLogger.classifyExitReason(2, 9))
        assertEquals("NATIVE_CRASH", AppLogger.classifyExitReason(5, 11))
        assertEquals("JAVA_CRASH", AppLogger.classifyExitReason(4, 0))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", AppLogger.classifyExitReason(9, 0))
    }

    @Test
    fun testExportMediaProbeParsingAndFailFastDriftDetection() {
        val sampleJson = """
            {
              "streams": [
                {
                  "codec_type": "video",
                  "width": 1920,
                  "height": 1080,
                  "r_frame_rate": "30/1",
                  "avg_frame_rate": "30/1",
                  "time_base": "1/30000",
                  "start_time": "0.000000",
                  "duration": "10.000000",
                  "nb_frames": "300"
                },
                {
                  "codec_type": "audio",
                  "codec_name": "aac",
                  "sample_rate": "44100",
                  "start_time": "0.000000",
                  "duration": "10.020000"
                }
              ],
              "format": {
                "duration": "10.020000",
                "start_time": "0.000000"
              }
            }
        """.trimIndent()
        val firstPkt = """{"packets":[{"pts_time":"0.000000","dts_time":"0.000000"}]}"""
        val lastPkt = """{"packets":[{"pts_time":"9.966667","dts_time":"9.966667"}]}"""
        val firstAudio = """{"packets":[{"pts_time":"0.000000"}]}"""

        val parsed = ExportMediaProbe.parseFfprobeJson(sampleJson, firstPkt, lastPkt, firstAudio, "/tmp/test.mp4")
        assertTrue(parsed.hasVideo)
        assertTrue(parsed.hasAudio)
        assertEquals(300, parsed.videoFrames)
        assertEquals(30.0, parsed.videoFps ?: 0.0, 0.01)
        assertEquals("1/30000", parsed.videoTimeBase)
        assertEquals(0.0, parsed.firstVideoPtsSec ?: -1.0, 0.001)
        assertEquals(9.966667, parsed.lastVideoPtsSec ?: -1.0, 0.001)

        val okVerify = ExportMediaProbe.verifyFinalMedia(
            probe = parsed,
            expectedVideoDurationSec = 10.0,
            expectedTotalFrames = 300,
            expectedFps = 30,
            expectedAudio = true,
            expectedAudioDurationSec = 10.0
        )
        assertTrue("Valid probe must pass: ${okVerify.diagnosticLine}", okVerify.isValid)

        val driftedVerify = ExportMediaProbe.verifyFinalMedia(
            probe = parsed.copy(videoDurationSec = 10.0, audioDurationSec = 11.4),
            expectedVideoDurationSec = 10.0,
            expectedTotalFrames = 300,
            expectedFps = 30,
            expectedAudio = true,
            expectedAudioDurationSec = 10.0
        )
        assertFalse("Severe A/V duration drift must fail verification", driftedVerify.isValid)
        assertNotNull(driftedVerify.failureReason)
    }

    @Test
    fun testScenario1_179Images_360p_24Fps_WithVoiceover_BresenhamExactFrames() {
        val durations = (0 until 179).map { idx ->
            when {
                idx == 178 -> 2460L
                idx % 3 == 0 -> 2150L
                idx % 3 == 1 -> 2330L
                else -> 2400L
            }
        }
        val totalMs = durations.sum()
        val (project, _) = buildImageProject(
            imageCount = 179,
            durationsMs = durations,
            audioDurationMs = totalMs
        )
        val vm = VideoEditingViewModel()
        vm.setExportSettings(resolution = 360, fps = 24, audioOnly = false)
        vm.loadProject(project)

        val plan = vm.buildChunkExportPlan("/tmp/img_0.jpg", "/tmp/out_360.mp4")!!
        assertTrue(plan.isChunked)
        assertFalse("Zero duplicate last-chunk encode", plan.needsVideoPadding)
        assertEquals(640, plan.targetWidth)
        assertEquals(360, plan.targetHeight)
        assertEquals(24, plan.fps)
        assertTrue(plan.canStreamCopyVideoWithAudioMux)

        val expectedTotalFrames = kotlin.math.round((totalMs / 1000.0) * 24).toInt()
        val sumChunkFrames = plan.chunkSpecs.sumOf { it.totalFrames }
        assertEquals("Cumulative chunk frames must exactly equal total timeline frames", expectedTotalFrames, sumChunkFrames)
        for ((i, spec) in plan.chunkSpecs.withIndex()) {
            assertEquals("Chunk $i totalFrames must equal sum of clip exactFrames", spec.clips.sumOf { it.exactFrames }, spec.totalFrames)
            val cmd = plan.chunkCommands[i].command
            assertTrue("Chunk $i must lock exact output frame count", cmd.contains("-frames:v ${spec.totalFrames}"))
            assertTrue("Chunk $i must lock timescale", cmd.contains("-video_track_timescale 24000"))
        }

        // Verify low-RAM ChunkPlanner caps chunk size at 3 for 360p while preserving exact frames
        val sequenceItems = listOf(EditOperation.MergeItem(uri = DummyUri.fromPath("/tmp/img_0.jpg"), durationMs = durations[0], isImage = true, motionEffect = "zoom_in")) +
            (1 until 179).map { EditOperation.MergeItem(uri = DummyUri.fromPath("/tmp/img_$it.jpg"), durationMs = durations[it], isImage = true, motionEffect = "zoom_out") }
        val lowRamPlan = ChunkPlanner.plan(
            sequenceItems = sequenceItems,
            transitions = emptyList(),
            fps = 24,
            maxChunkSize = 3,
            chunkTriggerThreshold = 8
        )
        assertTrue(lowRamPlan.chunks.all { it.clips.size <= 3 })
        assertEquals(expectedTotalFrames, lowRamPlan.chunks.sumOf { it.totalFrames })
    }

    @Test
    fun testScenarios2And3_179Images_720pAnd1080p_PreserveResolutionAndChunkCaps() {
        val (project, _) = buildImageProject(imageCount = 179, audioDurationMs = 179 * 2300L)
        val vm = VideoEditingViewModel()

        // 720p
        vm.setExportSettings(resolution = 720, fps = 30, audioOnly = false)
        vm.loadProject(project)
        val plan720 = vm.buildChunkExportPlan("/tmp/img_0.jpg", "/tmp/out_720.mp4")!!
        assertEquals(1280, plan720.targetWidth)
        assertEquals(720, plan720.targetHeight)
        assertEquals(30, plan720.fps)
        assertTrue(plan720.isChunked)
        assertTrue(plan720.chunkCommands.all { it.command.contains("1280x720") || it.command.contains("scale=1280:720") })

        // 1080p - never lowers user resolution
        vm.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)
        vm.loadProject(project)
        val plan1080 = vm.buildChunkExportPlan("/tmp/img_0.jpg", "/tmp/out_1080.mp4")!!
        assertEquals(1920, plan1080.targetWidth)
        assertEquals(1080, plan1080.targetHeight)
        assertTrue(plan1080.isChunked)
        assertTrue(plan1080.chunkCommands.all { it.command.contains("1920x1080") || it.command.contains("scale=1920:1080") })
    }

    @Test
    fun testScenario4And5_65Images_1080p_NoTransitionsAndWith64Transitions() {
        val (projectNoTrans, _) = buildImageProject(imageCount = 65, transitionsCount = 0)
        val vm = VideoEditingViewModel()
        vm.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)
        vm.loadProject(projectNoTrans)

        val planNoTrans = vm.buildChunkExportPlan("/tmp/img_0.jpg", "/tmp/out_65_notrans.mp4")!!
        assertTrue(planNoTrans.isChunked)
        assertTrue(planNoTrans.canStreamCopyFinalPass)
        assertEquals(1920, planNoTrans.targetWidth)
        assertEquals(1080, planNoTrans.targetHeight)
        assertEquals(kotlin.math.round((65 * 2.3) * 30).toInt(), planNoTrans.chunkSpecs.sumOf { it.totalFrames })

        val (projectTrans, _) = buildImageProject(
            imageCount = 65,
            durationsMs = List(65) { 3000L },
            transitionsCount = 64,
            transitionDurationMs = 500L
        )
        vm.loadProject(projectTrans)
        val planTrans = vm.buildChunkExportPlan("/tmp/img_0.jpg", "/tmp/out_65_trans.mp4")!!
        assertTrue(planTrans.isChunked)
        assertTrue("Stream copy must be enabled via boundary containment", planTrans.canStreamCopyFinalPass)
        assertTrue("Every chunk must contain xfade transitions", planTrans.chunkCommands.any { it.command.contains("xfade=") })
        assertEquals(((65L * 3000L) - (64L * 500L)) / 1000.0, planTrans.totalDurationSecs, 0.05)
    }

    @Test
    fun testScenario6And7_CaptionsOnly_And_TransitionsPlusCaptions_NoTopLineGarble() {
        val (capProject, _) = buildImageProject(
            imageCount = 30,
            durationsMs = List(30) { 4000L },
            transitionsCount = 0,
            captionCuesCount = 40
        )
        val vm = VideoEditingViewModel()
        vm.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)
        vm.loadProject(capProject)
        val capPlan = vm.buildChunkExportPlan("/tmp/img_0.jpg", "/tmp/out_cap.mp4")!!
        assertTrue(capPlan.isChunked)
        val allCapFilters = capPlan.chunkCommands.joinToString("\n") { it.command }
        assertTrue("Captions must include drawtext", allCapFilters.contains("drawtext="))
        assertTrue("Captions must disable expansion", allCapFilters.contains("expansion=none"))
        assertFalse("Top-left default y=0 coordinate must not appear for bottom subtitles", allCapFilters.contains(":y=0:"))

        val (bothProject, _) = buildImageProject(
            imageCount = 65,
            durationsMs = List(65) { 3000L },
            transitionsCount = 64,
            transitionDurationMs = 500L,
            captionCuesCount = 50
        )
        vm.loadProject(bothProject)
        val bothPlan = vm.buildChunkExportPlan("/tmp/img_0.jpg", "/tmp/out_both.mp4")!!
        assertTrue(bothPlan.isChunked)
        assertTrue(bothPlan.canStreamCopyFinalPass)
        val allBothFilters = bothPlan.chunkCommands.joinToString("\n") { it.command }
        assertTrue(allBothFilters.contains("xfade="))
        assertTrue(allBothFilters.contains("drawtext="))
        assertFalse("Top y=0 fallback must not appear", allBothFilters.contains(":y=0:"))
    }

    @Test
    fun testVoiceoverAudioMix_StreamCopyForRealM4aAacAndDirectTranscodeForMp3() {
        val tempDir = createTempDirCompat("vydra_audio_test")
        try {
            val dummyMp3 = File(tempDir, "voiceover.mp3").apply { writeBytes(ByteArray(256)) }
            val (mp3Proj, _) = buildImageProject(imageCount = 20, audioDurationMs = 46_000L, audioPath = dummyMp3.absolutePath)
            val vm = VideoEditingViewModel()
            vm.setExportSettings(resolution = 360, fps = 24, audioOnly = false)
            vm.loadProject(mp3Proj)
            val mp3Plan = vm.buildChunkExportPlan("/tmp/img_0.jpg", "${tempDir.absolutePath}/out_mp3.mp4")!!
            val mp3Cmd = mp3Plan.audioMixCommand!!
            assertFalse("Single unmodified MP3 should avoid filter_complex_script overhead", mp3Cmd.contains("-filter_complex"))
            assertTrue("Single unmodified MP3 must directly transcode to AAC", mp3Cmd.contains("-c:a aac"))

            val realHeaderM4a = File(tempDir, "voiceover.m4a")
            val ftypAndMp4a = byteArrayOf(
                0, 0, 0, 20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(),
                0, 0, 0, 0, 'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), 'a'.code.toByte(),
                0, 0, 0, 12, 's'.code.toByte(), 't'.code.toByte(), 's'.code.toByte(), 'd'.code.toByte(),
                0, 0, 0, 0
            )
            realHeaderM4a.writeBytes(ftypAndMp4a)
            val (m4aProj, _) = buildImageProject(imageCount = 20, audioDurationMs = 46_000L, audioPath = realHeaderM4a.absolutePath)
            vm.loadProject(m4aProj)
            val m4aPlan = vm.buildChunkExportPlan("/tmp/img_0.jpg", "${tempDir.absolutePath}/out_m4a.mp4")!!
            val m4aCmd = m4aPlan.audioMixCommand!!
            assertTrue("Confirmed AAC-in-M4A full voiceover must use instant -c:a copy", m4aCmd.contains("-c:a copy"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testHostE2E_TestA_65Images_1080p_30Fps_TransitionsCaptionsAndSinglePassTailPad() {
        if (!hasHostFfmpeg()) return
        val tempDir = createTempDirCompat("vydra_test_a_65")
        try {
            val totalStartNs = System.nanoTime()
            val palette = listOf("0x336699", "0x993366", "0x339966", "0x996633", "0x5555AA")
            val baseImages = (0 until 5).map { p ->
                val f = File(tempDir, "base_1080_$p.jpg")
                runProc("/usr/bin/ffmpeg", "-y", "-f", "lavfi", "-i", "color=c=${palette[p]}:s=1920x1080:d=0.1", "-frames:v", "1", f.absolutePath)
                f
            }
            val imageFiles = (0 until 65).map { idx -> baseImages[idx % 5] }

            // 65 clips * 1200ms - 64 * 200ms fade = 65,200ms (65.200s) + 1.000s voiceover tail = 66.200s
            // 1200ms gives 500ms interior margin after 200ms in/out transitions so ChunkPlanner contains all boundaries for -c copy!
            val durationsMs = List(65) { 1200L }
            val transDurMs = 200L
            val videoOnlyMs = (65L * 1200L) - (64L * transDurMs) // 65,200ms
            val audioDurMs = videoOnlyMs + 1000L // 66,200ms -> forces tailPadFrames = 30 frames in LAST chunk!
            val expectedDurationSec = 66.200
            val expectedFrames = 1986

            val voiceoverM4a = File(tempDir, "voiceover_65.m4a")
            runProc(
                "/usr/bin/ffmpeg", "-y", "-f", "lavfi",
                "-i", "sine=frequency=440:sample_rate=44100:duration=${audioDurMs / 1000.0}",
                "-c:a", "aac", "-b:a", "128k", voiceoverM4a.absolutePath
            )

            val mergeItems = (1 until 65).map { idx ->
                EditOperation.MergeItem(
                    uri = DummyUri.fromPath(imageFiles[idx].absolutePath),
                    durationMs = durationsMs[idx],
                    isImage = true,
                    motionEffect = if (idx % 2 == 0) "zoom_in" else "zoom_out"
                )
            }
            val ops = mutableListOf<EditOperation>(
                EditOperation.Trim(startMs = 0L, endMs = durationsMs[0]),
                EditOperation.MotionEffect(index = 0, motionType = "zoom_in"),
                EditOperation.Merge(items = mergeItems)
            )
            for (g in 0 until 64) {
                ops.add(EditOperation.Transition(index = g, type = "fade", durationMs = transDurMs))
            }
            val fontAsset = findFontFile()
            if (fontAsset != null) {
                val cues = (0 until 15).map { c ->
                    SubtitleCue(c * 2000L, c * 2000L + 1800L, "Sub line ${c + 1}")
                }
                ops.add(
                    EditOperation.AddSubtitles(
                        subtitlesUri = DummyUri.fromPath("/tmp/caps.srt"),
                        srtContent = "",
                        fileName = "caps.srt",
                        cues = cues,
                        position = TextPosition.BOTTOM_CENTER
                    )
                )
            }
            ops.add(
                EditOperation.AddBackgroundAudio(
                    audioUri = DummyUri.fromPath(voiceoverM4a.absolutePath),
                    volume = 1.0f,
                    internalStartMs = 0L,
                    internalEndMs = audioDurMs,
                    startTimeMs = 0L,
                    endTimeMs = audioDurMs,
                    originalDurationMs = audioDurMs
                )
            )

            val project = VideoProject(
                sourceUri = DummyUri.fromPath(imageFiles[0].absolutePath),
                sourceName = "base_1080_0.jpg",
                operations = ops,
                canvasAspectRatio = CanvasAspectRatio.LANDSCAPE_16_9
            )
            val vm = VideoEditingViewModel()
            vm.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)
            vm.loadProject(project)

            val planStartNs = System.nanoTime()
            val outMp4 = File(tempDir, "testA_final_1080p.mp4")
            val plan = vm.buildChunkExportPlan(imageFiles[0].absolutePath, outMp4.absolutePath, fontAsset?.absolutePath, null)!!
            val planSecs = (System.nanoTime() - planStartNs) / 1e9

            assertTrue(plan.isChunked)
            assertFalse("Last chunk duplicate re-encode must be eliminated", plan.needsVideoPadding)
            assertTrue("Last chunk must fold 30 tailPadFrames directly into initial render", plan.chunkCommands.last().tailPadFrames == 30)
            assertTrue(" preceding chunks must have 0 tailPadFrames", plan.chunkCommands.dropLast(1).all { it.tailPadFrames == 0 })
            assertTrue("Final assembly must stream-copy video + mux audio", plan.canStreamCopyVideoWithAudioMux)
            assertEquals(expectedFrames, plan.chunkSpecs.sumOf { it.totalFrames })

            // Rewrite chunk paths & execute chunks using ultrafast libx264 inside tempDir
            val chunkStartNs = System.nanoTime()
            val renderedChunks = mutableListOf<File>()
            var cumPlannedEnd = 0.0
            var cumActualEnd = 0.0
            for ((idx, spec) in plan.chunkSpecs.withIndex()) {
                val outChunk = File(tempDir, "testA_chunk_$idx.mp4")
                val tailPad = plan.chunkCommands[idx].tailPadFrames
                val cmd = vm.buildChunkPassCommand(
                    chunkSpec = spec,
                    sourceFilePath = imageFiles[0].absolutePath,
                    chunkOutputPath = outChunk.absolutePath,
                    fontFilePath = fontAsset?.absolutePath,
                    context = null,
                    totalDurationMs = kotlin.math.round(plan.totalDurationSecs * 1000.0).toLong(),
                    density = 1.0f,
                    targetWidth = 1920,
                    targetHeight = 1080,
                    operations = plan.operations,
                    encoderOverride = "-c:v libx264 -preset ultrafast -crf 28 -threads 2 -pix_fmt yuv420p",
                    tailPadFrames = tailPad
                )
                runProc(*((listOf("/usr/bin/ffmpeg") + tokenizeCommand(cmd.command)).toTypedArray()))
                val probe = hostProbeFile(outChunk.absolutePath)
                val startSec = cumPlannedEnd
                val endSec = startSec + cmd.durationSecs
                cumPlannedEnd = endSec
                cumActualEnd += (probe.effectiveVideoDurationSec ?: cmd.durationSecs)
                val check = ExportMediaProbe.verifyChunkMedia(
                    probe = probe,
                    chunkIndex = idx,
                    plannedStartSec = startSec,
                    plannedEndSec = endSec,
                    plannedDurationSec = cmd.durationSecs,
                    expectedFrames = spec.totalFrames,
                    expectedFps = 30,
                    transitionOverlapSec = 0.0,
                    plannedCumulativeEndSec = cumPlannedEnd,
                    actualCumulativeEndSec = cumActualEnd
                )
                assertTrue(check.failureReason ?: "Chunk $idx verification failed", check.isValid)
                renderedChunks.add(outChunk)
            }
            val chunksSecs = (System.nanoTime() - chunkStartNs) / 1e9

            val audioStartNs = System.nanoTime()
            val mixedAudio = File(tempDir, "testA_audio.m4a")
            val audioCmd = vm.buildAudioMixCommand(
                operations = plan.operations,
                outputAudioPath = mixedAudio.absolutePath,
                targetDurationSecs = plan.totalDurationSecs,
                context = null,
                audioBitrate = "128k"
            )!!
            runProc(*((listOf("/usr/bin/ffmpeg") + tokenizeCommand(audioCmd)).toTypedArray()))
            val audioSecs = (System.nanoTime() - audioStartNs) / 1e9

            val assemblyStartNs = System.nanoTime()
            val concatTxt = File(tempDir, "testA_concat.txt")
            concatTxt.writeText(buildString {
                renderedChunks.forEachIndexed { idx, f ->
                    append("file '${f.absolutePath}'\n")
                    append(String.format(Locale.US, "duration %.6f\n", plan.chunkSpecs[idx].totalFrames / 30.0))
                }
            })
            runProc(
                "/usr/bin/ffmpeg", "-y", "-f", "concat", "-safe", "0",
                "-i", concatTxt.absolutePath,
                "-i", mixedAudio.absolutePath,
                "-map", "0:v", "-map", "1:a", "-c", "copy",
                "-movflags", "+faststart", "-t", String.format(Locale.US, "%.6f", expectedDurationSec),
                outMp4.absolutePath
            )
            val assemblySecs = (System.nanoTime() - assemblyStartNs) / 1e9

            val finalProbe = hostProbeFile(outMp4.absolutePath)
            val finalVerify = ExportMediaProbe.verifyFinalMedia(
                probe = finalProbe,
                expectedVideoDurationSec = expectedDurationSec,
                expectedTotalFrames = expectedFrames,
                expectedFps = 30,
                expectedAudio = true,
                expectedAudioDurationSec = audioDurMs / 1000.0
            )
            assertTrue(finalVerify.failureReason ?: "Final TestA failed", finalVerify.isValid)
            assertEquals("1920x1080 preserved", 1920, finalProbe.videoWidth)
            assertEquals("1920x1080 preserved", 1080, finalProbe.videoHeight)
            assertEquals("Exact 915 frames", expectedFrames, finalProbe.videoFrames)
            val totalSecs = (System.nanoTime() - totalStartNs) / 1e9

            println(
                String.format(
                    Locale.US,
                    "HOST_E2E_TEST_A (65 imgs, 1080p@30, 64 transitions, captions, tailPad=30f): " +
                        "chunks=%d plan=%.3fs chunksRender=%.3fs audio=%.3fs streamCopyAssembly=%.3fs total=%.3fs | %s",
                    renderedChunks.size, planSecs, chunksSecs, audioSecs, assemblySecs, totalSecs, finalVerify.diagnosticLine
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testHostE2E_TestB_179Images_360p_30Fps_WithVoiceover_MultiCheckpointSyncVerification() {
        if (!hasHostFfmpeg()) return
        val tempDir = createTempDirCompat("vydra_test_b_179")
        try {
            val totalStartNs = System.nanoTime()
            val palette = listOf("0x225588", "0x882255", "0x228855", "0x885522", "0x4444AA")
            val baseImages = (0 until 5).map { p ->
                val f = File(tempDir, "base_360_$p.jpg")
                runProc("/usr/bin/ffmpeg", "-y", "-f", "lavfi", "-i", "color=c=${palette[p]}:s=640x360:d=0.1", "-frames:v", "1", f.absolutePath)
                f
            }
            val imageFiles = (0 until 179).map { idx -> baseImages[idx % 5] }

            // 179 fractional durations summing to ~92.42s @ 30fps (390..620ms per image) + 1.0s tail pad
            val durationsMs = (0 until 179).map { idx ->
                when (idx % 4) {
                    0 -> 430L
                    1 -> 510L
                    2 -> 570L
                    else -> 490L
                }
            } // mean 500ms, total = 89,500ms + fractional mix -> adjust last clip to non-round ms:
                .mapIndexed { idx, d -> if (idx == 178) 540L else d }
            val videoTotalMs = durationsMs.sum() // 89,550ms -> 2687 frames = 89.566667s
            val baseVideoFrames = kotlin.math.round((videoTotalMs / 1000.0) * 30).toInt()
            val audioDurMs = videoTotalMs + 1000L // 90,550ms -> 30 tailPadFrames in final chunk!

            val voiceoverM4a = File(tempDir, "voiceover_179.m4a")
            runProc(
                "/usr/bin/ffmpeg", "-y", "-f", "lavfi",
                "-i", "sine=frequency=523:sample_rate=44100:duration=${audioDurMs / 1000.0}",
                "-c:a", "aac", "-b:a", "96k", voiceoverM4a.absolutePath
            )

            // Also verify MP3 voiceover transcode path works cleanly on 179-image plan
            val voiceoverMp3 = File(tempDir, "voiceover_179.mp3")
            voiceoverMp3.writeBytes(ByteArray(256))

            val seqItems = (0 until 179).map { idx ->
                EditOperation.MergeItem(
                    uri = DummyUri.fromPath(imageFiles[idx].absolutePath),
                    durationMs = durationsMs[idx],
                    isImage = true,
                    motionEffect = if (idx % 2 == 0) "zoom_in" else "zoom_out"
                )
            }

            // Use low-RAM maxChunkSize=3 -> 60 sequential chunks for 179 images!
            val lowChunkPlan = ChunkPlanner.plan(
                sequenceItems = seqItems,
                transitions = emptyList(),
                fps = 30,
                maxChunkSize = 3,
                chunkTriggerThreshold = 8
            )
            assertTrue("179 images with low-RAM cap 3 must split into >= 59 chunks", lowChunkPlan.chunks.size >= 59)
            assertEquals(baseVideoFrames, lowChunkPlan.chunks.sumOf { it.totalFrames })

            // Fold tail padding into last chunk (matching VideoEditingViewModel.buildChunkExportPlan)
            val tailPadFrames = 30
            val tailPadMs = 1000L
            val effectiveChunks = lowChunkPlan.chunks.mapIndexed { idx, c ->
                if (idx == lowChunkPlan.chunks.lastIndex) {
                    c.copy(globalEndMs = c.globalEndMs + tailPadMs, durationMs = c.durationMs + tailPadMs, totalFrames = c.totalFrames + tailPadFrames)
                } else c
            }
            val totalExpectedFrames = effectiveChunks.sumOf { it.totalFrames }
            val totalExpectedDurSec = totalExpectedFrames / 30.0

            val vm = VideoEditingViewModel()
            vm.setExportSettings(resolution = 360, fps = 30, audioOnly = false)
            val ops = listOf(
                EditOperation.AddBackgroundAudio(
                    audioUri = DummyUri.fromPath(voiceoverM4a.absolutePath),
                    volume = 1.0f,
                    internalStartMs = 0L,
                    internalEndMs = audioDurMs,
                    startTimeMs = 0L,
                    endTimeMs = audioDurMs,
                    originalDurationMs = audioDurMs
                )
            )

            // Execute all 60 sequential chunks & verify cumulative timing at EVERY chunk!
            val chunksStartNs = System.nanoTime()
            val chunkFiles = mutableListOf<File>()
            val chunkEndCheckpoints = mutableListOf<Triple<Int, Double, Double>>() // idx, plannedCumEnd, actualCumEnd
            var cumPlannedEnd = 0.0
            var cumActualEnd = 0.0

            for ((idx, spec) in effectiveChunks.withIndex()) {
                val outChunk = File(tempDir, "testB_chunk_$idx.mp4")
                val pad = if (idx == effectiveChunks.lastIndex) tailPadFrames else 0
                val cmd = vm.buildChunkPassCommand(
                    chunkSpec = spec,
                    sourceFilePath = imageFiles[0].absolutePath,
                    chunkOutputPath = outChunk.absolutePath,
                    fontFilePath = null,
                    context = null,
                    totalDurationMs = videoTotalMs + tailPadMs,
                    density = 1.0f,
                    targetWidth = 640,
                    targetHeight = 360,
                    operations = ops,
                    encoderOverride = "-c:v libx264 -preset ultrafast -crf 28 -threads 1 -pix_fmt yuv420p",
                    tailPadFrames = pad
                )
                runProc(*((listOf("/usr/bin/ffmpeg") + tokenizeCommand(cmd.command)).toTypedArray()))
                val probe = hostProbeFile(outChunk.absolutePath, probePackets = (idx == 0 || idx == effectiveChunks.size / 2 || idx == (effectiveChunks.size * 3) / 4 || idx == effectiveChunks.lastIndex))
                val plannedStart = cumPlannedEnd
                val plannedEnd = plannedStart + cmd.durationSecs
                cumPlannedEnd = plannedEnd
                cumActualEnd += (probe.effectiveVideoDurationSec ?: cmd.durationSecs)
                chunkEndCheckpoints.add(Triple(idx, cumPlannedEnd, cumActualEnd))

                val check = ExportMediaProbe.verifyChunkMedia(
                    probe = probe,
                    chunkIndex = idx,
                    plannedStartSec = plannedStart,
                    plannedEndSec = plannedEnd,
                    plannedDurationSec = cmd.durationSecs,
                    expectedFrames = spec.totalFrames,
                    expectedFps = 30,
                    transitionOverlapSec = 0.0,
                    plannedCumulativeEndSec = cumPlannedEnd,
                    actualCumulativeEndSec = cumActualEnd
                )
                assertTrue(check.failureReason ?: "Chunk $idx sync failed", check.isValid)
                chunkFiles.add(outChunk)
            }
            val chunksSecs = (System.nanoTime() - chunksStartNs) / 1e9

            // Audio stream-copy pass (.m4a)
            val mixedAudio = File(tempDir, "testB_audio.m4a")
            val audioMixCmd = vm.buildAudioMixCommand(ops, mixedAudio.absolutePath, totalExpectedDurSec, null, "96k")!!
            assertTrue(audioMixCmd.contains("-c:a copy"))
            runProc(*((listOf("/usr/bin/ffmpeg") + tokenizeCommand(audioMixCmd)).toTypedArray()))

            // Final -c copy concat + audio mux
            val concatTxt = File(tempDir, "testB_concat.txt")
            concatTxt.writeText(buildString {
                chunkFiles.forEachIndexed { idx, f ->
                    append("file '${f.absolutePath}'\n")
                    append(String.format(Locale.US, "duration %.6f\n", effectiveChunks[idx].totalFrames / 30.0))
                }
            })
            val outMp4 = File(tempDir, "testB_final_360p.mp4")
            val assemblyStartNs = System.nanoTime()
            runProc(
                "/usr/bin/ffmpeg", "-y", "-f", "concat", "-safe", "0",
                "-i", concatTxt.absolutePath,
                "-i", mixedAudio.absolutePath,
                "-map", "0:v", "-map", "1:a", "-c", "copy",
                "-movflags", "+faststart", "-t", String.format(Locale.US, "%.6f", totalExpectedDurSec),
                outMp4.absolutePath
            )
            val assemblySecs = (System.nanoTime() - assemblyStartNs) / 1e9

            val finalProbe = hostProbeFile(outMp4.absolutePath, probePackets = true)
            val finalVerify = ExportMediaProbe.verifyFinalMedia(
                probe = finalProbe,
                expectedVideoDurationSec = totalExpectedDurSec,
                expectedTotalFrames = totalExpectedFrames,
                expectedFps = 30,
                expectedAudio = true,
                expectedAudioDurationSec = audioDurMs / 1000.0
            )
            assertTrue(finalVerify.failureReason ?: "Final TestB failed", finalVerify.isValid)
            assertEquals("Exact cumulative 179-image frame count", totalExpectedFrames, finalProbe.videoFrames)
            assertEquals("Strict 30/1 CFR", "30/1", finalProbe.videoAvgFrameRate)

            // Verify checkpoints: Start (0s), Chunk 1 end, 50%, 75%, Final chunk end, and packet PTS in final MP4!
            val cpChunk1 = chunkEndCheckpoints.first()
            val cp50 = chunkEndCheckpoints[chunkEndCheckpoints.size / 2]
            val cp75 = chunkEndCheckpoints[(chunkEndCheckpoints.size * 3) / 4]
            val cpLast = chunkEndCheckpoints.last()

            assertTrue("Chunk 1 boundary drift <= 0.05s", abs(cpChunk1.third - cpChunk1.second) <= 0.05)
            assertTrue("50% boundary drift <= 0.05s", abs(cp50.third - cp50.second) <= 0.05)
            assertTrue("75% boundary drift <= 0.05s", abs(cp75.third - cp75.second) <= 0.05)
            assertTrue("Final chunk boundary drift <= 0.05s", abs(cpLast.third - cpLast.second) <= 0.05)

            // Probe actual packet PTS inside final MP4 at 50% and 75% to ensure zero mux drift!
            val midSeekSec = totalExpectedDurSec * 0.50
            val threeQuarterSeekSec = totalExpectedDurSec * 0.75
            val vPts50 = probeNearestPacketPts(outMp4.absolutePath, "v:0", midSeekSec)
            val aPts50 = probeNearestPacketPts(outMp4.absolutePath, "a:0", midSeekSec)
            val vPts75 = probeNearestPacketPts(outMp4.absolutePath, "v:0", threeQuarterSeekSec)
            val aPts75 = probeNearestPacketPts(outMp4.absolutePath, "a:0", threeQuarterSeekSec)
            assertTrue("50% final MP4 A/V PTS delta <= 0.06s (v=$vPts50, a=$aPts50)", vPts50 != null && aPts50 != null && abs(vPts50 - aPts50) <= 0.06)
            assertTrue("75% final MP4 A/V PTS delta <= 0.06s (v=$vPts75, a=$aPts75)", vPts75 != null && aPts75 != null && abs(vPts75 - aPts75) <= 0.06)

            val totalSecs = (System.nanoTime() - totalStartNs) / 1e9
            println(
                String.format(
                    Locale.US,
                    "HOST_E2E_TEST_B (179 imgs, 360p@30, lowRAM cap=3, threads=1, tailPad=30f): " +
                        "chunks=%d chunksRender=%.3fs streamCopyAssembly=%.3fs total=%.3fs | " +
                        "checkpoints[startV=%.3f startA=%.3f | c1 planned=%.3f actual=%.3f | 50pct planned=%.3f actual=%.3f vPts=%.3f aPts=%.3f | 75pct planned=%.3f actual=%.3f vPts=%.3f aPts=%.3f | end planned=%.3f actual=%.3f lastVPts=%s] | %s",
                    chunkFiles.size, chunksSecs, assemblySecs, totalSecs,
                    finalProbe.firstVideoPtsSec ?: -1.0, finalProbe.firstAudioPtsSec ?: -1.0,
                    cpChunk1.second, cpChunk1.third,
                    cp50.second, cp50.third, vPts50, aPts50,
                    cp75.second, cp75.third, vPts75, aPts75,
                    cpLast.second, cpLast.third, finalProbe.lastVideoPtsSec?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a",
                    finalVerify.diagnosticLine
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testHostE2E_TestC_179Images_1080p_SimulatedLowRamConstraints() {
        if (!hasHostFfmpeg()) return
        val tempDir = createTempDirCompat("vydra_test_c_lowram_sim")
        try {
            // Explicit SIMULATION of Infinix Smart 7 HD constraints
            val simProfile = ExportMemoryProfile.fromMetrics(
                totalMemBytes = 1950L * MB,
                availMemBytes = 420L * MB,
                isLowMemoryFlag = false,
                isLowRamDevice = true,
                targetWidth = 1920,
                targetHeight = 1080,
                javaHeapMaxBytes = 128L * MB,
                javaHeapUsedBytes = 42L * MB,
                nativeHeapAllocatedBytes = 56L * MB
            )
            assertTrue(simProfile.isLowMemoryTier)
            assertEquals("1080p low-RAM must enforce maxChunkSize=2", 2, simProfile.maxChunkSize)
            assertEquals("1080p low-RAM must enforce softwareEncoderThreads=1", 1, simProfile.softwareEncoderThreads)

            val imgA = File(tempDir, "sim_1080_a.jpg")
            val imgB = File(tempDir, "sim_1080_b.jpg")
            runProc("/usr/bin/ffmpeg", "-y", "-f", "lavfi", "-i", "color=c=0x114477:s=1920x1080:d=0.1", "-frames:v", "1", imgA.absolutePath)
            runProc("/usr/bin/ffmpeg", "-y", "-f", "lavfi", "-i", "color=c=0x774411:s=1920x1080:d=0.1", "-frames:v", "1", imgB.absolutePath)

            val durations = List(179) { 1000L }
            val seqItems = (0 until 179).map { idx ->
                EditOperation.MergeItem(
                    uri = DummyUri.fromPath(if (idx % 2 == 0) imgA.absolutePath else imgB.absolutePath),
                    durationMs = durations[idx],
                    isImage = true,
                    motionEffect = if (idx % 2 == 0) "zoom_in" else "zoom_out"
                )
            }

            val simPlan = ChunkPlanner.plan(
                sequenceItems = seqItems,
                transitions = emptyList(),
                fps = 30,
                maxChunkSize = simProfile.maxChunkSize,
                chunkTriggerThreshold = 8
            )
            assertEquals(90, simPlan.chunks.size)
            assertTrue(simPlan.chunks.all { it.clips.size <= 2 })
            assertEquals(179 * 30, simPlan.chunks.sumOf { it.totalFrames })

            val swEnc = EncoderSelector.selectEncoder(
                preference = EncoderSelector.EncoderPreference.SOFTWARE,
                targetWidth = 1920,
                targetHeight = 1080,
                fps = 30,
                softwareThreads = simProfile.softwareEncoderThreads
            )
            assertTrue(swEnc.ffmpegVideoCodecArgs.contains("-threads 1"))

            // Execute representative simulated chunks (first, middle, last) at full 1920x1080@30 with -threads 1
            val vm = VideoEditingViewModel()
            vm.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)
            val sampleIndices = listOf(0, simPlan.chunks.size / 2, simPlan.chunks.lastIndex)
            val sampleOutFiles = mutableListOf<File>()
            val renderStartNs = System.nanoTime()
            for (cIdx in sampleIndices) {
                val spec = simPlan.chunks[cIdx]
                val outChunk = File(tempDir, "sim_chunk_$cIdx.mp4")
                val cmd = vm.buildChunkPassCommand(
                    chunkSpec = spec,
                    sourceFilePath = imgA.absolutePath,
                    chunkOutputPath = outChunk.absolutePath,
                    fontFilePath = null,
                    context = null,
                    totalDurationMs = 179_000L,
                    density = 1.0f,
                    targetWidth = 1920,
                    targetHeight = 1080,
                    operations = emptyList(),
                    encoderOverride = "-c:v libx264 -preset ultrafast -crf 28 -threads ${simProfile.softwareEncoderThreads} -pix_fmt yuv420p"
                )
                assertTrue("Preserves 1080p dimensions", cmd.command.contains("1920x1080") || cmd.command.contains("scale=1920:1080"))
                runProc(*((listOf("/usr/bin/ffmpeg") + tokenizeCommand(cmd.command)).toTypedArray()))
                val probe = hostProbeFile(outChunk.absolutePath)
                assertEquals(1920, probe.videoWidth)
                assertEquals(1080, probe.videoHeight)
                assertEquals(spec.totalFrames, probe.videoFrames)
                sampleOutFiles.add(outChunk)
            }
            val sampleRenderSecs = (System.nanoTime() - renderStartNs) / 1e9

            println(
                String.format(
                    Locale.US,
                    "[SIMULATION - NOT PHYSICAL DEVICE] HOST_E2E_TEST_C (179 imgs, 1080p@30, simulated 2GB/128MB heap Infinix Smart 7 HD): " +
                        "tier=%s plannedChunks=%d maxChunkSize=%d swThreads=%d preservedDim=1920x1080@30 sampleChunksRendered=%d time=%.3fs",
                    simProfile.memoryTierLabel, simPlan.chunks.size, simProfile.maxChunkSize,
                    simProfile.softwareEncoderThreads, sampleOutFiles.size, sampleRenderSecs
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun hasHostFfmpeg(): Boolean = File("/usr/bin/ffmpeg").exists() && File("/usr/bin/ffprobe").exists()

    private fun findFontFile(): File? = listOf(
        File("app/src/main/assets/fonts/Roboto-Regular.ttf"),
        File("src/main/assets/fonts/Roboto-Regular.ttf")
    ).firstOrNull { it.exists() }

    private fun hostProbeFile(path: String, probePackets: Boolean = true): ExportMediaProbe.MediaProbeResult {
        val baseJson = runProcOutput("/usr/bin/ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path)
        if (!probePackets) {
            return ExportMediaProbe.parseFfprobeJson(baseJson, null, null, null, path)
        }
        val base = ExportMediaProbe.parseFfprobeJson(baseJson, null, null, null, path)
        val firstV = runProcOutput("/usr/bin/ffprobe", "-v", "error", "-read_intervals", "0%+0.25", "-select_streams", "v:0", "-show_entries", "packet=pts_time,dts_time", "-print_format", "json", path)
        val tailSeek = maxOf(0.0, (base.effectiveVideoDurationSec ?: 0.0) - 0.5)
        val interval = if (tailSeek > 0.0) "${String.format(Locale.US, "%.3f", tailSeek)}%" else "0%"
        val lastV = runProcOutput("/usr/bin/ffprobe", "-v", "error", "-read_intervals", interval, "-select_streams", "v:0", "-show_entries", "packet=pts_time,dts_time", "-print_format", "json", path)
        val firstA = if (base.hasAudio) {
            runProcOutput("/usr/bin/ffprobe", "-v", "error", "-read_intervals", "0%+0.25", "-select_streams", "a:0", "-show_entries", "packet=pts_time,dts_time", "-print_format", "json", path)
        } else null
        return ExportMediaProbe.parseFfprobeJson(baseJson, firstV, lastV, firstA, path)
    }

    private fun probeNearestPacketPts(path: String, stream: String, targetSec: Double): Double? {
        val interval = String.format(Locale.US, "%.3f%%+2.5", maxOf(0.0, targetSec - 1.5))
        val out = runProcOutput(
            "/usr/bin/ffprobe", "-v", "error", "-read_intervals", interval,
            "-select_streams", stream, "-show_entries", "packet=pts_time",
            "-of", "default=noprint_wrappers=1:nokey=1", path
        )
        return out.lineSequence()
            .mapNotNull { it.trim().toDoubleOrNull() }
            .minByOrNull { abs(it - targetSec) }
    }

    private fun createTempDirCompat(prefix: String): File =
        kotlin.io.path.createTempDirectory(prefix).toFile()

    private fun tokenizeCommand(cmd: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inDouble = false
        var inSingle = false
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            when {
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '\'' && !inDouble -> inSingle = !inSingle
                c.isWhitespace() && !inDouble && !inSingle -> {
                    if (cur.isNotEmpty()) {
                        tokens.add(cur.toString())
                        cur.clear()
                    }
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())
        return tokens
    }

    private fun runProc(vararg args: String) {
        val pb = ProcessBuilder(*args).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        if (rc != 0) {
            throw AssertionError("Command failed (rc=$rc): ${args.joinToString(" ")}\n$out")
        }
    }

    private fun runProcOutput(vararg args: String): String {
        val pb = ProcessBuilder(*args).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        if (rc != 0) {
            throw AssertionError("Command failed (rc=$rc): ${args.joinToString(" ")}\n$out")
        }
        return out
    }
}

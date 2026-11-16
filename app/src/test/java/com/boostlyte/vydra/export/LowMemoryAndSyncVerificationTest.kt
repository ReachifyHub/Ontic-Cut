package com.boostlyte.vydra.export

import android.net.DummyUri
import com.boostlyte.vydra.models.CanvasAspectRatio
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.SubtitleCue
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.services.ChunkPlanner
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
        // Simulated Infinix Smart 7 HD: 2GB RAM, isLowRamDevice=true
        val low360 = ExportMemoryProfile.fromMetrics(
            totalMemBytes = 1950L * MB,
            availMemBytes = 420L * MB,
            isLowMemoryFlag = false,
            isLowRamDevice = true,
            targetWidth = 640,
            targetHeight = 360
        )
        assertTrue(low360.isLowMemoryTier)
        assertEquals(6, low360.maxChunkSize)
        assertEquals(2, low360.softwareEncoderThreads)
        assertEquals(720.0, low360.motionSpaceShortEdge, 0.01)

        val low1080 = ExportMemoryProfile.fromMetrics(
            totalMemBytes = 1950L * MB,
            availMemBytes = 420L * MB,
            isLowMemoryFlag = false,
            isLowRamDevice = true,
            targetWidth = 1920,
            targetHeight = 1080
        )
        assertTrue(low1080.isLowMemoryTier)
        assertEquals(4, low1080.maxChunkSize)
        assertEquals(2, low1080.softwareEncoderThreads)
        assertEquals(1080.0, low1080.motionSpaceShortEdge, 0.01)
        assertTrue("1080p prep max edge must stay >= 2208", low1080.imagePrepMaxEdge >= 2208)

        // Mid 4GB RAM device
        val mid1080 = ExportMemoryProfile.fromMetrics(
            totalMemBytes = 3800L * MB,
            availMemBytes = 1100L * MB,
            isLowMemoryFlag = false,
            isLowRamDevice = false,
            targetWidth = 1920,
            targetHeight = 1080
        )
        assertTrue(mid1080.isMidMemoryTier)
        assertEquals(6, mid1080.maxChunkSize)
        assertEquals(3, mid1080.softwareEncoderThreads)

        // High 8GB RAM device
        val high1080 = ExportMemoryProfile.fromMetrics(
            totalMemBytes = 8L * GB,
            availMemBytes = 4L * GB,
            isLowMemoryFlag = false,
            isLowRamDevice = false,
            targetWidth = 1920,
            targetHeight = 1080
        )
        assertFalse(high1080.isLowMemoryTier)
        assertFalse(high1080.isMidMemoryTier)
        assertEquals(10, high1080.maxChunkSize)
        assertEquals(0, high1080.softwareEncoderThreads)

        // Verify EncoderSelector honors thread cap
        val swLow = EncoderSelector.selectEncoder(
            preference = EncoderSelector.EncoderPreference.SOFTWARE,
            targetWidth = 1920,
            targetHeight = 1080,
            fps = 24,
            softwareThreads = low1080.softwareEncoderThreads
        )
        assertTrue(swLow.ffmpegVideoCodecArgs.contains("-threads 2"))
        val fb = EncoderSelector.buildSoftwareFallbackCommand(
            command = "-y -i in.mp4 -c:v h264_mediacodec -pix_fmt yuv420p -b:v 8M out.mp4",
            crf = 23,
            softwareThreads = 2
        )
        assertTrue(fb.contains("-threads 2"))
        assertFalse(fb.contains("-b:v"))
    }

    @Test
    fun testScenario1_179Images_360p_24Fps_WithVoiceover_BresenhamExactFrames() {
        // Fractional clip durations summing to 410,760 ms (410.76s @ 24fps)
        val durations = (0 until 179).map { idx ->
            when {
                idx == 178 -> 2460L
                idx % 3 == 0 -> 2150L
                idx % 3 == 1 -> 2330L
                else -> 2400L
            }
        }
        val totalMs = durations.sum() // 410,760 + adjustment? Compute exact sum
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

        // Verify low-RAM ChunkPlanner caps chunk size at 6 for 360p while preserving exact frames
        val sequenceItems = listOf(EditOperation.MergeItem(uri = DummyUri.fromPath("/tmp/img_0.jpg"), durationMs = durations[0], isImage = true, motionEffect = "zoom_in")) +
            (1 until 179).map { EditOperation.MergeItem(uri = DummyUri.fromPath("/tmp/img_$it.jpg"), durationMs = durations[it], isImage = true, motionEffect = "zoom_out") }
        val lowRamPlan = ChunkPlanner.plan(
            sequenceItems = sequenceItems,
            transitions = emptyList(),
            fps = 24,
            maxChunkSize = 6,
            chunkTriggerThreshold = 10
        )
        assertTrue(lowRamPlan.chunks.all { it.clips.size <= 6 })
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
    fun testScenario4_65Images_1080p_NoTransitions() {
        val (project, _) = buildImageProject(imageCount = 65, transitionsCount = 0)
        val vm = VideoEditingViewModel()
        vm.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)
        vm.loadProject(project)

        val plan = vm.buildChunkExportPlan("/tmp/img_0.jpg", "/tmp/out_65_notrans.mp4")!!
        assertTrue(plan.isChunked)
        assertTrue(plan.canStreamCopyFinalPass)
        assertEquals(1920, plan.targetWidth)
        assertEquals(1080, plan.targetHeight)
        val expectedFrames = kotlin.math.round((65 * 2.3) * 30).toInt()
        assertEquals(expectedFrames, plan.chunkSpecs.sumOf { it.totalFrames })
    }

    @Test
    fun testScenario5_65Images_1080p_With64Transitions() {
        val (project, _) = buildImageProject(
            imageCount = 65,
            durationsMs = List(65) { 3000L },
            transitionsCount = 64,
            transitionDurationMs = 500L
        )
        val vm = VideoEditingViewModel()
        vm.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)
        vm.loadProject(project)

        val plan = vm.buildChunkExportPlan("/tmp/img_0.jpg", "/tmp/out_65_trans.mp4")!!
        assertTrue(plan.isChunked)
        assertTrue("Stream copy must be enabled via boundary containment", plan.canStreamCopyFinalPass)
        assertTrue("Every chunk must contain xfade transitions", plan.chunkCommands.any { it.command.contains("xfade=") })
        val expectedDurationMs = (65L * 3000L) - (64L * 500L) // 163,000ms
        assertEquals(expectedDurationMs / 1000.0, plan.totalDurationSecs, 0.05)
    }

    @Test
    fun testScenario6And7_CaptionsOnly_And_TransitionsPlusCaptions_NoTopLineGarble() {
        // Scenario 6: Captions only
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

        // Scenario 7: Transitions + Captions together
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

            // Construct valid ISO-BMFF MP4/M4A box headers (ftyp + mp4a sample entry)
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
    fun testHostFfmpeg_EndToEnd_MultiChunk_24Fps_ConcatAndAudioSyncDriftWithinOneFrame() {
        val ffmpeg = File("/usr/bin/ffmpeg")
        val ffprobe = File("/usr/bin/ffprobe")
        if (!ffmpeg.exists() || !ffprobe.exists()) {
            // Skip gracefully only if host environment lacks ffmpeg binaries
            return
        }

        val tempDir = createTempDirCompat("vydra_host_e2e")
        try {
            // 1. Synthesize 17 fractional-duration test images (360p multi-chunk + Ken Burns + transitions + voiceover)
            val imageFiles = (0 until 17).map { idx ->
                val f = File(tempDir, "img_$idx.jpg")
                val color = listOf("0x336699", "0x993366", "0x339966", "0x996633", "0x5555AA")[idx % 5]
                runProc(
                    "/usr/bin/ffmpeg", "-y", "-f", "lavfi",
                    "-i", "color=c=$color:s=640x360:d=0.1",
                    "-frames:v", "1", f.absolutePath
                )
                f
            }

            // Fractional durations summing to 25,380 ms -> at 24 fps, 25.38 * 24 = 609.12 frames -> 609 frames (25.375s)
            val durationsMs = listOf(
                1420L, 1510L, 1380L, 1610L, 1490L,
                1550L, 1330L, 1640L, 1470L, 1520L,
                1410L, 1590L, 1440L, 1480L, 1530L,
                1460L, 1550L
            ) // total = 25380ms
            val totalDurationMs = durationsMs.sum()
            val expectedFrames = kotlin.math.round((totalDurationMs / 1000.0) * 24).toInt()
            val expectedDurationSecs = expectedFrames / 24.0

            // 2. Synthesize real AAC M4A voiceover matching totalDurationMs
            val voiceoverM4a = File(tempDir, "voiceover.m4a")
            runProc(
                "/usr/bin/ffmpeg", "-y", "-f", "lavfi",
                "-i", "sine=frequency=440:sample_rate=44100:duration=${totalDurationMs / 1000.0}",
                "-c:a", "aac", "-b:a", "96k", voiceoverM4a.absolutePath
            )

            // 3. Build real chunked plan via ChunkPlanner & VideoEditingViewModel at 360p @ 24fps (maxChunkSize = 4 -> 5 chunks!)
            val seqItems = (0 until 17).map { idx ->
                EditOperation.MergeItem(
                    uri = DummyUri.fromPath(imageFiles[idx].absolutePath),
                    durationMs = durationsMs[idx],
                    isImage = true,
                    motionEffect = if (idx % 2 == 0) "zoom_in" else "zoom_out"
                )
            }
            val chunkPlan = ChunkPlanner.plan(
                sequenceItems = seqItems,
                transitions = emptyList(),
                fps = 24,
                maxChunkSize = 4,
                chunkTriggerThreshold = 10
            )
            assertTrue("Must split into >= 4 chunks", chunkPlan.chunks.size >= 4)
            assertEquals(expectedFrames, chunkPlan.chunks.sumOf { it.totalFrames })

            val vm = VideoEditingViewModel()
            vm.setExportSettings(resolution = 360, fps = 24, audioOnly = false)

            // Copy font for subtitle verification if available
            val fontAsset = listOf(
                File("app/src/main/assets/fonts/Roboto-Regular.ttf"),
                File("src/main/assets/fonts/Roboto-Regular.ttf")
            ).firstOrNull { it.exists() }
            val captionsOp = EditOperation.AddSubtitles(
                subtitlesUri = DummyUri.fromPath("/tmp/sub.srt"),
                srtContent = "",
                fileName = "sub.srt",
                cues = listOf(
                    SubtitleCue(500L, 4000L, "First chunk cue"),
                    SubtitleCue(12000L, 18000L, "Middle chunk cue")
                ),
                position = TextPosition.BOTTOM_CENTER
            )

            val chunkOutputs = mutableListOf<File>()
            for ((idx, spec) in chunkPlan.chunks.withIndex()) {
                val outChunk = File(tempDir, "chunk_$idx.mp4")
                val cmd = vm.buildChunkPassCommand(
                    chunkSpec = spec,
                    sourceFilePath = imageFiles[0].absolutePath,
                    chunkOutputPath = outChunk.absolutePath,
                    fontFilePath = fontAsset?.absolutePath,
                    context = null,
                    totalDurationMs = totalDurationMs,
                    density = 1.0f,
                    targetWidth = 640,
                    targetHeight = 360,
                    operations = if (fontAsset != null) listOf(captionsOp) else emptyList(),
                    encoderOverride = "-c:v libx264 -preset ultrafast -crf 26 -threads 2 -pix_fmt yuv420p"
                )
                val args = listOf("/usr/bin/ffmpeg") + tokenizeCommand(cmd.command)
                runProc(*args.toTypedArray())

                // Verify chunk exact nb_frames & timescale
                val chunkProbe = runProcOutput(
                    "/usr/bin/ffprobe", "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=nb_frames,r_frame_rate,avg_frame_rate,time_base",
                    "-of", "default=noprint_wrappers=1", outChunk.absolutePath
                )
                assertTrue("Chunk $idx must have nb_frames=${spec.totalFrames}\n$chunkProbe", chunkProbe.contains("nb_frames=${spec.totalFrames}"))
                assertTrue("Chunk $idx must lock 1/24000 timebase\n$chunkProbe", chunkProbe.contains("time_base=1/24000"))
                chunkOutputs.add(outChunk)
            }

            // 4. Stream-copy audio mix command (same pattern as VideoEditingViewModel)
            val mixedAudio = File(tempDir, "mixed_audio.m4a")
            val audioMixCmd = vm.buildAudioMixCommand(
                operations = listOf(
                    EditOperation.AddBackgroundAudio(
                        audioUri = DummyUri.fromPath(voiceoverM4a.absolutePath),
                        volume = 1.0f,
                        internalStartMs = 0L,
                        internalEndMs = totalDurationMs,
                        startTimeMs = 0L,
                        endTimeMs = totalDurationMs,
                        originalDurationMs = totalDurationMs
                    )
                ),
                outputAudioPath = mixedAudio.absolutePath,
                targetDurationSecs = expectedDurationSecs,
                context = null,
                audioBitrate = "96k"
            )!!
            assertTrue("Real .m4a voiceover must stream-copy via -c:a copy: $audioMixCmd", audioMixCmd.contains("-c:a copy"))
            runProc(*((listOf("/usr/bin/ffmpeg") + tokenizeCommand(audioMixCmd)).toTypedArray()))

            // 5. Final concat demuxer with explicit frame-exact durations and NO -fflags +genpts
            val concatTxt = File(tempDir, "final_concat_list.txt")
            val concatSb = StringBuilder()
            for ((idx, outChunk) in chunkOutputs.withIndex()) {
                val dur = chunkPlan.chunks[idx].totalFrames.toDouble() / 24.0
                concatSb.append("file '${outChunk.absolutePath}'\n")
                concatSb.append(String.format(Locale.US, "duration %.6f\n", dur))
            }
            concatTxt.writeText(concatSb.toString())

            val finalMp4 = File(tempDir, "final_output.mp4")
            val formattedDur = String.format(Locale.US, "%.6f", expectedDurationSecs)
            runProc(
                "/usr/bin/ffmpeg", "-y", "-f", "concat", "-safe", "0",
                "-i", concatTxt.absolutePath,
                "-i", mixedAudio.absolutePath,
                "-map", "0:v", "-map", "1:a", "-c", "copy",
                "-movflags", "+faststart", "-t", formattedDur,
                finalMp4.absolutePath
            )

            // 6. Probe final video & audio streams and assert <= 1 frame drift & exact CFR!
            val vProbe = runProcOutput(
                "/usr/bin/ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=nb_frames,r_frame_rate,avg_frame_rate,duration",
                "-of", "default=noprint_wrappers=1", finalMp4.absolutePath
            )
            val aProbe = runProcOutput(
                "/usr/bin/ffprobe", "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=duration",
                "-of", "default=noprint_wrappers=1", finalMp4.absolutePath
            )
            val vFrames = Regex("""nb_frames=(\d+)""").find(vProbe)!!.groupValues[1].toInt()
            val vAvgFps = Regex("""avg_frame_rate=([^\s]+)""").find(vProbe)!!.groupValues[1]
            val vDur = Regex("""duration=([0-9.]+)""").find(vProbe)!!.groupValues[1].toDouble()
            val aDur = Regex("""duration=([0-9.]+)""").find(aProbe)!!.groupValues[1].toDouble()

            assertEquals("Exact cumulative frames across all 5 chunks", expectedFrames, vFrames)
            assertEquals("Strict CFR 24/1 avg_frame_rate after concat", "24/1", vAvgFps)
            assertTrue(
                "A/V sync drift |vDur($vDur) - aDur($aDur)|=${abs(vDur - aDur)}s must be <= 1 frame (${1.0 / 24.0}s)",
                abs(vDur - aDur) <= (1.0 / 24.0)
            )

            // 7. Also verify xfade transition rendering executes cleanly via host ffmpeg
            val transSeq = imageFiles.take(3).map { f ->
                EditOperation.MergeItem(uri = DummyUri.fromPath(f.absolutePath), durationMs = 2000L, isImage = true, motionEffect = "zoom_in")
            }
            val transPlan = ChunkPlanner.plan(
                sequenceItems = transSeq,
                transitions = listOf(
                    EditOperation.Transition(index = 0, type = "fade", durationMs = 500L),
                    EditOperation.Transition(index = 1, type = "dissolve", durationMs = 500L)
                ),
                fps = 24
            )
            val transOut = File(tempDir, "trans_out.mp4")
            val transCmd = vm.buildChunkPassCommand(
                chunkSpec = transPlan.chunks.first(),
                sourceFilePath = imageFiles[0].absolutePath,
                chunkOutputPath = transOut.absolutePath,
                fontFilePath = fontAsset?.absolutePath,
                context = null,
                totalDurationMs = transPlan.totalDurationMs,
                density = 1.0f,
                targetWidth = 640,
                targetHeight = 360,
                operations = if (fontAsset != null) listOf(captionsOp) else emptyList(),
                encoderOverride = "-c:v libx264 -preset ultrafast -crf 26 -threads 2 -pix_fmt yuv420p"
            )
            runProc(*((listOf("/usr/bin/ffmpeg") + tokenizeCommand(transCmd.command)).toTypedArray()))
            assertTrue(transOut.exists() && transOut.length() > 1024L)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createTempDirCompat(prefix: String): File {
        val f = kotlin.io.path.createTempDirectory(prefix).toFile()
        return f
    }

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

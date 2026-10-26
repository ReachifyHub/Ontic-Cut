package com.boostlyte.vydra

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.net.DummyUri
import android.net.Uri
import android.util.DisplayMetrics
import com.boostlyte.vydra.autoassemble.TimelineAssembler
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.EditRecipe
import com.boostlyte.vydra.models.SceneBoundary
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.transcription.VoiceoverTranscriptionPipeline
import com.boostlyte.vydra.utils.ErrorCode
import com.boostlyte.vydra.utils.ImagePreviewCache
import com.boostlyte.vydra.utils.ProjectSerializer
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class Phase7HandoffAndStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        ProjectSerializer.uriDeserializerFallback = { str -> DummyUri.fromPath(str) }
    }

    /**
     * Task 1: Reopen verification for an assembled project.
     * Verifies round-trip persistence of:
     * - Image clips with exact order and durations
     * - Ken Burns motion parameters (zoom_in, zoom_out, pan_left, pan_right)
     * - Transitions
     * - Voiceover audio track in sync
     * - Auto-caption overlays in sync
     */
    @Test
    fun testProjectReopen_RoundTripPreservesAllElements() {
        val baseUri = DummyUri.fromPath("file:///storage/emulated/0/DCIM/clip_0.jpg")
        val voiceoverUri = DummyUri.fromPath("file:///storage/emulated/0/Music/voiceover.m4a")

        val operations = listOf(
            EditOperation.Trim(0L, 4000L),
            EditOperation.MotionEffect(0, "zoom_in"),
            EditOperation.Merge(
                items = listOf(
                    EditOperation.MergeItem(
                        uri = DummyUri.fromPath("file:///storage/emulated/0/DCIM/clip_1.jpg"),
                        durationMs = 3500L,
                        trimStartMs = 0L,
                        trimEndMs = 3500L,
                        isImage = true,
                        motionEffect = "zoom_out"
                    ),
                    EditOperation.MergeItem(
                        uri = DummyUri.fromPath("file:///storage/emulated/0/DCIM/clip_2.jpg"),
                        durationMs = 4500L,
                        trimStartMs = 0L,
                        trimEndMs = 4500L,
                        isImage = true,
                        motionEffect = "pan_left"
                    ),
                    EditOperation.MergeItem(
                        uri = DummyUri.fromPath("file:///storage/emulated/0/DCIM/clip_3.jpg"),
                        durationMs = 5000L,
                        trimStartMs = 0L,
                        trimEndMs = 5000L,
                        isImage = true,
                        motionEffect = "pan_right"
                    )
                )
            ),
            EditOperation.MotionEffect(1, "zoom_out"),
            EditOperation.MotionEffect(2, "pan_left"),
            EditOperation.MotionEffect(3, "pan_right"),
            EditOperation.Transition(0, "fade", 500L),
            EditOperation.Transition(1, "slide_left", 600L),
            EditOperation.Transition(2, "wipe_right", 500L),
            EditOperation.AddBackgroundAudio(
                audioUri = voiceoverUri,
                originalDurationMs = 17000L,
                internalStartMs = 0L,
                internalEndMs = 17000L,
                startTimeMs = 0L,
                endTimeMs = 17000L,
                volume = 1.0f
            ),
            EditOperation.AddText(
                text = "Welcome to the Auto Assembled Video",
                fontSize = 24,
                position = TextPosition.BOTTOM_CENTER,
                startTimeMs = 0L,
                endTimeMs = 4000L,
                groupId = "auto-captions"
            ),
            EditOperation.AddText(
                text = "Exploring the next segment in detail",
                fontSize = 24,
                position = TextPosition.BOTTOM_CENTER,
                startTimeMs = 4000L,
                endTimeMs = 7500L,
                groupId = "auto-captions"
            ),
            EditOperation.AddText(
                text = "Final thoughts and conclusions",
                fontSize = 24,
                position = TextPosition.BOTTOM_CENTER,
                startTimeMs = 7500L,
                endTimeMs = 17000L,
                groupId = "auto-captions"
            )
        )

        val originalProject = VideoProject(
            sourceUri = baseUri,
            sourceName = "clip_0.jpg",
            operations = operations
        )

        val recipe = EditRecipe.fromVideoProject(
            projectName = "Full Assembled Test",
            project = originalProject,
            durationMs = 17000L,
            clipCount = 4
        )

        // 1. Serialize to JSON file (simulate save)
        val projectFile = File(tempFolder.root, "Full_Assembled.vdprj")
        val json = ProjectSerializer.serialize(recipe)
        assertNotNull(json)
        projectFile.writeText(json)

        // 2. Read from JSON file (simulate app kill and reopen)
        val loadedJson = projectFile.readText()
        val restoredRecipe = ProjectSerializer.deserialize(loadedJson)
        assertNotNull(restoredRecipe)

        val restoredProject = restoredRecipe!!.toVideoProject()
        assertNotNull(restoredProject)

        // 3. Verify all elements are strictly preserved
        // Base URI
        assertEquals(baseUri.toString(), restoredProject.sourceUri.toString())

        // Merge Items (Clips 1 to 3)
        val restoredMerge = restoredProject.operations.filterIsInstance<EditOperation.Merge>().firstOrNull()
        assertNotNull(restoredMerge)
        assertEquals(3, restoredMerge!!.items.size)
        assertEquals(3500L, restoredMerge.items[0].trimmedDurationMs)
        assertEquals("zoom_out", restoredMerge.items[0].motionEffect)
        assertEquals(4500L, restoredMerge.items[1].trimmedDurationMs)
        assertEquals("pan_left", restoredMerge.items[1].motionEffect)
        assertEquals(5000L, restoredMerge.items[2].trimmedDurationMs)
        assertEquals("pan_right", restoredMerge.items[2].motionEffect)

        // Motion Effects
        val motionOps = restoredProject.operations.filterIsInstance<EditOperation.MotionEffect>()
        assertEquals(4, motionOps.size)
        assertEquals("zoom_in", motionOps.find { it.index == 0 }?.motionType)
        assertEquals("zoom_out", motionOps.find { it.index == 1 }?.motionType)
        assertEquals("pan_left", motionOps.find { it.index == 2 }?.motionType)
        assertEquals("pan_right", motionOps.find { it.index == 3 }?.motionType)

        // Transitions
        val transitions = restoredProject.operations.filterIsInstance<EditOperation.Transition>()
        assertEquals(3, transitions.size)
        assertEquals("fade", transitions.find { it.index == 0 }?.type)
        assertEquals("slide_left", transitions.find { it.index == 1 }?.type)
        assertEquals("wipe_right", transitions.find { it.index == 2 }?.type)

        // Voiceover Audio
        val audioOps = restoredProject.operations.filterIsInstance<EditOperation.AddBackgroundAudio>()
        assertEquals(1, audioOps.size)
        assertEquals(voiceoverUri.toString(), audioOps[0].audioUri.toString())
        assertEquals(0L, audioOps[0].startTimeMs)
        assertEquals(17000L, audioOps[0].endTimeMs)
        assertEquals(17000L, audioOps[0].internalEndMs)

        // Captions
        val captions = restoredProject.operations.filterIsInstance<EditOperation.AddText>()
        assertEquals(3, captions.size)
        assertEquals("Welcome to the Auto Assembled Video", captions[0].text)
        assertEquals(0L, captions[0].startTimeMs)
        assertEquals(4000L, captions[0].endTimeMs)
        assertEquals("Exploring the next segment in detail", captions[1].text)
        assertEquals(4000L, captions[1].startTimeMs)
        assertEquals(7500L, captions[1].endTimeMs)
        assertEquals("Final thoughts and conclusions", captions[2].text)
        assertEquals(7500L, captions[2].startTimeMs)
        assertEquals(17000L, captions[2].endTimeMs)
    }

    /**
     * Task 1 & Stress Benchmarks:
     * Measures real execution times and serialized sizes for 10, 50, 100, 300, and 500 images.
     */
    @Test
    fun testStressBenchmarks_10_50_100_300_500_Clips() {
        val testScales = listOf(10, 50, 100, 300, 500)
        val benchmarkResults = mutableMapOf<Int, String>()

        for (clipCount in testScales) {
            val audioDurationMs = clipCount * 2500L // 2.5s per image on average
            val sceneCount = maxOf(2, clipCount / 5)
            val sceneDurationMs = audioDurationMs / sceneCount

            val scenes = (1..sceneCount).map {
                val start = (it - 1) * sceneDurationMs
                val end = if (it == sceneCount) audioDurationMs else it * sceneDurationMs
                SceneBoundary(
                    sceneIndex = it,
                    sceneTitle = "Scene $it",
                    startMs = start,
                    endMs = end,
                    isMatched = true
                )
            }

            val imageUris = (1..clipCount).map {
                DummyUri.fromPath("file:///storage/emulated/0/DCIM/photo_$it.jpg")
            }

            // 1. Measure Assembly calculation time
            val assembleStart = System.nanoTime()
            val plan = TimelineAssembler.computeAssemblyPlan(
                scenes = scenes,
                imageUris = imageUris,
                audioDurationMs = audioDurationMs
            )
            val assembleMs = (System.nanoTime() - assembleStart) / 1_000_000.0

            assertEquals(clipCount, plan.clips.size)
            assertEquals(audioDurationMs, plan.totalClipsDurationMs)

            // 2. Build full VideoProject
            val baseClip = plan.clips[0]
            val mergeItems = plan.clips.drop(1).map { clip ->
                EditOperation.MergeItem(
                    uri = clip.uri,
                    durationMs = clip.durationMs,
                    trimStartMs = 0L,
                    trimEndMs = clip.durationMs,
                    isImage = true,
                    motionEffect = clip.motionEffect
                )
            }
            val operations = mutableListOf<EditOperation>()
            operations.add(EditOperation.Trim(0L, baseClip.durationMs))
            operations.add(EditOperation.MotionEffect(0, baseClip.motionEffect))
            operations.add(EditOperation.Merge(items = mergeItems))
            for ((idx, clip) in plan.clips.withIndex()) {
                operations.add(EditOperation.MotionEffect(idx, clip.motionEffect))
            }
            // Add captions for each scene
            for (scene in scenes) {
                operations.add(
                    EditOperation.AddText(
                        text = "Caption for ${scene.sceneTitle}",
                        fontSize = 22,
                        position = TextPosition.BOTTOM_CENTER,
                        startTimeMs = scene.startMs,
                        endTimeMs = scene.endMs,
                        groupId = "auto-captions"
                    )
                )
            }

            val project = VideoProject(
                sourceUri = baseClip.uri,
                sourceName = "photo_1.jpg",
                operations = operations
            )

            // 3. Measure Project Serialization time & payload size
            val recipe = EditRecipe.fromVideoProject(
                projectName = "Stress Project $clipCount",
                project = project,
                durationMs = audioDurationMs,
                clipCount = clipCount
            )

            val serializeStart = System.nanoTime()
            val serializedJson = ProjectSerializer.serialize(recipe)
            val serializeMs = (System.nanoTime() - serializeStart) / 1_000_000.0

            val jsonSizeBytes = serializedJson.toByteArray(Charsets.UTF_8).size

            // 4. Measure Deserialization time
            val deserializeStart = System.nanoTime()
            val deserializedRecipe = ProjectSerializer.deserialize(serializedJson)
            val deserializeMs = (System.nanoTime() - deserializeStart) / 1_000_000.0

            assertNotNull(deserializedRecipe)
            val restoredProject = deserializedRecipe!!.toVideoProject()
            assertEquals(clipCount - 1, (restoredProject.operations.find { it is EditOperation.Merge } as EditOperation.Merge).items.size)

            val summary = String.format(
                "Clips: %3d | Assembly: %.2f ms | Serialize: %.2f ms | Deserialize: %.2f ms | JSON: %.1f KB",
                clipCount, assembleMs, serializeMs, deserializeMs, jsonSizeBytes / 1024.0
            )
            benchmarkResults[clipCount] = summary
            println("[STRESS BENCHMARK] $summary")

            // Strict sanity assertions
            assertTrue("Assembly must complete within 200ms", assembleMs < 200.0)
            assertTrue("Serialization must complete within 500ms", serializeMs < 500.0)
            assertTrue("Deserialization must complete within 500ms", deserializeMs < 500.0)
        }

        assertEquals(5, benchmarkResults.size)
    }

    /**
     * Task 2: Missing media and lost permission detection.
     * Verifies validateProjectMedia correctly identifies accessible vs missing media.
     */
    @Test
    fun testMediaAccessibilityValidation_DetectsMissingAndRevokedUris() {
        // Create 3 existing files on disk
        val file1 = File(tempFolder.root, "existing_1.jpg").apply { writeText("data1") }
        val file2 = File(tempFolder.root, "existing_2.jpg").apply { writeText("data2") }
        val fileAudio = File(tempFolder.root, "existing_audio.m4a").apply { writeText("audiodata") }

        val uri1 = DummyUri.fromPath(file1.absolutePath)
        val uri2 = DummyUri.fromPath(file2.absolutePath)
        val uriAudio = DummyUri.fromPath(fileAudio.absolutePath)

        // 2 missing files (non-existent paths)
        val missingUri1 = DummyUri.fromPath(File(tempFolder.root, "missing_photo.jpg").absolutePath)
        val missingUri2 = DummyUri.fromPath(File(tempFolder.root, "deleted_photo.jpg").absolutePath)

        val project = VideoProject(
            sourceUri = uri1,
            sourceName = "existing_1.jpg",
            operations = listOf(
                EditOperation.Merge(
                    items = listOf(
                        EditOperation.MergeItem(uri = uri2, durationMs = 3000L, trimStartMs = 0L, trimEndMs = 3000L, isImage = true),
                        EditOperation.MergeItem(uri = missingUri1, durationMs = 3000L, trimStartMs = 0L, trimEndMs = 3000L, isImage = true),
                        EditOperation.MergeItem(uri = missingUri2, durationMs = 3000L, trimStartMs = 0L, trimEndMs = 3000L, isImage = true)
                    )
                ),
                EditOperation.AddBackgroundAudio(
                    audioUri = uriAudio,
                    originalDurationMs = 10000L,
                    internalStartMs = 0L,
                    internalEndMs = 10000L,
                    startTimeMs = 0L,
                    endTimeMs = 10000L
                )
            )
        )

        // Accessibility check without throwing or crashing
        val report = ImagePreviewCache.validateProjectMedia(createMockContext(), project)

        assertEquals(5, report.totalChecked)
        assertEquals(2, report.missingUris.size)
        assertTrue(report.missingUris.contains(missingUri1))
        assertTrue(report.missingUris.contains(missingUri2))
        assertFalse(report.missingUris.contains(uri1))
        assertFalse(report.missingUris.contains(uri2))
        assertFalse(report.missingUris.contains(uriAudio))
        assertFalse(report.allValid)
    }

    /**
     * Task 2: Transcription error classification into clear, actionable categories with retry.
     */
    @Test
    fun testTranscriptionErrorClassification_AllCategories() {
        // 1. No internet
        val (code1, msg1) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(UnknownHostException("Unable to resolve host"))
        assertEquals(ErrorCode.TRANSCRIPTION_NETWORK_ERROR, code1)
        assertTrue(msg1.contains("No Internet Connection", ignoreCase = true))

        val (code1b, msg1b) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(ConnectException("Connection refused"))
        assertEquals(ErrorCode.TRANSCRIPTION_NETWORK_ERROR, code1b)
        assertTrue(msg1b.contains("No Internet Connection", ignoreCase = true))

        // 2. Timeout
        val (code2, msg2) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(SocketTimeoutException("Read timed out"))
        assertEquals(ErrorCode.TRANSCRIPTION_NETWORK_ERROR, code2)
        assertTrue(msg2.contains("Timed Out", ignoreCase = true))

        // 3. Server Error (500, 502, 503)
        val (code3, msg3) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(Exception("Internal Server Error"), statusCode = 500)
        assertEquals(ErrorCode.TRANSCRIPTION_API_FAILED, code3)
        assertTrue(msg3.contains("Server Error", ignoreCase = true))
        assertTrue(msg3.contains("500"))

        // 4. Authentication Failure (401, 403)
        val (code4, msg4) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(Exception("Unauthorized"), statusCode = 401)
        assertEquals(ErrorCode.TRANSCRIPTION_AUTH_MISSING, code4)
        assertTrue(msg4.contains("Authentication Error", ignoreCase = true))

        // 5. Credit Limit (402 or message with 'credit')
        val (code5, msg5) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(Exception("Payment required"), statusCode = 402)
        assertEquals(ErrorCode.TRANSCRIPTION_API_FAILED, code5)
        assertTrue(msg5.contains("Insufficient Credits", ignoreCase = true))

        val (code5b, msg5b) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(Exception("User has exceeded allowed credits"))
        assertEquals(ErrorCode.TRANSCRIPTION_API_FAILED, code5b)
        assertTrue(msg5b.contains("Insufficient Credits", ignoreCase = true))

        // 6. Audio Too Long (400)
        val (code6, msg6) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(Exception("Audio payload too large"), statusCode = 400)
        assertEquals(ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG, code6)
        assertTrue(msg6.contains("Audio Too Long", ignoreCase = true))
    }

    /**
     * Task 1 & Export: Verify that large timelines (>10 clips or long filter strings)
     * generate a `-filter_complex_script` file to avoid command line limits.
     */
    @Test
    fun testExportFilterComplexScriptFileGeneratedForLargeTimeline() {
        val mockContext = createMockContext()
        val viewModel = VideoEditingViewModel()

        val sourceFile = File(tempFolder.root, "source.mp4").apply { writeText("dummy") }
        val outputFile = File(tempFolder.root, "output.mp4")

        // 15 merge items to exceed threshold (mergeOp.items.size >= 10)
        val mergeItems = (1..15).map {
            val f = File(tempFolder.root, "merge_$it.jpg").apply { writeText("img") }
            EditOperation.MergeItem(
                uri = DummyUri.fromPath(f.absolutePath),
                durationMs = 2000L,
                trimStartMs = 0L,
                trimEndMs = 2000L,
                isImage = true,
                motionEffect = "zoom_in"
            )
        }

        val project = VideoProject(
            sourceUri = DummyUri.fromPath(sourceFile.absolutePath),
            sourceName = "source.mp4",
            operations = listOf(
                EditOperation.Trim(0L, 2000L),
                EditOperation.Merge(items = mergeItems)
            )
        )

        viewModel.loadProject(project)

        val cmd = viewModel.buildConsolidatedFFmpegCommand(
            sourceFilePath = sourceFile.absolutePath,
            outputFilePath = outputFile.absolutePath,
            context = mockContext
        )

        assertNotNull(cmd)
        // Must use -filter_complex_script instead of inline -filter_complex
        assertTrue("Command should use -filter_complex_script for large timeline: $cmd",
            cmd!!.contains("-filter_complex_script"))
        assertFalse("Command should not inline huge -filter_complex: $cmd",
            cmd.contains("-filter_complex \""))

        // Verify that the script file was written to disk and contains filter instructions
        val scriptFiles = mockContext.cacheDir.listFiles { _, name -> name.startsWith("filter_script_") }
        assertNotNull(scriptFiles)
        assertTrue("At least one filter script file should exist", scriptFiles!!.isNotEmpty())
        val scriptContent = scriptFiles[0].readText()
        assertTrue("Script content should contain scale/zoom/concat",
            scriptContent.contains("scale") || scriptContent.contains("concat"))
    }

    private fun createMockContext(): Context {
        val cache = tempFolder.newFolder("mock_cache")
        return object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): File = cache
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.boostlyte.vydra"
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo()
            override fun getResources(): Resources? = null
            override fun getContentResolver(): android.content.ContentResolver? = null
        }
    }
}

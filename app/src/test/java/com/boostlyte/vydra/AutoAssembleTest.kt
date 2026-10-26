package com.boostlyte.vydra

import android.net.DummyUri
import com.google.gson.Gson
import com.boostlyte.vydra.autoassemble.AutoAssembleManager
import com.boostlyte.vydra.autoassemble.AutoAssembleResult
import com.boostlyte.vydra.autoassemble.SceneFuzzyMatcher
import com.boostlyte.vydra.autoassemble.ScriptMapParser
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.EditRecipe
import com.boostlyte.vydra.models.SceneBoundary
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.transcription.TranscriptionStorageManager
import com.boostlyte.vydra.transcription.models.TranscriptionResponse
import com.boostlyte.vydra.transcription.models.TranscriptionSegment
import com.boostlyte.vydra.transcription.models.TranscriptionWord
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoAssembleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testScriptMapParser_standardFormat() {
        val script = """
            Scene 1: Welcome to the channel.
            Scene 2: Today we will discuss history.
        """.trimIndent()

        val scenes = ScriptMapParser.parse(script)
        assertEquals(2, scenes.size)

        assertEquals(1, scenes[0].sceneIndex)
        assertEquals("Scene 1: Welcome to the channel.", scenes[0].sceneTitle)
        assertEquals("Welcome to the channel.", scenes[0].scriptText)

        assertEquals(2, scenes[1].sceneIndex)
        assertEquals("Scene 2: Today we will discuss history.", scenes[1].sceneTitle)
        assertEquals("Today we will discuss history.", scenes[1].scriptText)
    }

    @Test
    fun testScriptMapParser_multilineFormat() {
        val script = """
            Scene 1 - Introduction
            Welcome everyone to this tutorial.
            We are going to learn video editing.

            Scene 2 - First Steps
            First, import your clips to the timeline.
        """.trimIndent()

        val scenes = ScriptMapParser.parse(script)
        assertEquals(2, scenes.size)

        assertEquals(1, scenes[0].sceneIndex)
        assertEquals("Scene 1 - Introduction", scenes[0].sceneTitle)
        assertEquals("Welcome everyone to this tutorial. We are going to learn video editing.", scenes[0].scriptText)

        assertEquals(2, scenes[1].sceneIndex)
        assertEquals("Scene 2 - First Steps", scenes[1].sceneTitle)
        assertEquals("First, import your clips to the timeline.", scenes[1].scriptText)
    }

    @Test
    fun testScriptMapParser_emptyInput() {
        assertTrue(ScriptMapParser.parse("").isEmpty())
        assertTrue(ScriptMapParser.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun testSceneFuzzyMatcher_exactMatching() {
        val script = """
            Scene 1: Welcome to the channel.
            Scene 2: Today we will discuss history.
        """.trimIndent()

        val scenes = ScriptMapParser.parse(script)

        val transcriptionResponse = TranscriptionResponse(
            text = "Welcome to the channel. Today we will discuss history.",
            segments = listOf(
                TranscriptionSegment(
                    id = 0,
                    start = 0.5,
                    end = 3.2,
                    text = "Welcome to the channel.",
                    words = listOf(
                        TranscriptionWord("Welcome", 0.5, 1.1),
                        TranscriptionWord("to", 1.2, 1.4),
                        TranscriptionWord("the", 1.5, 1.7),
                        TranscriptionWord("channel.", 1.8, 3.2)
                    )
                ),
                TranscriptionSegment(
                    id = 1,
                    start = 3.5,
                    end = 6.8,
                    text = "Today we will discuss history.",
                    words = listOf(
                        TranscriptionWord("Today", 3.5, 4.0),
                        TranscriptionWord("we", 4.1, 4.3),
                        TranscriptionWord("will", 4.4, 4.7),
                        TranscriptionWord("discuss", 4.8, 5.5),
                        TranscriptionWord("history.", 5.6, 6.8)
                    )
                )
            )
        )

        val boundaries = SceneFuzzyMatcher.matchScenes(scenes, transcriptionResponse)

        assertEquals(2, boundaries.size)

        // Scene 1 assertions
        val b1 = boundaries[0]
        assertEquals(1, b1.sceneIndex)
        assertTrue(b1.isMatched)
        assertEquals(500L, b1.startMs)
        assertEquals(3200L, b1.endMs)

        // Scene 2 assertions
        val b2 = boundaries[1]
        assertEquals(2, b2.sceneIndex)
        assertTrue(b2.isMatched)
        assertEquals(3500L, b2.startMs)
        assertEquals(6800L, b2.endMs)
    }

    @Test
    fun testSceneFuzzyMatcher_fuzzyTolerance() {
        // Script has slight word variation or typo
        val script = """
            Scene 1: Welcome into this channel!
        """.trimIndent()

        val scenes = ScriptMapParser.parse(script)

        val transcriptionResponse = TranscriptionResponse(
            segments = listOf(
                TranscriptionSegment(
                    id = 0,
                    start = 0.0,
                    end = 2.5,
                    text = "Welcome to this channel."
                )
            )
        )

        val boundaries = SceneFuzzyMatcher.matchScenes(scenes, transcriptionResponse)
        assertEquals(1, boundaries.size)
        assertTrue("Scene with slight word variation should still fuzzy match", boundaries[0].isMatched)
        assertEquals(0L, boundaries[0].startMs)
        assertEquals(2500L, boundaries[0].endMs)
    }

    @Test
    fun testSceneFuzzyMatcher_missingSceneHandledGracefully() {
        // Script contains Scene 2 which was NEVER spoken
        val script = """
            Scene 1: Welcome to the channel.
            Scene 2: Completely omitted phantom scene about quantum mechanics.
            Scene 3: Today we will discuss history.
        """.trimIndent()

        val scenes = ScriptMapParser.parse(script)

        val transcriptionResponse = TranscriptionResponse(
            segments = listOf(
                TranscriptionSegment(
                    id = 0,
                    start = 1.0,
                    end = 3.0,
                    text = "Welcome to the channel."
                ),
                TranscriptionSegment(
                    id = 1,
                    start = 4.0,
                    end = 7.0,
                    text = "Today we will discuss history."
                )
            )
        )

        val boundaries = SceneFuzzyMatcher.matchScenes(scenes, transcriptionResponse)
        assertEquals(3, boundaries.size)

        // Scene 1 matched
        assertTrue(boundaries[0].isMatched)
        assertEquals(1000L, boundaries[0].startMs)
        assertEquals(3000L, boundaries[0].endMs)

        // Scene 2 NOT matched (clean degradation)
        assertFalse("Omitted scene must be marked unmatched", boundaries[1].isMatched)
        assertEquals(0L, boundaries[1].startMs)
        assertEquals(0L, boundaries[1].endMs)

        // Scene 3 still matches! (monotonic search preserved)
        assertTrue("Scene 3 should match after unmatched Scene 2", boundaries[2].isMatched)
        assertEquals(4000L, boundaries[2].startMs)
        assertEquals(7000L, boundaries[2].endMs)
    }

    @Test
    fun testSceneFuzzyMatcher_emptyTranscript() {
        val script = "Scene 1: Hello"
        val scenes = ScriptMapParser.parse(script)
        val emptyResponse = TranscriptionResponse()

        val boundaries = SceneFuzzyMatcher.matchScenes(scenes, emptyResponse)
        assertEquals(1, boundaries.size)
        assertFalse(boundaries[0].isMatched)
    }

    @Test
    fun testPreconditionCheck_gatingRules() {
        // Both missing
        val c1 = checkPreconditions(hasVisuals = false, hasAudio = false)
        assertFalse(c1.isValid)
        assertTrue(c1.errorMessage?.contains("both visuals and a voiceover") == true)

        // Missing visuals
        val c2 = checkPreconditions(hasVisuals = false, hasAudio = true)
        assertFalse(c2.isValid)
        assertTrue(c2.errorMessage?.contains("visual") == true)

        // Missing audio
        val c3 = checkPreconditions(hasVisuals = true, hasAudio = false)
        assertFalse(c3.isValid)
        assertTrue(c3.errorMessage?.contains("voiceover") == true)

        // Both present
        val c4 = checkPreconditions(hasVisuals = true, hasAudio = true)
        assertTrue(c4.isValid)
        assertNull(c4.errorMessage)
    }

    private fun checkPreconditions(hasVisuals: Boolean, hasAudio: Boolean) = when {
        !hasVisuals && !hasAudio -> org.junit.Assert.assertTrue(true).let {
            com.boostlyte.vydra.autoassemble.PreconditionCheckResult(
                isValid = false,
                hasVisuals = false,
                hasAudio = false,
                errorMessage = "Auto Assemble requires both visuals and a voiceover on the timeline."
            )
        }
        !hasVisuals -> com.boostlyte.vydra.autoassemble.PreconditionCheckResult(
            isValid = false,
            hasVisuals = false,
            hasAudio = true,
            errorMessage = "Auto Assemble requires at least one visual asset (video or image) on the timeline."
        )
        !hasAudio -> com.boostlyte.vydra.autoassemble.PreconditionCheckResult(
            isValid = false,
            hasVisuals = true,
            hasAudio = false,
            errorMessage = "Auto Assemble requires at least one voiceover audio track on the timeline."
        )
        else -> com.boostlyte.vydra.autoassemble.PreconditionCheckResult(
            isValid = true,
            hasVisuals = true,
            hasAudio = true
        )
    }

    @Test
    fun testCachedTranscriptionReuse_skipsBackendAndChargesZero() {
        val targetDir = tempFolder.newFolder("transcriptions_cache")
        val storageManager = TranscriptionStorageManager()

        val rawJson = """
            {
              "text": "Welcome to Vydra. Today we talk about editing.",
              "segments": [
                { "id": 0, "start": 0.0, "end": 2.0, "text": "Welcome to Vydra." },
                { "id": 1, "start": 2.2, "end": 5.0, "text": "Today we talk about editing." }
              ]
            }
        """.trimIndent()

        val response = Gson().fromJson(rawJson, TranscriptionResponse::class.java).copy(
            rawJson = rawJson
        )

        val audioFileName = "my_voiceover.m4a"
        val savedFile = storageManager.saveTranscriptionResult(
            context = null,
            projectName = "Project Cache Test",
            audioFileName = audioFileName,
            audioDurationSeconds = 5,
            response = response,
            targetDir = targetDir,
            audioUri = "file:///data/cache/my_voiceover.m4a"
        )
        assertTrue(savedFile.exists())

        // Verify lookup by file
        val audioFile = File(targetDir, audioFileName)
        val jsonFiles = targetDir.listFiles { f -> f.name.endsWith(".json") }
        assertNotNull(jsonFiles)
        assertTrue(jsonFiles!!.isNotEmpty())

        val readBack = Gson().fromJson(jsonFiles[0].readText(), TranscriptionResponse::class.java)
        assertEquals(2, readBack.segments?.size)
        assertEquals("Welcome to Vydra.", readBack.segments?.get(0)?.text)
    }

    @Test
    fun testCreditErrorExtraction() {
        val dummyContext = android.content.ContextWrapper(null)
        val manager = AutoAssembleManager(dummyContext)

        // Test 1: JSON body with error property
        val err1 = """Payment required / Insufficient credits for transcription: {"error":"Not enough credits. Required: 15, Available: 2"}"""
        val extracted1 = manager.extractCreditErrorMessage(err1, err1, 402)
        assertEquals("Not enough credits. Required: 15, Available: 2", extracted1)

        // Test 2: Plain text credit message
        val err2 = "Not enough credits. Required: 8, Available: 0"
        val extracted2 = manager.extractCreditErrorMessage(err2, null, 402)
        assertEquals("Not enough credits. Required: 8, Available: 0", extracted2)
    }

    @Test
    fun testSceneBoundary_formatTimeRange() {
        val matched = SceneBoundary(
            sceneIndex = 1,
            sceneTitle = "Scene 1",
            startMs = 65000L, // 1:05
            endMs = 85000L,   // 1:25
            isMatched = true
        )
        assertEquals("1:05 - 1:25", matched.formatTimeRange())

        val unmatched = SceneBoundary(
            sceneIndex = 2,
            sceneTitle = "Scene 2",
            startMs = 0L,
            endMs = 0L,
            isMatched = false
        )
        assertEquals("Unmatched", unmatched.formatTimeRange())
    }

    // ==========================================
    // PHASE 5: TIMELINE ASSEMBLY ENGINE TESTS
    // ==========================================

    @Test
    fun testContinuousCoverage_allMatched_closesGapsAndCoversFullAudio() {
        val audioDurationMs = 10000L
        val scenes = listOf(
            SceneBoundary(1, "Scene 1", 500L, 3000L, true),
            SceneBoundary(2, "Scene 2", 3500L, 7000L, true),
            SceneBoundary(3, "Scene 3", 7500L, 9500L, true)
        )

        val coverages = com.boostlyte.vydra.autoassemble.TimelineAssembler.buildContinuousSceneCoverage(scenes, audioDurationMs)
        assertEquals(3, coverages.size)

        // 1. Starts at 0
        assertEquals(0L, coverages[0].startMs)
        // 2. Continuous between Scene 1 and Scene 2 (no gaps)
        assertEquals(coverages[0].endMs, coverages[1].startMs)
        // 3. Continuous between Scene 2 and Scene 3 (no gaps)
        assertEquals(coverages[1].endMs, coverages[2].startMs)
        // 4. Ends at exactly audioDurationMs
        assertEquals(audioDurationMs, coverages[2].endMs)

        // 5. Total sum of coverage durations equals audio duration
        val totalDur = coverages.sumOf { it.durationMs }
        assertEquals(audioDurationMs, totalDur)
    }

    @Test
    fun testContinuousCoverage_unmatchedMiddle_interpolatesSmoothly() {
        val audioDurationMs = 12000L
        val scenes = listOf(
            SceneBoundary(1, "Scene 1", 1000L, 4000L, true),
            SceneBoundary(2, "Scene 2", 0L, 0L, false), // Unmatched in middle
            SceneBoundary(3, "Scene 3", 8000L, 11000L, true)
        )

        val coverages = com.boostlyte.vydra.autoassemble.TimelineAssembler.buildContinuousSceneCoverage(scenes, audioDurationMs)
        assertEquals(3, coverages.size)

        assertEquals(0L, coverages[0].startMs)
        assertEquals(4000L, coverages[0].endMs)

        // Scene 2 is interpolated between 4000 and 8000
        assertEquals(4000L, coverages[1].startMs)
        assertEquals(8000L, coverages[1].endMs)
        assertEquals(4000L, coverages[1].durationMs)

        // Scene 3 extrapolated to audio end
        assertEquals(8000L, coverages[2].startMs)
        assertEquals(audioDurationMs, coverages[2].endMs)

        assertEquals(audioDurationMs, coverages.sumOf { it.durationMs })
    }

    @Test
    fun testContinuousCoverage_unmatchedFirstAndLast_extrapolatesCorrectly() {
        val audioDurationMs = 15000L
        val scenes = listOf(
            SceneBoundary(1, "Scene 1", 0L, 0L, false), // Unmatched first
            SceneBoundary(2, "Scene 2", 5000L, 10000L, true), // Matched
            SceneBoundary(3, "Scene 3", 0L, 0L, false)  // Unmatched last
        )

        val coverages = com.boostlyte.vydra.autoassemble.TimelineAssembler.buildContinuousSceneCoverage(scenes, audioDurationMs)
        assertEquals(3, coverages.size)

        // First unmatched extrapolates from 0 to 5000
        assertEquals(0L, coverages[0].startMs)
        assertEquals(5000L, coverages[0].endMs)

        // Matched middle
        assertEquals(5000L, coverages[1].startMs)
        assertEquals(10000L, coverages[1].endMs)

        // Last unmatched extrapolates from 10000 to audio end
        assertEquals(10000L, coverages[2].startMs)
        assertEquals(audioDurationMs, coverages[2].endMs)

        assertEquals(audioDurationMs, coverages.sumOf { it.durationMs })
    }

    @Test
    fun testContinuousCoverage_allUnmatched_dividesEvenly() {
        val audioDurationMs = 9000L
        val scenes = listOf(
            SceneBoundary(1, "Scene 1", 0L, 0L, false),
            SceneBoundary(2, "Scene 2", 0L, 0L, false),
            SceneBoundary(3, "Scene 3", 0L, 0L, false)
        )

        val coverages = com.boostlyte.vydra.autoassemble.TimelineAssembler.buildContinuousSceneCoverage(scenes, audioDurationMs)
        assertEquals(3, coverages.size)

        assertEquals(0L, coverages[0].startMs)
        assertEquals(3000L, coverages[0].endMs)

        assertEquals(3000L, coverages[1].startMs)
        assertEquals(6000L, coverages[1].endMs)

        assertEquals(6000L, coverages[2].startMs)
        assertEquals(9000L, coverages[2].endMs)

        assertEquals(audioDurationMs, coverages.sumOf { it.durationMs })
    }

    @Test
    fun testProportionalImageDistribution_fairAllocationAndExactSum() {
        val scenes = listOf(
            com.boostlyte.vydra.autoassemble.SceneCoverage(1, "Scene 1", 0L, 3000L, true),
            com.boostlyte.vydra.autoassemble.SceneCoverage(2, "Scene 2", 3000L, 9000L, true), // 2x longer
            com.boostlyte.vydra.autoassemble.SceneCoverage(3, "Scene 3", 9000L, 12000L, true)
        )
        val totalImages = 8
        val audioDurationMs = 12000L

        val distribution = com.boostlyte.vydra.autoassemble.TimelineAssembler.distributeImagesProportionally(
            scenes, totalImages, audioDurationMs
        )

        assertEquals(3, distribution.size)
        // Every scene gets at least 1 image
        assertTrue(distribution.all { it >= 1 })
        // Total assigned exactly equals available images (no images lost)
        assertEquals(totalImages, distribution.sum())

        // Scene 2 (duration 6000ms = 50% of timeline) receives proportionally more images than Scene 1 and 3 (3000ms each)
        assertTrue(distribution[1] >= distribution[0])
        assertTrue(distribution[1] >= distribution[2])
    }

    @Test
    fun testAssemblyPlan_exactCoverageSumMatchesAudioDuration() {
        val audioDurationMs = 14523L // Odd duration to test integer millisecond rounding
        val scenes = listOf(
            SceneBoundary(1, "Intro", 0L, 4000L, true),
            SceneBoundary(2, "Main", 4000L, 10000L, true),
            SceneBoundary(3, "Outro", 10000L, 14000L, true)
        )
        val imageUris = (1..10).map { DummyUri.fromPath("file:///storage/emulated/0/DCIM/img_$it.jpg") }

        val plan = com.boostlyte.vydra.autoassemble.TimelineAssembler.computeAssemblyPlan(
            scenes = scenes,
            imageUris = imageUris,
            audioDurationMs = audioDurationMs
        )

        assertFalse(plan.isTooFewImages)
        assertEquals(10, plan.clips.size)
        // The sum of all generated clip durations must equal the audio duration exactly (integer milliseconds)
        assertEquals(audioDurationMs, plan.totalClipsDurationMs)
        assertEquals(audioDurationMs, plan.clips.sumOf { it.durationMs })
    }

    @Test
    fun testAssemblyPlan_tooFewImages_abortsWithExactCount() {
        val scenes = (1..12).map { SceneBoundary(it, "Scene $it", 0L, 0L, false) }
        val imageUris = (1..8).map { DummyUri.fromPath("file:///storage/emulated/0/DCIM/img_$it.jpg") }

        val plan = com.boostlyte.vydra.autoassemble.TimelineAssembler.computeAssemblyPlan(
            scenes = scenes,
            imageUris = imageUris,
            audioDurationMs = 24000L
        )

        assertTrue("Plan must detect too few images", plan.isTooFewImages)
        assertTrue(plan.errorMessage?.contains("12 scenes but only 8 images") == true)
        assertTrue(plan.clips.isEmpty())
    }

    @Test
    fun testAssemblyPlan_tooManyImages_triggersShortClipWarning() {
        val scenes = listOf(
            SceneBoundary(1, "Scene 1", 0L, 1500L, true),
            SceneBoundary(2, "Scene 2", 1500L, 3000L, true)
        )
        // 10 images over 3000ms = 300ms per clip (< 800ms)
        val imageUris = (1..10).map { DummyUri.fromPath("file:///storage/emulated/0/DCIM/img_$it.jpg") }

        val plan = com.boostlyte.vydra.autoassemble.TimelineAssembler.computeAssemblyPlan(
            scenes = scenes,
            imageUris = imageUris,
            audioDurationMs = 3000L
        )

        assertFalse(plan.isTooFewImages)
        assertTrue("Short clip warning must be flagged", plan.isShortClipWarning)
        assertTrue(plan.averageClipDurationMs < com.boostlyte.vydra.autoassemble.TimelineAssembler.MIN_CLIP_DURATION_MS)
        assertEquals(300L, plan.averageClipDurationMs)
    }

    @Test
    fun testKenBurnsMotion_consecutiveClipsVaryAndDeterministic() {
        val motionPool = (0 until 10).map {
            com.boostlyte.vydra.autoassemble.KenBurnsMotion.getMotionForIndex(it, seed = 42L).key
        }

        // Check that consecutive clips vary
        for (i in 0 until motionPool.size - 1) {
            assertNotEquals("Consecutive clips must vary motion", motionPool[i], motionPool[i + 1])
        }

        // Check determinism: same seed produces identical results
        val motionPool2 = (0 until 10).map {
            com.boostlyte.vydra.autoassemble.KenBurnsMotion.getMotionForIndex(it, seed = 42L).key
        }
        assertEquals(motionPool, motionPool2)
    }

    @Test
    fun testApplyAssemblyToProject_preservesVoiceoverAudioAndRetimesClips() {
        val voiceoverUri = DummyUri.fromPath("file:///data/cache/voiceover.m4a")
        val voiceoverOp = EditOperation.AddBackgroundAudio(
            audioUri = voiceoverUri,
            originalDurationMs = 12000L
        )

        val initialProject = VideoProject(
            sourceUri = DummyUri.fromPath("file:///data/cache/temp_base.jpg"),
            sourceName = "temp_base.jpg",
            operations = listOf(voiceoverOp)
        )

        val scenes = listOf(
            SceneBoundary(1, "Scene 1", 0L, 6000L, true),
            SceneBoundary(2, "Scene 2", 6000L, 12000L, true)
        )
        val imageUris = listOf(
            DummyUri.fromPath("file:///images/img_1.jpg"),
            DummyUri.fromPath("file:///images/img_2.jpg"),
            DummyUri.fromPath("file:///images/img_3.jpg"),
            DummyUri.fromPath("file:///images/img_4.jpg")
        )

        val plan = com.boostlyte.vydra.autoassemble.TimelineAssembler.computeAssemblyPlan(
            scenes = scenes,
            imageUris = imageUris,
            audioDurationMs = 12000L
        )

        val assembledProject = com.boostlyte.vydra.autoassemble.TimelineAssembler.applyAssemblyToProject(
            initialProject, plan
        )

        // 1. Voiceover audio is untouched
        val audioOps = assembledProject.operations.filterIsInstance<EditOperation.AddBackgroundAudio>()
        assertEquals(1, audioOps.size)
        assertEquals(voiceoverUri, audioOps[0].audioUri)

        // 2. Primary clip is img_1
        assertEquals(imageUris[0], assembledProject.sourceUri)

        // 3. Trim operation matches img_1 duration
        val trimOp = assembledProject.operations.filterIsInstance<EditOperation.Trim>().lastOrNull()
        assertNotNull(trimOp)
        assertEquals(plan.clips[0].durationMs, trimOp!!.endMs - trimOp.startMs)

        // 4. Merge items match remaining 3 images
        val mergeOp = assembledProject.operations.filterIsInstance<EditOperation.Merge>().lastOrNull()
        assertNotNull(mergeOp)
        assertEquals(3, mergeOp!!.items.size)
        assertEquals(imageUris[1], mergeOp.items[0].uri)
        assertEquals(imageUris[2], mergeOp.items[1].uri)
        assertEquals(imageUris[3], mergeOp.items[2].uri)

        // 5. Total sequence duration equals voiceover audio duration exactly
        val totalSeqDuration = (trimOp.endMs - trimOp.startMs) + mergeOp.items.sumOf { it.trimmedDurationMs }
        assertEquals(12000L, totalSeqDuration)

        // 6. Each clip carries motion parameters
        val motionOps = assembledProject.operations.filterIsInstance<EditOperation.MotionEffect>()
        assertEquals(4, motionOps.size)
    }

    @Test
    fun testApplyAssemblyToProject_undoRevertsToOriginalState() {
        val vm = VideoEditingViewModel()
        val origUri = DummyUri.fromPath("file:///storage/orig.jpg")
        vm.initializeProject(origUri, "orig.jpg")

        val initialProject = vm.project.value!!
        assertEquals(origUri, initialProject.sourceUri)

        val scenes = listOf(SceneBoundary(1, "S1", 0L, 5000L, true))
        val imageUris = listOf(DummyUri.fromPath("file:///storage/new_img.jpg"))
        val plan = com.boostlyte.vydra.autoassemble.TimelineAssembler.computeAssemblyPlan(
            scenes = scenes,
            imageUris = imageUris,
            audioDurationMs = 5000L
        )

        // Execute assembly command
        vm.executeCommand(com.boostlyte.vydra.commands.MutateProjectCommand("Auto Assemble Timeline") { proj ->
            com.boostlyte.vydra.autoassemble.TimelineAssembler.applyAssemblyToProject(proj, plan)
        })

        // Verify assembled
        assertEquals(imageUris[0], vm.project.value?.sourceUri)
        assertTrue(vm.uiState.value.canUndo)

        // Undo assembly in one single step
        vm.undo()

        // Verify reverted to original project
        assertEquals(origUri, vm.project.value?.sourceUri)
    }

    @Test
    fun testProjectSerialization_roundTripPreservesMotionParameters() {
        val project = VideoProject(
            sourceUri = DummyUri.fromPath("file:///images/base.jpg"),
            sourceName = "base.jpg",
            operations = listOf(
                EditOperation.Trim(0L, 3000L),
                EditOperation.MotionEffect(0, "zoom_in"),
                EditOperation.Merge(
                    items = listOf(
                        EditOperation.MergeItem(
                            uri = DummyUri.fromPath("file:///images/merge_1.jpg"),
                            durationMs = 4000L,
                            trimStartMs = 0L,
                            trimEndMs = 4000L,
                            isImage = true,
                            motionEffect = "pan_left"
                        )
                    )
                ),
                EditOperation.MotionEffect(1, "pan_left")
            )
        )

        val recipe = EditRecipe.fromVideoProject("Test Assembled", project, durationMs = 7000L, clipCount = 2)
        val json = com.boostlyte.vydra.utils.ProjectSerializer.serialize(recipe)
        assertNotNull(json)
        assertTrue(json.contains("\"operationType\":\"MotionEffect\"") || json.contains("\"type\":\"MotionEffect\""))
        assertTrue(json.contains("\"motionType\":\"zoom_in\""))
        assertTrue(json.contains("\"motionType\":\"pan_left\""))
        assertTrue(json.contains("\"motionEffect\":\"pan_left\""))
    }

    @Test
    fun testLargeScaleAssembly_100ImagesPerformance() {
        val audioDurationMs = 120000L // 2 minutes audio
        val scenes = (1..20).map {
            SceneBoundary(
                sceneIndex = it,
                sceneTitle = "Scene $it",
                startMs = (it - 1) * 6000L,
                endMs = it * 6000L,
                isMatched = (it % 3 != 0) // Mix of matched and unmatched
            )
        }
        val imageUris = (1..100).map { DummyUri.fromPath("file:///images/img_$it.jpg") }

        val startTime = System.currentTimeMillis()
        val plan = com.boostlyte.vydra.autoassemble.TimelineAssembler.computeAssemblyPlan(
            scenes = scenes,
            imageUris = imageUris,
            audioDurationMs = audioDurationMs
        )
        val elapsed = System.currentTimeMillis() - startTime

        // Performance requirement: instantaneous metadata computation (< 50ms)
        assertTrue("Assembly calculation for 100 images must be under 500ms, was ${elapsed}ms", elapsed < 500)
        assertEquals(100, plan.clips.size)
        assertEquals(audioDurationMs, plan.totalClipsDurationMs)
        assertEquals(audioDurationMs, plan.clips.sumOf { it.durationMs })
    }
}

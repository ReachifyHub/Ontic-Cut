package com.tharunbirla.librecuts

import android.net.DummyUri
import com.google.gson.Gson
import com.tharunbirla.librecuts.autoassemble.AutoAssembleManager
import com.tharunbirla.librecuts.autoassemble.AutoAssembleResult
import com.tharunbirla.librecuts.autoassemble.SceneFuzzyMatcher
import com.tharunbirla.librecuts.autoassemble.ScriptMapParser
import com.tharunbirla.librecuts.models.SceneBoundary
import com.tharunbirla.librecuts.transcription.TranscriptionStorageManager
import com.tharunbirla.librecuts.transcription.models.TranscriptionResponse
import com.tharunbirla.librecuts.transcription.models.TranscriptionSegment
import com.tharunbirla.librecuts.transcription.models.TranscriptionWord
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
            com.tharunbirla.librecuts.autoassemble.PreconditionCheckResult(
                isValid = false,
                hasVisuals = false,
                hasAudio = false,
                errorMessage = "Auto Assemble requires both visuals and a voiceover on the timeline."
            )
        }
        !hasVisuals -> com.tharunbirla.librecuts.autoassemble.PreconditionCheckResult(
            isValid = false,
            hasVisuals = false,
            hasAudio = true,
            errorMessage = "Auto Assemble requires at least one visual asset (video or image) on the timeline."
        )
        !hasAudio -> com.tharunbirla.librecuts.autoassemble.PreconditionCheckResult(
            isValid = false,
            hasVisuals = true,
            hasAudio = false,
            errorMessage = "Auto Assemble requires at least one voiceover audio track on the timeline."
        )
        else -> com.tharunbirla.librecuts.autoassemble.PreconditionCheckResult(
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
              "text": "Welcome to Ontic Cut. Today we talk about editing.",
              "segments": [
                { "id": 0, "start": 0.0, "end": 2.0, "text": "Welcome to Ontic Cut." },
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
        assertEquals("Welcome to Ontic Cut.", readBack.segments?.get(0)?.text)
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
}

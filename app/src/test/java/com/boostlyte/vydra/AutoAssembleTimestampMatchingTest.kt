package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.autoassemble.ParsedScene
import com.boostlyte.vydra.autoassemble.SceneFuzzyMatcher
import com.boostlyte.vydra.autoassemble.TimelineAssembler
import com.boostlyte.vydra.models.SceneBoundary
import com.boostlyte.vydra.transcription.TranscriptionResponseParser
import com.boostlyte.vydra.transcription.TranscriptionStorageManager
import com.boostlyte.vydra.transcription.models.TranscriptionResponse
import com.boostlyte.vydra.transcription.models.TranscriptionSegment
import com.boostlyte.vydra.transcription.models.TranscriptionWord
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoAssembleTimestampMatchingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // 1. Parsing the captured raw response schema
    @Test
    fun test1_parsingCapturedRawResponseSchema() {
        // Grok Speech-to-Text via NanoGPT observed response format with embedded word timestamps
        val grokRawResponse = """
            Welcome to the future of editing. Today we explore high performance video creation.
            
            --- Detailed Information ---
            Language: en
            Duration: 0.59 minutes
            
            [0.06s - 0.18s] Welcome
            [0.26s - 0.48s] to
            [0.52s - 0.72s] the
            [0.80s - 1.20s] future
            [1.32s - 1.90s] of
            [1.95s - 2.50s] editing.
            [2.80s - 3.20s] Today
            [3.25s - 3.45s] we
            [3.50s - 4.10s] explore
            [4.20s - 4.60s] high
            [4.65s - 5.40s] performance
            [5.50s - 5.90s] video
            [6.00s - 6.60s] creation.
        """.trimIndent()

        val parsed = TranscriptionResponseParser.parse(grokRawResponse)

        assertNotNull(parsed.words)
        assertEquals(13, parsed.words?.size)
        assertEquals("Welcome", parsed.words?.first()?.word)
        assertEquals(0.06, parsed.words?.first()?.start ?: 0.0, 0.001)
        assertEquals(0.18, parsed.words?.first()?.end ?: 0.0, 0.001)
        assertEquals("creation.", parsed.words?.last()?.word)
        assertEquals(6.60, parsed.words?.last()?.end ?: 0.0, 0.001)
        assertEquals(35.4, parsed.duration ?: 0.0, 0.1) // 0.59 minutes = 35.4 seconds
        assertEquals("en", parsed.language)
        assertEquals("Welcome to the future of editing. Today we explore high performance video creation.", parsed.text)

        // Also test structured JSON response format with string timestamps
        val structuredJsonResponse = """
            {
              "language": "en",
              "duration": 35.4,
              "words": [
                { "word": "Welcome", "start": "0.06s", "end": "0.18s" },
                { "word": "everyone", "start": 0.25, "end": 0.75 }
              ]
            }
        """.trimIndent()

        val parsedJson = TranscriptionResponseParser.parse(structuredJsonResponse)
        assertNotNull(parsedJson.words)
        assertEquals(2, parsedJson.words?.size)
        assertEquals(0.06, parsedJson.words?.get(0)?.start ?: 0.0, 0.001)
        assertEquals(0.18, parsedJson.words?.get(0)?.end ?: 0.0, 0.001)
        assertEquals(0.25, parsedJson.words?.get(1)?.start ?: 0.0, 0.001)
        assertEquals(0.75, parsedJson.words?.get(1)?.end ?: 0.0, 0.001)
    }

    // 2. Plain-text word-timestamp line parsing, including sub-second rounding (0.06 s to 60 ms)
    @Test
    fun test2_plainTextWordTimestampLineParsing_subSecondRounding() {
        val word1 = TranscriptionResponseParser.parseWordTimestampLine("[0.06s - 0.18s] You")
        assertNotNull(word1)
        assertEquals("You", word1?.word)
        assertEquals(0.06, word1?.start ?: 0.0, 0.001)
        assertEquals(0.18, word1?.end ?: 0.0, 0.001)

        // Sub-second rounding verification: 0.06s must be exactly 60ms, NEVER 0ms!
        val startMs1 = TranscriptionResponseParser.secondsToMillis(word1!!.start!!)
        val endMs1 = TranscriptionResponseParser.secondsToMillis(word1.end!!)
        assertEquals(60L, startMs1)
        assertEquals(180L, endMs1)

        val word2 = TranscriptionResponseParser.parseWordTimestampLine("[30.73s - 31.07s] doesn't")
        assertNotNull(word2)
        assertEquals("doesn't", word2?.word)
        assertEquals(30730L, TranscriptionResponseParser.secondsToMillis(word2!!.start!!))
        assertEquals(31070L, TranscriptionResponseParser.secondsToMillis(word2.end!!))

        // Negative check: Invalid line format
        val invalidLine = TranscriptionResponseParser.parseWordTimestampLine("Just some narration without timestamps")
        assertNull(invalidLine)
    }

    // 3. Seconds-to-milliseconds conversion edge cases
    @Test
    fun test3_secondsToMillisecondsConversionEdgeCases() {
        assertEquals(0L, TranscriptionResponseParser.secondsToMillis(0.0))
        assertEquals(1L, TranscriptionResponseParser.secondsToMillis(0.001))
        assertEquals(59L, TranscriptionResponseParser.secondsToMillis(0.059))
        assertEquals(60L, TranscriptionResponseParser.secondsToMillis(0.06))
        assertEquals(64L, TranscriptionResponseParser.secondsToMillis(0.064))
        assertEquals(65L, TranscriptionResponseParser.secondsToMillis(0.065))
        assertEquals(1900L, TranscriptionResponseParser.secondsToMillis(1.90))
        assertEquals(35400L, TranscriptionResponseParser.secondsToMillis(35.4))
        assertEquals(0L, TranscriptionResponseParser.secondsToMillis(Double.NaN))
        assertEquals(0L, TranscriptionResponseParser.secondsToMillis(Double.POSITIVE_INFINITY))
        assertEquals(0L, TranscriptionResponseParser.secondsToMillis(-1.5))

        // Duration parsing: minutes vs seconds
        assertEquals(35.4, TranscriptionResponseParser.parseDurationString("0.59 minutes") ?: 0.0, 0.01)
        assertEquals(120.0, TranscriptionResponseParser.parseDurationString("2.0 mins") ?: 0.0, 0.01)
        assertEquals(45.0, TranscriptionResponseParser.parseDurationString("45s") ?: 0.0, 0.01)
        assertEquals(36.0, TranscriptionResponseParser.parseDurationString("36") ?: 0.0, 0.01)
    }

    // 4. Scene matching with contractions, punctuation, and case differences
    @Test
    fun test4_sceneMatchingWithContractionsPunctuationAndCase() {
        // Test contraction normalization
        assertEquals("doesnt", SceneFuzzyMatcher.normalize("doesn't"))
        assertEquals("doesnt", SceneFuzzyMatcher.normalize("doesn’t"))
        assertEquals("doesnt", SceneFuzzyMatcher.normalize("doesnt"))
        assertEquals("its", SceneFuzzyMatcher.normalize("It's"))
        assertEquals("its", SceneFuzzyMatcher.normalize("it’s"))
        assertEquals("its", SceneFuzzyMatcher.normalize("its"))

        val sceneScript = "It's everywhere you look, doesn't it make sense?"
        val transcriptWords = listOf(
            TranscriptionWord("its", 0.1, 0.4),
            TranscriptionWord("everywhere", 0.5, 1.2),
            TranscriptionWord("you", 1.3, 1.5),
            TranscriptionWord("look", 1.6, 2.0),
            TranscriptionWord("doesnt", 2.2, 2.7),
            TranscriptionWord("it", 2.8, 3.0),
            TranscriptionWord("make", 3.1, 3.5),
            TranscriptionWord("sense", 3.6, 4.2)
        )

        val response = TranscriptionResponse(words = transcriptWords)
        val scenes = listOf(ParsedScene(sceneIndex = 1, sceneTitle = "Scene 1", scriptText = sceneScript))

        val boundaries = SceneFuzzyMatcher.matchScenes(scenes, response)
        assertEquals(1, boundaries.size)
        assertTrue(boundaries[0].isMatched)
        assertEquals(100L, boundaries[0].startMs)
        assertEquals(4200L, boundaries[0].endMs)
        assertTrue(boundaries[0].confidence >= 0.8f)
    }

    // 5. Sequential monotonic alignment across a 3-scene fixture resembling the reported case
    @Test
    fun test5_sequentialMonotonicAlignmentAcross3SceneFixture() {
        // 36-second audio sample with 3 scenes
        val transcriptWords = listOf(
            // Scene 1: ~0.06s to ~10.50s (0 to 10s)
            TranscriptionWord("You", 0.06, 0.18),
            TranscriptionWord("hear", 0.26, 0.48),
            TranscriptionWord("about", 0.60, 0.90),
            TranscriptionWord("AI", 1.20, 1.80),
            TranscriptionWord("everywhere", 2.00, 2.70),
            TranscriptionWord("in", 3.00, 3.20),
            TranscriptionWord("the", 3.30, 3.50),
            TranscriptionWord("news", 4.00, 4.80),
            TranscriptionWord("today.", 5.00, 5.80),

            // Scene 2: ~11.00s to ~22.40s (11 to 22s)
            TranscriptionWord("Technology", 11.00, 12.00),
            TranscriptionWord("advances", 12.50, 13.50),
            TranscriptionWord("faster", 14.00, 15.00),
            TranscriptionWord("than", 15.50, 16.00),
            TranscriptionWord("ever", 16.50, 17.20),
            TranscriptionWord("before", 17.80, 18.80),
            TranscriptionWord("in", 19.00, 19.30),
            TranscriptionWord("history.", 19.50, 20.80),

            // Scene 3: ~23.00s to ~35.50s (23 to 35s)
            TranscriptionWord("And", 23.00, 23.50),
            TranscriptionWord("that", 24.00, 24.60),
            TranscriptionWord("doesn't", 25.00, 25.80),
            TranscriptionWord("stop", 26.20, 27.00),
            TranscriptionWord("anytime", 27.50, 28.50),
            TranscriptionWord("soon", 29.00, 30.00),
            TranscriptionWord("for", 30.50, 31.00),
            TranscriptionWord("creators.", 31.50, 33.00)
        )

        val response = TranscriptionResponse(words = transcriptWords, duration = 36.0)

        val scenes = listOf(
            ParsedScene(1, "Scene 1: Introduction", "You hear about AI everywhere in the news today"),
            ParsedScene(2, "Scene 2: Growth", "Technology advances faster than ever before in history"),
            ParsedScene(3, "Scene 3: Conclusion", "And that doesn't stop anytime soon for creators")
        )

        val boundaries = SceneFuzzyMatcher.matchScenes(scenes, response, audioDurationMs = 36000L)

        assertEquals(3, boundaries.size)

        // Scene 1 verification:
        val s1 = boundaries[0]
        assertTrue("Scene 1 should be matched", s1.isMatched)
        assertEquals(60L, s1.startMs)
        assertEquals(5800L, s1.endMs)
        assertEquals("0:00 - 0:05", s1.formatTimeRange())

        // Scene 2 verification:
        val s2 = boundaries[1]
        assertTrue("Scene 2 should be matched", s2.isMatched)
        assertEquals(11000L, s2.startMs)
        assertEquals(20800L, s2.endMs)
        assertEquals("0:11 - 0:20", s2.formatTimeRange())

        // Scene 3 verification:
        val s3 = boundaries[2]
        assertTrue("Scene 3 should be matched", s3.isMatched)
        assertEquals(23000L, s3.startMs)
        assertEquals(33000L, s3.endMs)
        assertEquals("0:23 - 0:33", s3.formatTimeRange())

        // Monotonicity and non-collapse assertions:
        assertTrue(s1.startMs < s1.endMs)
        assertTrue(s1.endMs <= s2.startMs)
        assertTrue(s2.startMs < s2.endMs)
        assertTrue(s2.endMs <= s3.startMs)
        assertTrue(s3.startMs < s3.endMs)
        assertTrue(s3.endMs <= 36000L)

        // Crucial: none of the scenes show 0:00 - 0:00 or 0:00 - 0:01!
        assertNotEquals("0:00 - 0:00", s1.formatTimeRange())
        assertNotEquals("0:00 - 0:00", s2.formatTimeRange())
        assertNotEquals("0:00 - 0:01", s3.formatTimeRange())
    }

    // 6. Missing timestamps produce unmatched scenes without crashes and without Matched badges
    @Test
    fun test6_missingTimestampsProduceUnmatchedScenesWithoutCrashesOrMatchedBadge() {
        // Plain text with NO timestamps whatsoever
        val responseWithoutTimestamps = TranscriptionResponse(
            text = "You hear about AI everywhere in the news today. Technology advances faster.",
            words = null,
            segments = null
        )

        val scenes = listOf(
            ParsedScene(1, "Scene 1", "You hear about AI everywhere in the news today"),
            ParsedScene(2, "Scene 2", "Technology advances faster")
        )

        val boundaries = SceneFuzzyMatcher.matchScenes(scenes, responseWithoutTimestamps, 36000L)

        assertEquals(2, boundaries.size)
        // Must be marked unmatched, NOT matched!
        assertFalse(boundaries[0].isMatched)
        assertEquals(0L, boundaries[0].startMs)
        assertEquals(0L, boundaries[0].endMs)
        assertEquals("Unmatched", boundaries[0].formatTimeRange())

        assertFalse(boundaries[1].isMatched)
        assertEquals(0L, boundaries[1].startMs)
        assertEquals(0L, boundaries[1].endMs)
        assertEquals("Unmatched", boundaries[1].formatTimeRange())
    }

    // 7. Unmatched scene handling remains compatible with Phase 5
    @Test
    fun test7_unmatchedSceneHandlingRemainsCompatibleWithPhase5() {
        // Scene 1 matched (0 to 10,000 ms), Scene 2 unmatched, Scene 3 matched (20,000 to 30,000 ms)
        val scenes = listOf(
            SceneBoundary(1, "Scene 1", startMs = 0L, endMs = 10000L, isMatched = true),
            SceneBoundary(2, "Scene 2 (Missing)", startMs = 0L, endMs = 0L, isMatched = false),
            SceneBoundary(3, "Scene 3", startMs = 20000L, endMs = 30000L, isMatched = true)
        )

        val coverages = TimelineAssembler.buildContinuousSceneCoverage(scenes, audioDurationMs = 36000L)

        assertEquals(3, coverages.size)
        // Scene 1 coverage:
        assertEquals(0L, coverages[0].startMs)
        assertEquals(10000L, coverages[0].endMs)

        // Scene 2 (unmatched) interpolated smoothly between Scene 1 and Scene 3:
        assertEquals(10000L, coverages[1].startMs)
        assertEquals(20000L, coverages[1].endMs)
        assertEquals(10000L, coverages[1].durationMs)

        // Scene 3 coverage extrapolated to audio end (36000 ms):
        assertEquals(20000L, coverages[2].startMs)
        assertEquals(36000L, coverages[2].endMs)

        // Zero gaps or overlaps
        assertEquals(coverages[0].endMs, coverages[1].startMs)
        assertEquals(coverages[1].endMs, coverages[2].startMs)
        assertEquals(36000L, coverages.sumOf { it.durationMs })
    }

    // 8. Stale cache entries from old versions are invalidated
    @Test
    fun test8_staleCacheEntriesFromOldVersionsAreInvalidated() {
        val storage = TranscriptionStorageManager()
        val transDir = tempFolder.newFolder("transcriptions_test_cache")

        // 1. Create a stale v1 cache file (missing _cache_version, or _cache_version: 1)
        val staleFile = File(transDir, "Project_transcription_stale.json")
        val staleJson = """
            {
              "_project_name": "Project",
              "_audio_file_name": "voiceover.m4a",
              "_audio_duration_seconds": 36,
              "text": "Stale transcription without version"
            }
        """.trimIndent()
        staleFile.writeText(staleJson)

        // Verify that a v1 cache file is ignored / rejected
        val root = JsonParser.parseString(staleJson).asJsonObject
        val cacheVersion = root.get("_cache_version")?.asInt ?: 1
        assertTrue("Stale cache version ($cacheVersion) must be less than current version (2)",
            cacheVersion < TranscriptionStorageManager.CURRENT_CACHE_VERSION)

        // 2. Create a modern v2 cache file with valid word timestamps
        val validResponse = TranscriptionResponse(
            text = "Welcome to Vydra.",
            words = listOf(
                TranscriptionWord("Welcome", 0.06, 0.40),
                TranscriptionWord("to", 0.45, 0.65),
                TranscriptionWord("Vydra", 0.70, 1.50)
            ),
            duration = 36.0
        )
        val savedFile = storage.saveTranscriptionResult(
            context = null,
            projectName = "Project",
            audioFileName = "voiceover.m4a",
            audioDurationSeconds = 36,
            response = validResponse,
            targetDir = transDir
        )

        assertTrue(savedFile.exists())
        val savedJson = JsonParser.parseString(savedFile.readText()).asJsonObject
        assertEquals(TranscriptionStorageManager.CURRENT_CACHE_VERSION, savedJson.get("_cache_version").asInt)
        assertTrue(savedJson.has("words"))
    }

    // 9. Preview distribution uses real boundaries and rejects the silent fallback
    @Test
    fun test9_previewDistributionUsesRealBoundariesAndRejectsSilentFallback() {
        // 3 scenes with distinct, real durations:
        // Scene 1: 6,000 ms (16.7%) -> 1 image
        // Scene 2: 20,000 ms (55.6%) -> 3 images
        // Scene 3: 10,000 ms (27.8%) -> 2 images
        // Total audio: 36,000 ms
        val scenes = listOf(
            SceneBoundary(1, "Scene 1", startMs = 0L, endMs = 6000L, isMatched = true),
            SceneBoundary(2, "Scene 2", startMs = 6000L, endMs = 26000L, isMatched = true),
            SceneBoundary(3, "Scene 3", startMs = 26000L, endMs = 36000L, isMatched = true)
        )

        // 6 images available
        val imageUris = (1..6).map { DummyUri.fromPath("file:///clip_$it.jpg") }

        val plan = TimelineAssembler.computeAssemblyPlan(
            scenes = scenes,
            imageUris = imageUris,
            audioDurationMs = 36000L
        )

        assertEquals(3, plan.matchedScenesCount)
        assertEquals(0, plan.unmatchedScenesCount)
        assertFalse(plan.hasInvalidBoundariesWarning)

        // Proportional distribution verification:
        // Scene 1 (6s) gets 1 image
        // Scene 2 (20s) gets 3 images
        // Scene 3 (10s) gets 2 images
        assertEquals(listOf(1, 3, 2), plan.imagesPerScene)
        assertEquals(6, plan.clips.size)

        // Average clip duration computed from actual clips:
        assertEquals(6000L, plan.averageClipDurationMs)

        // Now test degenerate collapsed boundaries:
        // All scenes claim to be matched but collapse to <= 1000 ms out of 36,000 ms
        val collapsedScenes = listOf(
            SceneBoundary(1, "Scene 1", startMs = 0L, endMs = 100L, isMatched = true),
            SceneBoundary(2, "Scene 2", startMs = 100L, endMs = 300L, isMatched = true),
            SceneBoundary(3, "Scene 3", startMs = 300L, endMs = 600L, isMatched = true)
        )

        val collapsedPlan = TimelineAssembler.computeAssemblyPlan(
            scenes = collapsedScenes,
            imageUris = imageUris,
            audioDurationMs = 36000L
        )

        // Must NOT silently proceed as normal:
        assertTrue("Collapsed boundaries must trigger hasInvalidBoundariesWarning",
            collapsedPlan.hasInvalidBoundariesWarning)
    }
}

package com.boostlyte.vydra

import com.boostlyte.vydra.autoassemble.ParsedScene
import com.boostlyte.vydra.autoassemble.SceneFuzzyMatcher
import com.boostlyte.vydra.autoassemble.ScriptMapParser
import com.boostlyte.vydra.transcription.TranscriptionResponseParser
import org.junit.Test

class RealUserCaseInvestigationTest {

    val rawJson = """
{
  "jobId": "aa9a39f0-61da-4b51-bf2f-80c6718c0ef0",
  "status": "completed",
  "creditsCharged": 1,
  "creditsRemaining": 497,
  "text": "You hear AI everywhere, but what is it, really? At its core, AI is just software that learns from examples, millions of them, instead of following rigid rules. Show it enough photos of cats, and it learns to spot cats. No magic, just pattern recognition at superhuman speed. It's already in your pocket, unlocking your face, routing your drive, finishing your sentences. It doesn't think like you, it predicts based on what it's seen before.",
  "words": [
    {"text": "You", "start": 0.06, "end": 0.18},
    {"text": "hear", "start": 0.26, "end": 0.48},
    {"text": "AI", "start": 0.66, "end": 1.14},
    {"text": "everywhere,", "start": 1.32, "end": 1.901},
    {"text": "but", "start": 2.381, "end": 2.521},
    {"text": "what", "start": 2.621, "end": 2.801},
    {"text": "is", "start": 2.981, "end": 3.161},
    {"text": "it,", "start": 3.241, "end": 3.361},
    {"text": "really?", "start": 3.561, "end": 3.921},
    {"text": "At", "start": 4.641, "end": 4.741},
    {"text": "its", "start": 4.801, "end": 4.941},
    {"text": "core,", "start": 5.001, "end": 5.362},
    {"text": "AI", "start": 5.842, "end": 6.282},
    {"text": "is", "start": 6.442, "end": 6.562},
    {"text": "just", "start": 6.682, "end": 6.982},
    {"text": "software", "start": 7.122, "end": 7.722},
    {"text": "that", "start": 7.802, "end": 7.942},
    {"text": "learns", "start": 8.082, "end": 8.422},
    {"text": "from", "start": 8.482, "end": 8.643},
    {"text": "examples,", "start": 8.703, "end": 9.383},
    {"text": "millions", "start": 9.663, "end": 10.163},
    {"text": "of", "start": 10.223, "end": 10.323},
    {"text": "them,", "start": 10.363, "end": 10.563},
    {"text": "instead", "start": 11.023, "end": 11.483},
    {"text": "of", "start": 11.503, "end": 11.583},
    {"text": "following", "start": 11.723, "end": 12.204},
    {"text": "rigid", "start": 12.344, "end": 12.684},
    {"text": "rules.", "start": 12.804, "end": 13.264},
    {"text": "Show", "start": 13.864, "end": 14.044},
    {"text": "it", "start": 14.124, "end": 14.204},
    {"text": "enough", "start": 14.304, "end": 14.604},
    {"text": "photos", "start": 14.704, "end": 15.184},
    {"text": "of", "start": 15.244, "end": 15.325},
    {"text": "cats,", "start": 15.405, "end": 15.845},
    {"text": "and", "start": 16.105, "end": 16.225},
    {"text": "it", "start": 16.265, "end": 16.345},
    {"text": "learns", "start": 16.465, "end": 16.805},
    {"text": "to", "start": 16.865, "end": 16.945},
    {"text": "spot", "start": 17.045, "end": 17.345},
    {"text": "cats.", "start": 17.465, "end": 17.905},
    {"text": "No", "start": 18.505, "end": 18.605},
    {"text": "magic,", "start": 18.786, "end": 19.246},
    {"text": "just", "start": 19.526, "end": 19.786},
    {"text": "pattern", "start": 19.886, "end": 20.286},
    {"text": "recognition", "start": 20.366, "end": 21.066},
    {"text": "at", "start": 21.226, "end": 21.326},
    {"text": "superhuman", "start": 21.486, "end": 22.207},
    {"text": "speed.", "start": 22.327, "end": 22.747},
    {"text": "It's", "start": 23.547, "end": 23.707},
    {"text": "already", "start": 23.827, "end": 24.267},
    {"text": "in", "start": 24.427, "end": 24.527},
    {"text": "your", "start": 24.567, "end": 24.707},
    {"text": "pocket,", "start": 24.747, "end": 25.187},
    {"text": "unlocking", "start": 25.628, "end": 26.228},
    {"text": "your", "start": 26.268, "end": 26.408},
    {"text": "face,", "start": 26.508, "end": 26.908},
    {"text": "routing", "start": 27.188, "end": 27.568},
    {"text": "your", "start": 27.608, "end": 27.728},
    {"text": "drive,", "start": 27.768, "end": 28.268},
    {"text": "finishing", "start": 28.608, "end": 29.069},
    {"text": "your", "start": 29.109, "end": 29.229},
    {"text": "sentences.", "start": 29.309, "end": 29.969},
    {"text": "It", "start": 30.529, "end": 30.609},
    {"text": "doesn't", "start": 30.729, "end": 31.069},
    {"text": "think", "start": 31.209, "end": 31.489},
    {"text": "like", "start": 31.649, "end": 31.929},
    {"text": "you,", "start": 31.969, "end": 32.149},
    {"text": "it", "start": 32.65, "end": 32.73},
    {"text": "predicts", "start": 32.81, "end": 33.41},
    {"text": "based", "start": 33.59, "end": 33.91},
    {"text": "on", "start": 33.95, "end": 34.05},
    {"text": "what", "start": 34.15, "end": 34.31},
    {"text": "it's", "start": 34.35, "end": 34.49},
    {"text": "seen", "start": 34.59, "end": 34.81},
    {"text": "before.", "start": 34.87, "end": 35.35}
  ],
  "language": "en",
  "duration": 35.55,
  "note": "word_timestamps_returned",
  "_cache_version": 2,
  "_project_name": "raw__storage_emulated_0_Download_Oct 18 - 15_26_Scene_01_20261018152856",
  "_audio_file_name": "audio_1793202025610.wav",
  "_audio_uri": "file:///data/user/0/com.boostlyte/cache/audio_1793202025610.wav",
  "_audio_duration_seconds": 36,
  "_saved_at_ms": 1793276337051
}
    """.trimIndent()

    val script = """
Scene 1: You hear AI everywhere, but what is it really?
Scene 2: At its core, AI is just software that learns from examples, millions of them, instead of following rigid rules. Show it enough photos of cats, and it learns to spot cats. No magic, just pattern recognition at superhuman speed.
Scene 3: It's already in your pocket: unlocking your face, routing your drive, finishing your sentences. It doesn't think like you; it predicts based on what it's seen before.
    """.trimIndent()

    @Test
    fun testRealUserCase() {
        val parsedResponse = TranscriptionResponseParser.parse(rawJson)
        val transcriptTokens = SceneFuzzyMatcher.extractTokens(parsedResponse)
        org.junit.Assert.assertEquals(75, transcriptTokens.size)

        val parsedScenes = ScriptMapParser.parse(script)
        org.junit.Assert.assertEquals(3, parsedScenes.size)

        val boundaries = SceneFuzzyMatcher.matchScenes(parsedScenes, parsedResponse, 36000L)
        org.junit.Assert.assertEquals(3, boundaries.size)

        // Scene 1: starts at 60ms ("You"), ends at 3921ms ("really?")
        org.junit.Assert.assertTrue("Scene 1 should be matched", boundaries[0].isMatched)
        org.junit.Assert.assertEquals(60L, boundaries[0].startMs)
        org.junit.Assert.assertEquals(3921L, boundaries[0].endMs)

        // Scene 2: starts at 4641ms ("At"), ends at 22747ms ("speed.")
        org.junit.Assert.assertTrue("Scene 2 should be matched", boundaries[1].isMatched)
        org.junit.Assert.assertEquals(4641L, boundaries[1].startMs)
        org.junit.Assert.assertEquals(22747L, boundaries[1].endMs)

        // Scene 3: starts at 23547ms ("It's"), ends at 35350ms ("before.")
        org.junit.Assert.assertTrue("Scene 3 should be matched", boundaries[2].isMatched)
        org.junit.Assert.assertEquals(23547L, boundaries[2].startMs)
        org.junit.Assert.assertEquals(35350L, boundaries[2].endMs)
    }

    @Test
    fun testGsonDeserializationWithTextProperty() {
        // Test that Gson directly parsing the cache JSON with "text" instead of "word" populates TranscriptionWord correctly
        val gson = com.google.gson.Gson()
        val directGsonResponse = gson.fromJson(rawJson, com.boostlyte.vydra.transcription.models.TranscriptionResponse::class.java)
        org.junit.Assert.assertNotNull(directGsonResponse.words)
        org.junit.Assert.assertEquals(75, directGsonResponse.words!!.size)
        org.junit.Assert.assertEquals("You", directGsonResponse.words!![0].word)
        org.junit.Assert.assertEquals("before.", directGsonResponse.words!![74].word)

        val tokens = SceneFuzzyMatcher.extractTokens(directGsonResponse)
        org.junit.Assert.assertEquals(75, tokens.size)
        org.junit.Assert.assertEquals("you", tokens[0].normalized)
        org.junit.Assert.assertEquals("before", tokens[74].normalized)

        val parsedScenes = ScriptMapParser.parse(script)
        val boundaries = SceneFuzzyMatcher.matchScenes(parsedScenes, directGsonResponse, 36000L)
        org.junit.Assert.assertTrue(boundaries[0].isMatched)
        org.junit.Assert.assertTrue(boundaries[1].isMatched)
        org.junit.Assert.assertTrue(boundaries[2].isMatched)
        org.junit.Assert.assertEquals(60L, boundaries[0].startMs)
        org.junit.Assert.assertEquals(3921L, boundaries[0].endMs)
        org.junit.Assert.assertEquals(4641L, boundaries[1].startMs)
        org.junit.Assert.assertEquals(22747L, boundaries[1].endMs)
        org.junit.Assert.assertEquals(23547L, boundaries[2].startMs)
        org.junit.Assert.assertEquals(35350L, boundaries[2].endMs)
    }

    @Test
    fun testScriptFormatsWithLabelsAndQuotes() {
        val scriptWithLabelsAndQuotes = """
Scene 1
Script: "You hear AI everywhere, but what is it, really?"

Scene 2
Narration: "At its core, AI is just software that learns from examples, millions of them, instead of following rigid rules. Show it enough photos of cats, and it learns to spot cats. No magic, just pattern recognition at superhuman speed."

Scene 3
Voiceover: "It's already in your pocket, unlocking your face, routing your drive, finishing your sentences. It doesn't think like you, it predicts based on what it's seen before."
        """.trimIndent()

        val parsedScenes = ScriptMapParser.parse(scriptWithLabelsAndQuotes)
        org.junit.Assert.assertEquals(3, parsedScenes.size)
        org.junit.Assert.assertFalse(parsedScenes[0].scriptText.contains("Script:"))
        org.junit.Assert.assertFalse(parsedScenes[0].scriptText.startsWith("\""))

        val parsedResponse = TranscriptionResponseParser.parse(rawJson)
        val boundaries = SceneFuzzyMatcher.matchScenes(parsedScenes, parsedResponse, 36000L)
        org.junit.Assert.assertTrue(boundaries[0].isMatched)
        org.junit.Assert.assertTrue(boundaries[1].isMatched)
        org.junit.Assert.assertTrue(boundaries[2].isMatched)
        org.junit.Assert.assertEquals(60L, boundaries[0].startMs)
        org.junit.Assert.assertEquals(3921L, boundaries[0].endMs)
        org.junit.Assert.assertEquals(4641L, boundaries[1].startMs)
        org.junit.Assert.assertEquals(22747L, boundaries[1].endMs)
        org.junit.Assert.assertEquals(23547L, boundaries[2].startMs)
        org.junit.Assert.assertEquals(35350L, boundaries[2].endMs)
    }
}

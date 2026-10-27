package com.boostlyte.vydra

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.boostlyte.vydra.transcription.SupabaseConfig
import com.boostlyte.vydra.transcription.SupabaseStorageClient
import com.boostlyte.vydra.transcription.SupabaseWhisperTranscriptionProvider
import com.boostlyte.vydra.transcription.TranscriptionProvider
import com.boostlyte.vydra.transcription.TranscriptionStorageManager
import com.boostlyte.vydra.transcription.VoiceoverTranscriptionPipeline
import com.boostlyte.vydra.transcription.models.TranscribeWhisperRequest
import com.boostlyte.vydra.transcription.models.TranscriptionPipelineResult
import com.boostlyte.vydra.transcription.models.TranscriptionResponse
import com.boostlyte.vydra.transcription.models.TranscriptionSegment
import com.boostlyte.vydra.utils.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceoverTranscriptionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSupabaseConfig_endpointsAndKeys() {
        assertEquals("https://rqnbumhburadomrdmzhp.supabase.co", SupabaseConfig.SUPABASE_URL)
        assertEquals("sb_publishable_TuNGrwbWqxVwv8G6t6GxdA_H89LLk8G", SupabaseConfig.ANON_KEY)
        assertEquals("transcription-uploads", SupabaseConfig.DEFAULT_STORAGE_BUCKET)
        assertEquals("transcribe-whisper", SupabaseConfig.EDGE_FUNCTION_TRANSCRIBE)
        assertEquals(1800, SupabaseConfig.MAX_AUDIO_DURATION_SECONDS)

        assertEquals(
            "https://rqnbumhburadomrdmzhp.supabase.co/functions/v1/transcribe-whisper",
            SupabaseConfig.getEdgeFunctionUrl("transcribe-whisper")
        )
        assertEquals(
            "https://rqnbumhburadomrdmzhp.supabase.co/storage/v1/object/transcription-uploads/test.m4a",
            SupabaseConfig.getStorageObjectUrl("transcription-uploads", "test.m4a")
        )
        assertEquals(
            "https://rqnbumhburadomrdmzhp.supabase.co/storage/v1/object/sign/transcription-uploads/test.m4a",
            SupabaseConfig.getStorageSignUrl("transcription-uploads", "test.m4a")
        )
    }

    @Test
    fun testTranscribeWhisperRequest_jsonContract() {
        val request = TranscribeWhisperRequest(
            audioUrl = "https://rqnbumhburadomrdmzhp.supabase.co/storage/v1/object/sign/transcription-uploads/voice.m4a?token=xyz",
            audioDurationSeconds = 125
        )
        val json = Gson().toJson(request)
        val jsonObject = JsonParser.parseString(json).asJsonObject

        assertTrue(jsonObject.has("audioUrl"))
        assertTrue(jsonObject.has("audioDurationSeconds"))
        assertEquals(125, jsonObject.get("audioDurationSeconds").asInt)
        assertEquals(
            "https://rqnbumhburadomrdmzhp.supabase.co/storage/v1/object/sign/transcription-uploads/voice.m4a?token=xyz",
            jsonObject.get("audioUrl").asString
        )
    }

    @Test
    fun testPipeline_blocksGracefullyWhenJwtIsMissing() = runBlocking {
        val audioFile = tempFolder.newFile("sample_voice.m4a")
        audioFile.writeBytes(ByteArray(1024) { 1 })

        val pipeline = VoiceoverTranscriptionPipeline(
            context = null,
            jwtProvider = { null } // Explicitly simulate missing JWT
        )

        val result = pipeline.execute(
            audioUri = android.net.DummyUri.fromPath(audioFile.absolutePath),
            audioDurationSeconds = 45,
            projectName = "Project 1"
        )

        assertTrue(result is TranscriptionPipelineResult.BlockedAuth)
        val blockedResult = result as TranscriptionPipelineResult.BlockedAuth
        assertEquals(
            "Transcription blocked: Valid user JWT is not yet available. Authentication UI is pending.",
            blockedResult.message
        )
    }

    @Test
    fun testPipeline_durationExceeding30MinutesRejected() = runBlocking {
        val audioFile = tempFolder.newFile("long_voice.m4a")
        audioFile.writeBytes(ByteArray(1024) { 1 })

        val pipeline = VoiceoverTranscriptionPipeline(
            context = null,
            jwtProvider = { "mock_jwt" }
        )

        val result = pipeline.execute(
            audioUri = android.net.DummyUri.fromPath(audioFile.absolutePath),
            audioDurationSeconds = 1801 // 30 mins + 1 second
        )

        assertTrue(result is TranscriptionPipelineResult.Error)
        val errorResult = result as TranscriptionPipelineResult.Error
        assertEquals(ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG, errorResult.code)
        assertTrue(errorResult.message.contains("30-minute"))
    }

    @Test
    fun testPipeline_zeroOrNegativeDurationRejected() = runBlocking {
        val audioFile = tempFolder.newFile("empty_voice.m4a")
        audioFile.writeBytes(ByteArray(100))

        val pipeline = VoiceoverTranscriptionPipeline(
            context = null,
            jwtProvider = { "mock_jwt" }
        )

        val result = pipeline.execute(
            audioUri = android.net.DummyUri.fromPath(audioFile.absolutePath),
            audioDurationSeconds = 0
        )

        assertTrue(result is TranscriptionPipelineResult.Error)
        val errorResult = result as TranscriptionPipelineResult.Error
        assertEquals(ErrorCode.FILE_NOT_FOUND, errorResult.code)
    }

    @Test
    fun testLocalPersistence_savesRawResponseWithoutAssumingWordTimestamps() {
        val manager = TranscriptionStorageManager()
        val targetDir = tempFolder.newFolder("transcriptions_test")

        // Server response with segments only, NO word-level timestamps
        val rawServerResponse = """
            {
              "text": "Welcome to Vydra video editor.",
              "language": "en",
              "duration": 4.5,
              "segments": [
                {
                  "id": 0,
                  "start": 0.0,
                  "end": 2.1,
                  "text": "Welcome to Vydra"
                },
                {
                  "id": 1,
                  "start": 2.2,
                  "end": 4.5,
                  "text": "video editor."
                }
              ]
            }
        """.trimIndent()

        val parsed = Gson().fromJson(rawServerResponse, TranscriptionResponse::class.java).copy(
            rawJson = rawServerResponse
        )

        val savedFile = manager.saveTranscriptionResult(
            context = null,
            projectName = "My Test Project",
            audioFileName = "voice_123.m4a",
            audioDurationSeconds = 5,
            response = parsed,
            targetDir = targetDir
        )

        assertTrue(savedFile.exists())
        assertTrue(savedFile.length() > 0)

        // Read and verify saved contents
        val savedJson = savedFile.readText()
        val jsonObject = JsonParser.parseString(savedJson).asJsonObject

        assertEquals("My Test Project", jsonObject.get("_project_name").asString)
        assertEquals("voice_123.m4a", jsonObject.get("_audio_file_name").asString)
        assertEquals(5, jsonObject.get("_audio_duration_seconds").asInt)
        assertEquals("Welcome to Vydra video editor.", jsonObject.get("text").asString)

        val segments = jsonObject.getAsJsonArray("segments")
        assertEquals(2, segments.size())
        assertEquals("Welcome to Vydra", segments[0].asJsonObject.get("text").asString)
        assertEquals(0.0, segments[0].asJsonObject.get("start").asDouble, 0.001)
        assertEquals(2.1, segments[0].asJsonObject.get("end").asDouble, 0.001)

        // Verify word timestamps were NOT fabricated
        assertNull(segments[0].asJsonObject.get("words"))
        assertNull(jsonObject.get("words"))

        // Verify security: no tokens stored
        assertFalse(jsonObject.has("jwt"))
        assertFalse(jsonObject.has("apiKey"))
        assertFalse(jsonObject.has("token"))
    }

    @Test
    fun testWhisperProvider_durationLimitValidation() = runBlocking {
        val provider = SupabaseWhisperTranscriptionProvider()
        val result = provider.transcribeAudio(
            signedAudioUrl = "https://example.com/audio.m4a",
            audioDurationSeconds = 1801,
            jwt = "mock_jwt"
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
        assertTrue(ex?.message?.contains("30 minutes") == true)
    }

    @Test
    fun testWhisperProvider_emptyJwtValidation() = runBlocking {
        val provider = SupabaseWhisperTranscriptionProvider()
        val result = provider.transcribeAudio(
            signedAudioUrl = "https://example.com/audio.m4a",
            audioDurationSeconds = 60,
            jwt = ""
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex?.message?.contains("JWT") == true)
    }

    @Test
    fun testAudioDurationCalculation_36SecondsAudioYields36Seconds() {
        // Test audio duration calculation as performed in VideoEditingActivity:
        // durationSecs = ((durationMs + 999) / 1000).toInt().coerceAtLeast(1)
        val durationMs36Exact = 36000L
        val durationSecs36 = ((durationMs36Exact + 999) / 1000).toInt().coerceAtLeast(1)
        assertEquals(36, durationSecs36)

        // 35.1s to 36.0s rounds/ceilings to 36s
        val durationMs35_2 = 35200L
        val durationSecs35_2 = ((durationMs35_2 + 999) / 1000).toInt().coerceAtLeast(1)
        assertEquals(36, durationSecs35_2)

        // Request JSON payload contains exactly 36 seconds
        val request = TranscribeWhisperRequest(
            audioUrl = "https://rqnbumhburadomrdmzhp.supabase.co/storage/v1/object/sign/transcription-uploads/test_36s.m4a?token=xyz",
            audioDurationSeconds = durationSecs36
        )
        val json = Gson().toJson(request)
        val parsedJson = JsonParser.parseString(json).asJsonObject
        assertEquals(36, parsedJson.get("audioDurationSeconds").asInt)
    }

    @Test
    fun testClassifyTranscriptionError_genuineDurationLimitError() {
        // Explicit duration limit violation
        val ex = SupabaseWhisperTranscriptionProvider.TranscriptionApiException(
            statusCode = 400,
            errorBody = """{"error":"Voiceover duration exceeds 30-minute limit","code":"audio_too_long"}""",
            message = "Bad Request from 'transcribe-whisper': {\"error\":\"Voiceover duration exceeds 30-minute limit\",\"code\":\"audio_too_long\"}"
        )

        val (errorCode, message) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(ex)
        assertEquals(ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG, errorCode)
        assertTrue(message.contains("Audio Too Long"))
        assertTrue(message.contains("30 minutes"))
    }

    @Test
    fun testClassifyTranscriptionError_invalidSignedUrlError() {
        // Invalid or expired signed URL error
        val ex = SupabaseWhisperTranscriptionProvider.TranscriptionApiException(
            statusCode = 400,
            errorBody = """{"error":"Failed to download audio from signed URL: expired or invalid signature","code":"invalid_audio_url"}""",
            message = "Bad Request from 'transcribe-whisper': {\"error\":\"Failed to download audio from signed URL: expired or invalid signature\",\"code\":\"invalid_audio_url\"}"
        )

        val (errorCode, message) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(ex)
        assertNotEquals(ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG, errorCode)
        assertEquals(ErrorCode.TRANSCRIPTION_SIGNED_URL_FAILED, errorCode)
        assertEquals("Failed to download audio from signed URL: expired or invalid signature", message)
    }

    @Test
    fun testClassifyTranscriptionError_invalidDurationParameter() {
        // Malformed or negative duration parameter error
        val ex = SupabaseWhisperTranscriptionProvider.TranscriptionApiException(
            statusCode = 400,
            errorBody = """{"error":"Invalid audio duration: duration must be positive"}""",
            message = "Bad Request from 'transcribe-whisper': {\"error\":\"Invalid audio duration: duration must be positive\"}"
        )

        val (errorCode, message) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(ex)
        assertNotEquals(ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG, errorCode)
        assertEquals(ErrorCode.TRANSCRIPTION_API_FAILED, errorCode)
        assertEquals("Invalid audio duration: duration must be positive", message)
    }

    @Test
    fun testClassifyTranscriptionError_genericHttp400BadRequest() {
        // Generic 400 bad request error
        val ex = SupabaseWhisperTranscriptionProvider.TranscriptionApiException(
            statusCode = 400,
            errorBody = """{"error":"Malformed request payload: missing required field 'audioUrl'"}""",
            message = "Bad Request from 'transcribe-whisper': {\"error\":\"Malformed request payload: missing required field 'audioUrl'\"}"
        )

        val (errorCode, message) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(ex)
        assertNotEquals(ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG, errorCode)
        assertEquals(ErrorCode.TRANSCRIPTION_API_FAILED, errorCode)
        assertEquals("Malformed request payload: missing required field 'audioUrl'", message)
    }

    @Test
    fun testClassifyTranscriptionError_providerDescriptiveMessageDoesNotFalseTriggerDuration() {
        // Verify that standard provider 400 error does NOT falsely trigger duration limit
        val ex = SupabaseWhisperTranscriptionProvider.TranscriptionApiException(
            statusCode = 400,
            errorBody = """{"message":"Unsupported audio format or corrupt file"}""",
            message = "Bad Request from 'transcribe-whisper': {\"message\":\"Unsupported audio format or corrupt file\"}"
        )

        val (errorCode, message) = VoiceoverTranscriptionPipeline.classifyTranscriptionError(ex)
        assertNotEquals(ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG, errorCode)
        assertEquals(ErrorCode.TRANSCRIPTION_API_FAILED, errorCode)
        assertEquals("Unsupported audio format or corrupt file", message)
    }
}

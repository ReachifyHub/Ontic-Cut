package com.tharunbirla.librecuts.transcription

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tharunbirla.librecuts.transcription.models.TranscribeWhisperRequest
import com.tharunbirla.librecuts.transcription.models.TranscriptionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Supabase Whisper implementation of [TranscriptionProvider].
 * Connects to the "transcribe-whisper" Supabase Edge Function (version 2)
 * using the project's publishable anon key and user JWT.
 */
class SupabaseWhisperTranscriptionProvider(
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.ANON_KEY,
    private val functionName: String = SupabaseConfig.EDGE_FUNCTION_TRANSCRIBE,
    private val gson: Gson = Gson()
) : TranscriptionProvider {

    private val TAG = "WhisperProvider"

    override suspend fun transcribeAudio(
        signedAudioUrl: String,
        audioDurationSeconds: Int,
        jwt: String
    ): Result<TranscriptionResponse> = withContext(Dispatchers.IO) {
        // Validate audio duration
        if (audioDurationSeconds > SupabaseConfig.MAX_AUDIO_DURATION_SECONDS) {
            val errorMsg = "Audio duration (${audioDurationSeconds}s) exceeds the maximum allowed duration of 30 minutes (1800s)."
            Log.e(TAG, errorMsg)
            return@withContext Result.failure(IllegalArgumentException(errorMsg))
        }

        if (jwt.isBlank()) {
            val errorMsg = "Cannot call Edge Function '$functionName': User JWT is empty or unavailable."
            Log.e(TAG, errorMsg)
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        if (signedAudioUrl.isBlank()) {
            val errorMsg = "Cannot call Edge Function '$functionName': Signed audio URL is empty."
            Log.e(TAG, errorMsg)
            return@withContext Result.failure(IllegalArgumentException(errorMsg))
        }

        val endpointUrl = SupabaseConfig.getEdgeFunctionUrl(functionName)
        var connection: HttpsURLConnection? = null

        try {
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = SupabaseConfig.HTTP_CONNECT_TIMEOUT_MS
                readTimeout = SupabaseConfig.HTTP_READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $jwt")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Content-Type", "application/json")
            }

            // Construct JSON request body: { "audioUrl": "...", "audioDurationSeconds": 123 }
            val requestPayload = TranscribeWhisperRequest(
                audioUrl = signedAudioUrl,
                audioDurationSeconds = audioDurationSeconds
            )
            val jsonString = gson.toJson(requestPayload)

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val responseBody = readStreamSafely(connection.inputStream)
                Log.d(TAG, "Edge Function '$functionName' returned HTTP $statusCode (${responseBody.length} bytes)")

                val parsedResponse = try {
                    gson.fromJson(responseBody, TranscriptionResponse::class.java).copy(
                        rawJson = responseBody
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fully map response to TranscriptionResponse data class, wrapping raw: ${e.message}")
                    TranscriptionResponse(rawJson = responseBody)
                }

                Result.success(parsedResponse)
            } else {
                val errorBody = readStreamSafely(connection.errorStream)
                val descriptiveMessage = when (statusCode) {
                    400 -> "Bad Request from '$functionName' (audio may exceed limit or invalid URL): $errorBody"
                    401 -> "Unauthorized call to '$functionName': User JWT is invalid or expired: $errorBody"
                    402 -> "Payment required / Insufficient credits for transcription: $errorBody"
                    403 -> "Forbidden access to '$functionName': $errorBody"
                    429 -> "Rate limit exceeded while calling '$functionName': $errorBody"
                    500, 502, 503, 504 -> "Server error in '$functionName' Edge Function or NanoGPT proxy: $errorBody"
                    else -> "Edge Function '$functionName' failed with HTTP $statusCode: $errorBody"
                }
                Log.e(TAG, descriptiveMessage)
                Result.failure(
                    TranscriptionApiException(
                        statusCode = statusCode,
                        errorBody = errorBody,
                        message = descriptiveMessage
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network or unexpected exception calling '$functionName': ${e.message}", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readStreamSafely(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    class TranscriptionApiException(
        val statusCode: Int,
        val errorBody: String,
        override val message: String
    ) : Exception(message)
}

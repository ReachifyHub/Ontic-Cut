package com.tharunbirla.librecuts.transcription

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Client for interacting with Supabase Storage REST API.
 * Handles audio upload, signed URL generation, and cleanup deletion.
 */
class SupabaseStorageClient(
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.ANON_KEY,
    private val defaultBucket: String = SupabaseConfig.DEFAULT_STORAGE_BUCKET
) {

    private val TAG = "SupabaseStorageClient"

    /**
     * Uploads a local audio file to the specified Supabase Storage bucket.
     *
     * @param audioFile The local audio file to upload.
     * @param remotePath The destination object path inside the bucket (e.g. "voiceover_123.m4a").
     * @param jwt The authenticated user's Supabase JWT access token.
     * @param mimeType MIME type of the audio file (defaults to "audio/m4a").
     * @param bucket The Supabase Storage bucket name.
     * @return Result containing the remote object path or failure exception.
     */
    fun uploadAudioFile(
        audioFile: File,
        remotePath: String,
        jwt: String,
        mimeType: String = "audio/m4a",
        bucket: String = defaultBucket
    ): Result<String> {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return Result.failure(IllegalArgumentException("Audio file does not exist or is empty: ${audioFile.absolutePath}"))
        }
        if (jwt.isBlank()) {
            return Result.failure(IllegalArgumentException("Cannot upload to Supabase Storage: JWT is empty."))
        }

        val targetUrl = SupabaseConfig.getStorageObjectUrl(bucket, remotePath)
        var connection: HttpsURLConnection? = null
        return try {
            val url = URL(targetUrl)
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = SupabaseConfig.HTTP_CONNECT_TIMEOUT_MS
                readTimeout = SupabaseConfig.HTTP_READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $jwt")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Content-Type", mimeType)
                setRequestProperty("x-upsert", "true")
                setFixedLengthStreamingMode(audioFile.length())
            }

            // Stream file contents directly without full buffering in memory
            FileInputStream(audioFile).use { input ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                Log.d(TAG, "Audio successfully uploaded to $bucket/$remotePath (HTTP $statusCode)")
                Result.success(remotePath)
            } else {
                val errorBody = readStreamSafely(connection.errorStream)
                Log.e(TAG, "Storage upload failed: HTTP $statusCode - $errorBody")
                Result.failure(
                    HttpException(
                        statusCode = statusCode,
                        responseBody = errorBody,
                        message = "Storage upload failed (HTTP $statusCode): $errorBody"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during audio upload to Supabase Storage: ${e.message}", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Creates a temporary HTTPS signed URL for an uploaded file in Supabase Storage.
     * The Edge Function requires an HTTPS signed URL pointing to Supabase Storage.
     *
     * @param remotePath The path of the object in the bucket.
     * @param jwt The authenticated user's Supabase JWT access token.
     * @param expiresInSeconds Duration for the signed URL to remain valid (default 3600 = 1 hour).
     * @param bucket The Supabase Storage bucket name.
     * @return Result containing the complete, absolute HTTPS signed URL.
     */
    fun createSignedUrl(
        remotePath: String,
        jwt: String,
        expiresInSeconds: Int = 3600,
        bucket: String = defaultBucket
    ): Result<String> {
        if (jwt.isBlank()) {
            return Result.failure(IllegalArgumentException("Cannot create signed URL: JWT is empty."))
        }

        val targetUrl = SupabaseConfig.getStorageSignUrl(bucket, remotePath)
        var connection: HttpsURLConnection? = null
        return try {
            val url = URL(targetUrl)
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

            val jsonPayload = JsonObject().apply {
                addProperty("expiresIn", expiresInSeconds)
            }.toString()

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val responseBody = readStreamSafely(connection.inputStream)
                val jsonObject = JsonParser.parseString(responseBody).asJsonObject
                
                // Supabase returns {"signedURL": "/storage/v1/object/sign/..."} or {"signedUrl": "..."}
                val rawSignedUrl = when {
                    jsonObject.has("signedURL") -> jsonObject.get("signedURL").asString
                    jsonObject.has("signedUrl") -> jsonObject.get("signedUrl").asString
                    jsonObject.has("url") -> jsonObject.get("url").asString
                    else -> null
                }

                if (rawSignedUrl.isNullOrEmpty()) {
                    Result.failure(IllegalStateException("No signedURL field found in response: $responseBody"))
                } else {
                    val fullUrl = buildFullSignedUrl(rawSignedUrl)
                    Log.d(TAG, "Signed URL successfully generated: $fullUrl")
                    Result.success(fullUrl)
                }
            } else {
                val errorBody = readStreamSafely(connection.errorStream)
                Log.e(TAG, "Signed URL creation failed: HTTP $statusCode - $errorBody")
                Result.failure(
                    HttpException(
                        statusCode = statusCode,
                        responseBody = errorBody,
                        message = "Signed URL creation failed (HTTP $statusCode): $errorBody"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating signed URL: ${e.message}", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Deletes an audio file from the Supabase Storage bucket after transcription.
     * Recommended to clean up temporary audio files.
     */
    fun deleteAudioFile(
        remotePath: String,
        jwt: String,
        bucket: String = defaultBucket
    ): Result<Boolean> {
        if (jwt.isBlank()) {
            return Result.failure(IllegalArgumentException("Cannot delete audio from storage: JWT is empty."))
        }

        val targetUrl = SupabaseConfig.getStorageObjectUrl(bucket, remotePath)
        var connection: HttpsURLConnection? = null
        return try {
            val url = URL(targetUrl)
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "DELETE"
                useCaches = false
                connectTimeout = SupabaseConfig.HTTP_CONNECT_TIMEOUT_MS
                readTimeout = SupabaseConfig.HTTP_READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $jwt")
                setRequestProperty("apikey", anonKey)
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                Log.d(TAG, "Deleted cloud audio file: $bucket/$remotePath (HTTP $statusCode)")
                Result.success(true)
            } else {
                val errorBody = readStreamSafely(connection.errorStream)
                Log.w(TAG, "Storage deletion returned HTTP $statusCode: $errorBody")
                Result.failure(HttpException(statusCode, errorBody, "Storage deletion failed (HTTP $statusCode)"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during cleanup deletion: ${e.message}")
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Resolves a relative Supabase Storage path into a full HTTPS URL.
     */
    private fun buildFullSignedUrl(rawUrl: String): String {
        return when {
            rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true) -> {
                rawUrl
            }
            rawUrl.startsWith("/storage/v1/") -> {
                "$supabaseUrl$rawUrl"
            }
            rawUrl.startsWith("/") -> {
                "$supabaseUrl$rawUrl"
            }
            else -> {
                "$supabaseUrl/$rawUrl"
            }
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

    class HttpException(
        val statusCode: Int,
        val responseBody: String,
        override val message: String
    ) : Exception(message)
}

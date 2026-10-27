package com.boostlyte.vydra.transcription

import android.content.Context
import android.net.Uri
import android.util.Log
import com.boostlyte.vydra.transcription.models.TranscriptionPipelineResult
import com.boostlyte.vydra.utils.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Orchestrates the 8-step voiceover transcription workflow.
 *
 * Steps in order:
 * 1. Accept local voiceover audio URI and its duration in seconds.
 * 2. Check for a valid Supabase user JWT.
 *    IF UNAVAILABLE: Log "Transcription blocked: Valid user JWT is not yet available. Authentication UI is pending."
 *    and terminate gracefully.
 * 3. Upload local audio file to Supabase Storage bucket ("transcription-uploads").
 * 4. Generate temporary signed HTTPS URL.
 * 5. Call "transcribe-whisper" Edge Function.
 * 6. Parse response.
 * 7. Save transcription result locally without altering timestamps or storing keys.
 * 8. Delete uploaded audio file from Supabase Storage.
 */
class VoiceoverTranscriptionPipeline(
    private val context: Context?,
    private val storageClient: SupabaseStorageClient = SupabaseStorageClient(),
    private val transcriptionProvider: TranscriptionProvider = SupabaseWhisperTranscriptionProvider(),
    private val storageManager: TranscriptionStorageManager = TranscriptionStorageManager(),
    private val jwtProvider: (() -> String?)? = null
) {

    private val TAG = "VoiceoverPipeline"

    companion object {
        const val BLOCKED_AUTH_MESSAGE = "Transcription blocked: Valid user JWT is not yet available. Authentication UI is pending."

        internal data class BackendErrorInfo(
            val parsedMessage: String?,
            val errorCode: String?,
            val isExplicitDurationLimit: Boolean,
            val isInvalidDuration: Boolean,
            val isSignedUrlError: Boolean,
            val isCreditError: Boolean,
            val isAuthError: Boolean
        )

        internal fun parseBackendError(errorBody: String, rawMessage: String): BackendErrorInfo {
            var extractedMsg: String? = null
            var extractedCode: String? = null

            if (errorBody.isNotBlank()) {
                try {
                    if (errorBody.contains("{")) {
                        val jsonStart = errorBody.indexOf("{")
                        val jsonEnd = errorBody.lastIndexOf("}")
                        if (jsonStart in 0 until jsonEnd) {
                            val jsonStr = errorBody.substring(jsonStart, jsonEnd + 1)
                            val json = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
                            extractedMsg = json.get("error")?.asString
                                ?: json.get("message")?.asString
                                ?: json.get("msg")?.asString
                                ?: json.get("details")?.asString
                            extractedCode = json.get("code")?.asString
                                ?: json.get("error_code")?.asString
                        }
                    }
                } catch (_: Exception) {}

                if (extractedMsg.isNullOrBlank()) {
                    extractedMsg = errorBody.trim().takeIf { it.isNotBlank() && it.length < 300 }
                }
            }

            // Only examine the backend payload, or fallback to rawMessage if errorBody was empty
            val textToInspect = (extractedMsg ?: errorBody.ifBlank { rawMessage }).lowercase()
            val codeToInspect = (extractedCode ?: "").lowercase()

            // 1. Explicit duration limit violation
            // Note: Must be an EXPLICIT limit violation (e.g. exceeds 30 minutes, audio payload too large, duration limit exceeded).
            // It MUST NOT match "invalid duration" or "duration must be positive" or "rate limit" or "credit limit".
            val isInvalidDuration = textToInspect.contains("invalid duration") ||
                    textToInspect.contains("duration must be") ||
                    textToInspect.contains("duration cannot") ||
                    textToInspect.contains("duration <= 0") ||
                    textToInspect.contains("negative duration")

            val isDurationLimitExceeded = codeToInspect == "audio_too_long" ||
                    codeToInspect == "duration_limit_exceeded" ||
                    codeToInspect == "audio_payload_too_large" ||
                    (textToInspect.contains("duration") && (textToInspect.contains("exceed") || textToInspect.contains("too long") || textToInspect.contains("maximum") || textToInspect.contains("30 min") || textToInspect.contains("30-min"))) ||
                    (textToInspect.contains("audio") && (textToInspect.contains("too long") || textToInspect.contains("too large"))) ||
                    textToInspect.contains("exceeds the 30-minute") ||
                    textToInspect.contains("exceeds maximum allowed duration")

            val isExplicitDurationLimit = isDurationLimitExceeded && !isInvalidDuration

            // 2. Signed URL / storage access error
            val isSignedUrlError = codeToInspect.contains("signed_url") ||
                    codeToInspect == "invalid_audio_url" ||
                    codeToInspect == "invalid_url" ||
                    textToInspect.contains("signed url") ||
                    textToInspect.contains("signed_url") ||
                    textToInspect.contains("download audio") ||
                    textToInspect.contains("fetch audio") ||
                    textToInspect.contains("could not access audio") ||
                    textToInspect.contains("audio file not found") ||
                    textToInspect.contains("invalid signed") ||
                    textToInspect.contains("expired url") ||
                    (textToInspect.contains("invalid url") && !textToInspect.contains("missing"))

            // 3. Credit error
            val isCreditError = textToInspect.contains("credit") ||
                    textToInspect.contains("balance") ||
                    textToInspect.contains("payment") ||
                    codeToInspect.contains("credit")

            // 4. Auth error
            val isAuthError = textToInspect.contains("unauthorized") ||
                    textToInspect.contains("jwt") ||
                    textToInspect.contains("bearer") ||
                    textToInspect.contains("forbidden") ||
                    textToInspect.contains("token") ||
                    codeToInspect.contains("auth")

            return BackendErrorInfo(
                parsedMessage = extractedMsg,
                errorCode = extractedCode,
                isExplicitDurationLimit = isExplicitDurationLimit,
                isInvalidDuration = isInvalidDuration,
                isSignedUrlError = isSignedUrlError,
                isCreditError = isCreditError,
                isAuthError = isAuthError
            )
        }

        fun classifyTranscriptionError(error: Throwable?, statusCode: Int? = null): Pair<ErrorCode, String> {
            val apiException = error as? SupabaseWhisperTranscriptionProvider.TranscriptionApiException
            val actualStatusCode = statusCode ?: apiException?.statusCode
            val rawErrorBody = apiException?.errorBody ?: ""
            val rawMsg = error?.message ?: "Whisper transcription failed"

            val info = parseBackendError(rawErrorBody, rawMsg)
            val backendMsg = info.parsedMessage

            return when {
                // 1. Auth errors (401, 403, or auth messages)
                actualStatusCode == 401 || actualStatusCode == 403 || info.isAuthError -> {
                    Pair(ErrorCode.TRANSCRIPTION_AUTH_MISSING, backendMsg ?: "Authentication Error: Please sign in or re-authenticate.")
                }
                // 2. Credit errors (402 or credit messages)
                actualStatusCode == 402 || info.isCreditError -> {
                    Pair(ErrorCode.TRANSCRIPTION_API_FAILED, backendMsg ?: "Insufficient Credits: You do not have enough credits to complete this transcription.")
                }
                // 3. Explicit duration limit violation (HTTP 400 + explicit duration limit exceeded)
                actualStatusCode == 400 && info.isExplicitDurationLimit -> {
                    Pair(ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG, "Audio Too Long: Voiceover exceeds maximum allowed duration of 30 minutes.")
                }
                // 4. Signed URL error (HTTP 400 + audio URL / storage download issue)
                actualStatusCode == 400 && info.isSignedUrlError -> {
                    val cleanMsg = backendMsg ?: "Audio File Error: Could not access audio from cloud storage for transcription."
                    Pair(ErrorCode.TRANSCRIPTION_SIGNED_URL_FAILED, cleanMsg)
                }
                // 5. Invalid duration parameter (HTTP 400 + duration <= 0 or invalid duration)
                actualStatusCode == 400 && info.isInvalidDuration -> {
                    val cleanMsg = backendMsg ?: "Invalid Audio Duration: The audio duration must be greater than 0."
                    Pair(ErrorCode.TRANSCRIPTION_API_FAILED, cleanMsg)
                }
                // 6. Generic HTTP 400 Bad Request
                actualStatusCode == 400 -> {
                    val cleanMsg = backendMsg ?: "Bad Request: Unable to process audio transcription. Please verify the audio file format."
                    Pair(ErrorCode.TRANSCRIPTION_API_FAILED, cleanMsg)
                }
                // 7. Server error (5xx)
                actualStatusCode != null && actualStatusCode in 500..599 -> {
                    val serverMsg = backendMsg ?: "The transcription service encountered an internal error. Please try again later."
                    Pair(ErrorCode.TRANSCRIPTION_API_FAILED, "Server Error (HTTP $actualStatusCode): $serverMsg")
                }
                // 8. Timeout
                error is java.net.SocketTimeoutException -> {
                    Pair(ErrorCode.TRANSCRIPTION_NETWORK_ERROR, "Connection Timed Out: The transcription server took too long to respond. Please try again.")
                }
                // 9. Network connectivity
                error is java.net.UnknownHostException || error is java.net.ConnectException -> {
                    Pair(ErrorCode.TRANSCRIPTION_NETWORK_ERROR, "No Internet Connection: Please check your network connection and try again.")
                }
                // 10. Fallback
                else -> {
                    Pair(ErrorCode.TRANSCRIPTION_API_FAILED, backendMsg ?: rawMsg)
                }
            }
        }
    }

    suspend fun execute(
        audioUri: Uri,
        audioDurationSeconds: Int,
        projectName: String? = null,
        onProgress: (progress: Int, statusMessage: String) -> Unit = { _, _ -> }
    ): TranscriptionPipelineResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting voiceover transcription pipeline - duration: ${audioDurationSeconds}s, URI scheme: ${audioUri.scheme}")
        onProgress(5, "Validating voiceover...")

        // Step 1: Validate duration and resolve local audio file
        if (audioDurationSeconds <= 0) {
            val msg = "Audio duration must be greater than 0 seconds."
            Log.e(TAG, msg)
            return@withContext TranscriptionPipelineResult.Error(
                code = ErrorCode.FILE_NOT_FOUND,
                message = msg
            )
        }

        if (audioDurationSeconds > SupabaseConfig.MAX_AUDIO_DURATION_SECONDS) {
            val msg = "Voiceover duration (${audioDurationSeconds}s) exceeds the 30-minute maximum limit."
            Log.e(TAG, msg)
            return@withContext TranscriptionPipelineResult.Error(
                code = ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG,
                message = msg
            )
        }

        val localAudioFile = resolveLocalFile(audioUri)
        if (localAudioFile == null || !localAudioFile.exists() || localAudioFile.length() == 0L) {
            val msg = "Failed to resolve local audio file from URI: $audioUri"
            Log.e(TAG, msg)
            return@withContext TranscriptionPipelineResult.Error(
                code = ErrorCode.FILE_NOT_FOUND,
                message = msg
            )
        }

        // Step 2: Check for a valid Supabase user JWT (with auto-refresh)
        var userJwt = jwtProvider?.invoke() ?: (context?.let { SupabaseAuthManager.getValidAccessToken(it) })
        if (userJwt.isNullOrBlank()) {
            Log.w(TAG, BLOCKED_AUTH_MESSAGE)
            return@withContext TranscriptionPipelineResult.BlockedAuth(BLOCKED_AUTH_MESSAGE)
        }

        // Generate unique cloud filename for storage isolation
        val ext = localAudioFile.extension.ifEmpty { "m4a" }
        val remoteFileName = "voiceover_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext"
        val mimeType = when (ext.lowercase()) {
            "m4a", "mp4" -> "audio/m4a"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            else -> "audio/*"
        }

        var hasRetriedAuth = false

        // Step 3: Upload local audio file to Supabase Storage bucket ("transcription-uploads")
        onProgress(20, "Uploading voiceover to secure cloud storage...")
        var uploadResult = storageClient.uploadAudioFile(
            audioFile = localAudioFile,
            remotePath = remoteFileName,
            jwt = userJwt,
            mimeType = mimeType
        )

        // Check for 401 on upload and retry once with refreshed token
        if (uploadResult.isFailure && context != null && !hasRetriedAuth) {
            val error = uploadResult.exceptionOrNull()
            val statusCode = (error as? SupabaseStorageClient.HttpException)?.statusCode
            if (statusCode == 401) {
                Log.i(TAG, "Storage upload returned 401. Refreshing token and retrying...")
                hasRetriedAuth = true
                val refreshedJwt = SupabaseAuthManager.getValidAccessToken(context, forceRefresh = true)
                if (!refreshedJwt.isNullOrBlank()) {
                    userJwt = refreshedJwt
                    uploadResult = storageClient.uploadAudioFile(
                        audioFile = localAudioFile,
                        remotePath = remoteFileName,
                        jwt = userJwt,
                        mimeType = mimeType
                    )
                }
            }
        }

        if (uploadResult.isFailure) {
            val error = uploadResult.exceptionOrNull()
            val msg = error?.message ?: "Unknown storage upload failure"
            Log.e(TAG, "Step 3 failed - Storage upload error: $msg", error)
            return@withContext TranscriptionPipelineResult.Error(
                code = ErrorCode.TRANSCRIPTION_UPLOAD_FAILED,
                message = "Failed to upload audio to Supabase Storage: $msg",
                details = msg
            )
        }

        // Step 4: Generate temporary signed HTTPS URL for the uploaded audio file
        onProgress(45, "Generating secure media access URL...")
        var signedUrlResult = storageClient.createSignedUrl(
            remotePath = remoteFileName,
            jwt = userJwt,
            expiresInSeconds = 3600
        )

        // Check for 401 on signed URL and retry once with refreshed token
        if (signedUrlResult.isFailure && context != null && !hasRetriedAuth) {
            val error = signedUrlResult.exceptionOrNull()
            val statusCode = (error as? SupabaseStorageClient.HttpException)?.statusCode
            if (statusCode == 401) {
                Log.i(TAG, "Signed URL returned 401. Refreshing token and retrying...")
                hasRetriedAuth = true
                val refreshedJwt = SupabaseAuthManager.getValidAccessToken(context, forceRefresh = true)
                if (!refreshedJwt.isNullOrBlank()) {
                    userJwt = refreshedJwt
                    signedUrlResult = storageClient.createSignedUrl(
                        remotePath = remoteFileName,
                        jwt = userJwt,
                        expiresInSeconds = 3600
                    )
                }
            }
        }

        if (signedUrlResult.isFailure) {
            val error = signedUrlResult.exceptionOrNull()
            val msg = error?.message ?: "Unknown signed URL generation failure"
            Log.e(TAG, "Step 4 failed - Signed URL error: $msg", error)
            // Attempt cleanup of uploaded file
            storageClient.deleteAudioFile(remoteFileName, userJwt)
            return@withContext TranscriptionPipelineResult.Error(
                code = ErrorCode.TRANSCRIPTION_SIGNED_URL_FAILED,
                message = "Failed to generate signed URL: $msg",
                details = msg
            )
        }

        val signedAudioUrl = signedUrlResult.getOrThrow()

        // Step 5: Call "transcribe-whisper" Edge Function with signed URL and duration
        Log.i(TAG, "Calling transcription provider with duration: ${audioDurationSeconds}s")
        onProgress(65, "Transcribing voiceover with Whisper AI...")
        var transcribeResult = transcriptionProvider.transcribeAudio(
            signedAudioUrl = signedAudioUrl,
            audioDurationSeconds = audioDurationSeconds,
            jwt = userJwt
        )

        // Check for 401 on Edge Function and retry once with refreshed token
        if (transcribeResult.isFailure && context != null && !hasRetriedAuth) {
            val error = transcribeResult.exceptionOrNull()
            val statusCode = (error as? SupabaseWhisperTranscriptionProvider.TranscriptionApiException)?.statusCode
            if (statusCode == 401) {
                Log.i(TAG, "Edge Function returned 401. Refreshing token and retrying...")
                hasRetriedAuth = true
                val refreshedJwt = SupabaseAuthManager.getValidAccessToken(context, forceRefresh = true)
                if (!refreshedJwt.isNullOrBlank()) {
                    userJwt = refreshedJwt
                    transcribeResult = transcriptionProvider.transcribeAudio(
                        signedAudioUrl = signedAudioUrl,
                        audioDurationSeconds = audioDurationSeconds,
                        jwt = userJwt
                    )
                }
            }
        }

        if (transcribeResult.isFailure) {
            val error = transcribeResult.exceptionOrNull()
            val msg = error?.message ?: "Whisper transcription failed"
            val apiException = error as? SupabaseWhisperTranscriptionProvider.TranscriptionApiException
            val statusCode = apiException?.statusCode
            val rawErrorBody = apiException?.errorBody ?: ""
            Log.e(TAG, "Step 5 failed - Transcription Edge Function error. Duration: ${audioDurationSeconds}s, HTTP status: $statusCode, backendError: $rawErrorBody", error)
            
            // Clean up uploaded audio file from storage
            storageClient.deleteAudioFile(remoteFileName, userJwt)

            val (errorCode, userMsg) = classifyTranscriptionError(error, statusCode)

            return@withContext TranscriptionPipelineResult.Error(
                code = errorCode,
                message = userMsg,
                httpStatusCode = statusCode,
                details = msg
            )
        }

        val transcriptionResponse = transcribeResult.getOrThrow()

        // Step 6: Parse the response (already done inside transcribeAudio)
        onProgress(85, "Processing transcription results...")

        // Step 7: Save transcription result locally without altering timestamps
        onProgress(90, "Saving transcription to project...")
        val savedFile = try {
            storageManager.saveTranscriptionResult(
                context = context,
                projectName = projectName,
                audioFileName = localAudioFile.name,
                audioDurationSeconds = audioDurationSeconds,
                response = transcriptionResponse,
                audioUri = audioUri.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Step 7 failed - Local saving error: ${e.message}", e)
            return@withContext TranscriptionPipelineResult.Error(
                code = ErrorCode.UNEXPECTED_CRASH,
                message = "Failed to save transcription locally: ${e.message}",
                details = e.message
            )
        }

        // Step 8: (Optional but recommended) Delete audio file from Supabase Storage
        onProgress(95, "Cleaning up temporary cloud storage...")
        try {
            storageClient.deleteAudioFile(remoteFileName, userJwt)
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal: Failed to delete temporary audio file $remoteFileName: ${e.message}")
        }

        onProgress(100, "Transcription complete!")
        Log.i(TAG, "Voiceover transcription successfully completed. Saved to: ${savedFile.absolutePath}")

        return@withContext TranscriptionPipelineResult.Success(
            response = transcriptionResponse,
            localSavedFile = savedFile,
            text = transcriptionResponse.getFullText(),
            durationSeconds = audioDurationSeconds
        )
    }

    /**
     * Resolves a Uri to a concrete local File, copying to cache if necessary for content:// URIs.
     */
    private fun resolveLocalFile(uri: Uri): File? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                File(path)
            } else {
                val ctx = context ?: return null
                // Copy content:// stream to temporary cache file
                val tempDir = File(ctx.cacheDir, "transcription_temp")
                if (!tempDir.exists()) tempDir.mkdirs()
                val tempFile = File(tempDir, "temp_voice_${System.currentTimeMillis()}.m4a")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving audio file from URI $uri: ${e.message}", e)
            null
        }
    }
}

package com.tharunbirla.librecuts.transcription

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tharunbirla.librecuts.transcription.models.TranscriptionPipelineResult
import com.tharunbirla.librecuts.utils.ErrorCode
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
    }

    suspend fun execute(
        audioUri: Uri,
        audioDurationSeconds: Int,
        projectName: String? = null,
        onProgress: (progress: Int, statusMessage: String) -> Unit = { _, _ -> }
    ): TranscriptionPipelineResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting voiceover transcription pipeline for URI: $audioUri (duration: ${audioDurationSeconds}s)")
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

        // Step 2: Check for a valid Supabase user JWT
        val userJwt = jwtProvider?.invoke() ?: (context?.let { SupabaseAuthManager.getCurrentUserJwt(it) })
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

        // Step 3: Upload local audio file to Supabase Storage bucket ("transcription-uploads")
        onProgress(20, "Uploading voiceover to secure cloud storage...")
        val uploadResult = storageClient.uploadAudioFile(
            audioFile = localAudioFile,
            remotePath = remoteFileName,
            jwt = userJwt,
            mimeType = mimeType
        )

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
        val signedUrlResult = storageClient.createSignedUrl(
            remotePath = remoteFileName,
            jwt = userJwt,
            expiresInSeconds = 3600
        )

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
        onProgress(65, "Transcribing voiceover with Whisper AI...")
        val transcribeResult = transcriptionProvider.transcribeAudio(
            signedAudioUrl = signedAudioUrl,
            audioDurationSeconds = audioDurationSeconds,
            jwt = userJwt
        )

        if (transcribeResult.isFailure) {
            val error = transcribeResult.exceptionOrNull()
            val msg = error?.message ?: "Whisper transcription failed"
            val statusCode = (error as? SupabaseWhisperTranscriptionProvider.TranscriptionApiException)?.statusCode
            Log.e(TAG, "Step 5 failed - Transcription Edge Function error ($statusCode): $msg", error)
            
            // Clean up uploaded audio file from storage
            storageClient.deleteAudioFile(remoteFileName, userJwt)

            val errorCode = when (statusCode) {
                401 -> ErrorCode.TRANSCRIPTION_AUTH_MISSING
                402 -> ErrorCode.TRANSCRIPTION_API_FAILED
                400 -> ErrorCode.TRANSCRIPTION_AUDIO_TOO_LONG
                else -> ErrorCode.TRANSCRIPTION_API_FAILED
            }

            return@withContext TranscriptionPipelineResult.Error(
                code = errorCode,
                message = msg,
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
                response = transcriptionResponse
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

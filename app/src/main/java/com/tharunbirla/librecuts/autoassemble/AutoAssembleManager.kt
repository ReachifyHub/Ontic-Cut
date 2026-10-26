package com.tharunbirla.librecuts.autoassemble

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tharunbirla.librecuts.R
import com.tharunbirla.librecuts.models.SceneBoundary
import com.tharunbirla.librecuts.transcription.SupabaseAuthManager
import com.tharunbirla.librecuts.transcription.TranscriptionStorageManager
import com.tharunbirla.librecuts.transcription.VoiceoverTranscriptionPipeline
import com.tharunbirla.librecuts.transcription.models.TranscriptionPipelineResult
import com.tharunbirla.librecuts.transcription.models.TranscriptionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class PreconditionCheckResult(
    val isValid: Boolean,
    val hasVisuals: Boolean,
    val hasAudio: Boolean,
    val errorMessage: String? = null
)

sealed class AutoAssembleResult {
    data class Success(
        val boundaries: List<SceneBoundary>,
        val transcriptionResponse: TranscriptionResponse,
        val wasCached: Boolean
    ) : AutoAssembleResult()

    data class BlockedAuth(val message: String) : AutoAssembleResult()

    data class Error(
        val message: String,
        val isCreditError: Boolean = false,
        val details: String? = null
    ) : AutoAssembleResult()
}

class AutoAssembleManager(
    private val context: Context,
    private val storageManager: TranscriptionStorageManager = TranscriptionStorageManager(),
    private val pipeline: VoiceoverTranscriptionPipeline = VoiceoverTranscriptionPipeline(context),
    private val gson: Gson = Gson()
) {

    private val TAG = "AutoAssembleManager"

    /**
     * Validates that the timeline satisfies all prerequisites for Auto Assemble:
     * - At least one visual asset (image or video).
     * - At least one audio track (voiceover).
     */
    fun validatePreconditions(hasVisuals: Boolean, hasAudio: Boolean): PreconditionCheckResult {
        return when {
            !hasVisuals && !hasAudio -> PreconditionCheckResult(
                isValid = false,
                hasVisuals = false,
                hasAudio = false,
                errorMessage = context.getString(R.string.str_auto_assemble_prerequisite_missing)
            )
            !hasVisuals -> PreconditionCheckResult(
                isValid = false,
                hasVisuals = false,
                hasAudio = true,
                errorMessage = context.getString(R.string.str_auto_assemble_visuals_missing)
            )
            !hasAudio -> PreconditionCheckResult(
                isValid = false,
                hasVisuals = true,
                hasAudio = false,
                errorMessage = context.getString(R.string.str_auto_assemble_audio_missing)
            )
            else -> PreconditionCheckResult(
                isValid = true,
                hasVisuals = true,
                hasAudio = true
            )
        }
    }

    /**
     * Checks if a valid local transcription JSON file already exists for this exact audio file.
     * Matches by audio file name, URI string, or dedicated metadata properties.
     */
    fun findCachedTranscription(audioUri: Uri, audioFileName: String? = null): Pair<TranscriptionResponse, File>? {
        return try {
            val transDir = storageManager.getTranscriptionsDirectory(context)
            val jsonFiles = transDir.listFiles { file ->
                file.isFile && file.name.endsWith(".json", ignoreCase = true)
            }?.sortedByDescending { it.lastModified() } ?: return null

            val targetFileName = audioFileName
                ?: (if (audioUri.scheme == "file") File(audioUri.path ?: "").name else audioUri.lastPathSegment)

            for (file in jsonFiles) {
                try {
                    val content = file.readText()
                    if (content.isBlank()) continue

                    val jsonElement = JsonParser.parseString(content)
                    if (!jsonElement.isJsonObject) continue
                    val root = jsonElement.asJsonObject

                    val savedAudioName = root.get("_audio_file_name")?.asString
                    val savedAudioUri = root.get("_audio_uri")?.asString

                    val matchesAudio = when {
                        !savedAudioUri.isNullOrEmpty() && savedAudioUri == audioUri.toString() -> true
                        !targetFileName.isNullOrEmpty() && savedAudioName.equals(targetFileName, ignoreCase = true) -> true
                        !targetFileName.isNullOrEmpty() && file.name.contains(targetFileName.substringBeforeLast(".")) -> true
                        else -> false
                    }

                    if (matchesAudio) {
                        val parsed = gson.fromJson(content, TranscriptionResponse::class.java).copy(
                            rawJson = content
                        )
                        // Verify valid transcription payload (has text or segments)
                        if (!parsed.text.isNullOrBlank() || !parsed.segments.isNullOrEmpty()) {
                            Log.i(TAG, "Reusing valid cached transcription from: ${file.absolutePath}")
                            return Pair(parsed, file)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking candidate transcription file ${file.name}: ${e.message}")
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error searching cached transcriptions: ${e.message}", e)
            null
        }
    }

    /**
     * Parses the script map, resolves or executes transcription, and fuzzy matches scene timestamps.
     */
    suspend fun executeAutoAssemble(
        rawScriptMap: String,
        audioUri: Uri,
        audioDurationSeconds: Int,
        projectName: String? = null,
        onProgress: (progress: Int, statusMessage: String) -> Unit = { _, _ -> }
    ): AutoAssembleResult = withContext(Dispatchers.IO) {
        val parsedScenes = ScriptMapParser.parse(rawScriptMap)
        if (parsedScenes.isEmpty()) {
            return@withContext AutoAssembleResult.Error(
                message = context.getString(R.string.str_empty_script_error)
            )
        }

        // 1. Check for existing local transcription (Reuse logic - zero network / zero credit cost)
        onProgress(5, "Checking for cached transcription...")
        val cached = findCachedTranscription(audioUri)
        if (cached != null) {
            onProgress(60, "Loaded cached transcription. Aligning scenes...")
            val boundaries = SceneFuzzyMatcher.matchScenes(parsedScenes, cached.first)
            onProgress(100, "Scenes calculated successfully!")
            return@withContext AutoAssembleResult.Success(
                boundaries = boundaries,
                transcriptionResponse = cached.first,
                wasCached = true
            )
        }

        // 2. Local transcription does not exist -> check JWT authentication before calling backend
        if (!SupabaseAuthManager.hasValidJwt(context)) {
            Log.w(TAG, "Transcription blocked: User JWT is missing")
            return@withContext AutoAssembleResult.BlockedAuth(
                context.getString(R.string.str_sign_in_required_auto_assemble)
            )
        }

        // 3. Trigger Phase 3 pipeline
        onProgress(10, "Uploading voiceover for transcription...")
        val pipelineResult = pipeline.execute(
            audioUri = audioUri,
            audioDurationSeconds = audioDurationSeconds,
            projectName = projectName,
            onProgress = onProgress
        )

        when (pipelineResult) {
            is TranscriptionPipelineResult.Success -> {
                onProgress(90, "Transcribed! Aligning scene boundaries...")
                val boundaries = SceneFuzzyMatcher.matchScenes(parsedScenes, pipelineResult.response)
                onProgress(100, "Scenes calculated successfully!")
                AutoAssembleResult.Success(
                    boundaries = boundaries,
                    transcriptionResponse = pipelineResult.response,
                    wasCached = false
                )
            }
            is TranscriptionPipelineResult.BlockedAuth -> {
                Log.w(TAG, "Transcription pipeline blocked due to missing auth")
                AutoAssembleResult.BlockedAuth(
                    context.getString(R.string.str_sign_in_required_auto_assemble)
                )
            }
            is TranscriptionPipelineResult.Error -> {
                Log.e(TAG, "Transcription pipeline error: ${pipelineResult.message}")
                val creditError = extractCreditErrorMessage(pipelineResult.message, pipelineResult.details, pipelineResult.httpStatusCode)
                if (creditError != null) {
                    AutoAssembleResult.Error(
                        message = creditError,
                        isCreditError = true,
                        details = pipelineResult.details
                    )
                } else {
                    AutoAssembleResult.Error(
                        message = pipelineResult.message,
                        isCreditError = false,
                        details = pipelineResult.details
                    )
                }
            }
        }
    }

    /**
     * Extracts exact credit error returned by backend or formats it cleanly.
     */
    fun extractCreditErrorMessage(message: String, details: String?, statusCode: Int?): String? {
        if (statusCode == 402 || message.contains("credit", ignoreCase = true) || (details?.contains("credit", ignoreCase = true) == true)) {
            // Check if details contains JSON like {"error":"Not enough credits..."}
            val textToInspect = details ?: message
            try {
                if (textToInspect.contains("{")) {
                    val jsonStart = textToInspect.indexOf("{")
                    val jsonEnd = textToInspect.lastIndexOf("}")
                    if (jsonStart in 0 until jsonEnd) {
                        val jsonStr = textToInspect.substring(jsonStart, jsonEnd + 1)
                        val parsed = JsonParser.parseString(jsonStr).asJsonObject
                        val err = parsed.get("error")?.asString ?: parsed.get("message")?.asString
                        if (!err.isNullOrBlank()) return err
                    }
                }
            } catch (e: Exception) {
                // Ignore json parse error, fall back to regex or message
            }

            // Extract pattern: "Not enough credits. Required: X, Available: Y" or similar
            val regex = Regex("""Not enough credits[^\n\r]*|Insufficient credits[^\n\r]*""", RegexOption.IGNORE_CASE)
            val match = regex.find(textToInspect)
            if (match != null) {
                return match.value.trim()
            }

            return message
        }
        return null
    }
}

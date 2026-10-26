package com.boostlyte.vydra.transcription

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.boostlyte.vydra.transcription.models.TranscriptionResponse
import com.boostlyte.vydra.utils.ProjectManager
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Manages local persistence of transcription results in the app's dedicated project directory.
 *
 * Requirements:
 * - Saves raw response structure without alteration (segments, words, text).
 * - Never assumes or fakes word-level timestamps if only segments are returned.
 * - Never saves JWT or any API keys.
 */
class TranscriptionStorageManager {

    private val TAG = "TranscriptionStorage"
    private val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Returns the dedicated directory for storing voiceover transcriptions.
     * Placed inside the app's dedicated project directory.
     */
    fun getTranscriptionsDirectory(context: Context?, baseDir: File? = null): File {
        val projectDir = baseDir ?: (context?.let { ProjectManager.getProjectsDirectory(it) } ?: File("Projects"))
        val transDir = File(projectDir, "transcriptions")
        if (!transDir.exists()) {
            transDir.mkdirs()
        }
        return transDir
    }

    /**
     * Persists transcription data locally as a JSON file.
     *
     * @param context Android context.
     * @param projectName Name of the project associated with the voiceover (optional).
     * @param audioFileName Name of the recorded audio file.
     * @param audioDurationSeconds Duration of the recorded audio in seconds.
     * @param response The [TranscriptionResponse] containing the raw JSON from Whisper.
     * @param targetDir Optional custom destination directory for test isolation.
     * @return The saved [File].
     */
    fun saveTranscriptionResult(
        context: Context?,
        projectName: String?,
        audioFileName: String,
        audioDurationSeconds: Int,
        response: TranscriptionResponse,
        targetDir: File? = null,
        audioUri: String? = null
    ): File {
        val dir = targetDir ?: getTranscriptionsDirectory(context)
        val safeProjectName = (projectName ?: "Voiceover")
            .replace(Regex("[/\\\\?%*:|\"<>]"), "_")
            .trim()
        val timestamp = System.currentTimeMillis()
        val fileName = "${safeProjectName}_transcription_${timestamp}.json"
        val targetFile = File(dir, fileName)

        val rootObject: JsonObject = try {
            if (response.rawJson.isNotBlank()) {
                val parsed = JsonParser.parseString(response.rawJson)
                if (parsed.isJsonObject) parsed.asJsonObject else JsonObject().apply {
                    add("response", parsed)
                }
            } else {
                JsonObject().apply {
                    addProperty("text", response.text ?: "")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse rawJson as JsonObject, falling back to wrapper: ${e.message}")
            JsonObject().apply {
                addProperty("text", response.text ?: "")
                addProperty("raw_fallback", response.rawJson)
            }
        }

        // Add local metadata headers
        rootObject.addProperty("_project_name", projectName ?: "Untitled")
        rootObject.addProperty("_audio_file_name", audioFileName)
        if (!audioUri.isNullOrEmpty()) {
            rootObject.addProperty("_audio_uri", audioUri)
        }
        rootObject.addProperty("_audio_duration_seconds", audioDurationSeconds)
        rootObject.addProperty("_saved_at_ms", timestamp)

        // STRICT SECURITY: Scrub any credentials or tokens if accidentally contained
        rootObject.remove("jwt")
        rootObject.remove("token")
        rootObject.remove("apiKey")
        rootObject.remove("apikey")
        rootObject.remove("Authorization")
        rootObject.remove("authorization")

        val jsonString = prettyGson.toJson(rootObject)
        FileOutputStream(targetFile).use { fos ->
            OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }
        }

        Log.d(TAG, "Transcription successfully saved to: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
        return targetFile
    }

    /**
     * Lists all saved transcription files in the dedicated folder, sorted newest first.
     */
    fun listSavedTranscriptions(context: Context): List<File> {
        val dir = getTranscriptionsDirectory(context)
        return dir.listFiles { file ->
            file.isFile && file.name.endsWith(".json", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}

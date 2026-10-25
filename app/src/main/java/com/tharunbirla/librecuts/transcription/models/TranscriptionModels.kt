package com.tharunbirla.librecuts.transcription.models

import com.google.gson.annotations.SerializedName
import com.tharunbirla.librecuts.utils.ErrorCode
import java.io.File
import java.io.Serializable

/**
 * Request payload sent to Supabase Edge Function "transcribe-whisper".
 */
data class TranscribeWhisperRequest(
    @SerializedName("audioUrl") val audioUrl: String,
    @SerializedName("audioDurationSeconds") val audioDurationSeconds: Int
) : Serializable

/**
 * Represents a single word timestamp from Whisper (if provided by the engine).
 * Note: Timestamps are nullable as word-level timestamps are not guaranteed.
 */
data class TranscriptionWord(
    @SerializedName("word") val word: String = "",
    @SerializedName("start") val start: Double? = null,
    @SerializedName("end") val end: Double? = null,
    @SerializedName("score") val score: Double? = null
) : Serializable

/**
 * Represents a segment or sentence from the transcription.
 */
data class TranscriptionSegment(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("seek") val seek: Long? = null,
    @SerializedName("start") val start: Double = 0.0,
    @SerializedName("end") val end: Double = 0.0,
    @SerializedName("text") val text: String = "",
    @SerializedName("tokens") val tokens: List<Int>? = null,
    @SerializedName("temperature") val temperature: Double? = null,
    @SerializedName("avg_logprob") val avgLogprob: Double? = null,
    @SerializedName("compression_ratio") val compressionRatio: Double? = null,
    @SerializedName("no_speech_prob") val noSpeechProb: Double? = null,
    @SerializedName("words") val words: List<TranscriptionWord>? = null
) : Serializable

/**
 * Parsed structure of the transcription response.
 * Preserves the exact rawJson string returned by the server to avoid data loss.
 */
data class TranscriptionResponse(
    @SerializedName("text") val text: String? = null,
    @SerializedName("segments") val segments: List<TranscriptionSegment>? = null,
    @SerializedName("words") val words: List<TranscriptionWord>? = null,
    @SerializedName("language") val language: String? = null,
    @SerializedName("duration") val duration: Double? = null,
    @Transient val rawJson: String = ""
) : Serializable {

    /**
     * Helper to get full transcribed text, falling back to segment concatenation if text is null.
     */
    fun getFullText(): String {
        if (!text.isNullOrBlank()) return text.trim()
        val segs = segments
        if (!segs.isNullOrEmpty()) {
            return segs.joinToString(" ") { it.text.trim() }.trim()
        }
        return ""
    }
}

/**
 * Result state of the transcription pipeline.
 */
sealed class TranscriptionPipelineResult : Serializable {

    /**
     * Successfully transcribed, saved locally, and temporary cloud storage cleaned up.
     */
    data class Success(
        val response: TranscriptionResponse,
        val localSavedFile: File,
        val text: String,
        val durationSeconds: Int
    ) : TranscriptionPipelineResult()

    /**
     * Transcription was safely blocked because user JWT is missing or unauthenticated.
     */
    data class BlockedAuth(
        val message: String = "Transcription blocked: Valid user JWT is not yet available. Authentication UI is pending."
    ) : TranscriptionPipelineResult()

    /**
     * Error encountered during the process.
     */
    data class Error(
        val code: ErrorCode,
        val message: String,
        val httpStatusCode: Int? = null,
        val details: String? = null
    ) : TranscriptionPipelineResult()
}

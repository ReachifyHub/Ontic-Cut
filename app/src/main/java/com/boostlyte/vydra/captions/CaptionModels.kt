package com.boostlyte.vydra.captions

import java.io.Serializable

/**
 * Indicates whether cue timestamps were derived from word-level timestamps or segment distribution.
 */
enum class CaptionTimestampMode {
    WORD_LEVEL,
    SEGMENT_BASED
}

/**
 * Represents a single timed caption cue with start and end times in milliseconds.
 */
data class CaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
) : Serializable {
    init {
        require(startMs >= 0) { "Cue startMs cannot be negative" }
        require(endMs >= startMs) { "Cue endMs ($endMs) must be >= startMs ($startMs)" }
    }

    val durationMs: Long
        get() = endMs - startMs
}

/**
 * Result of building caption cues from a transcription.
 */
sealed class CaptionBuildResult {
    data class Success(
        val cues: List<CaptionCue>,
        val mode: CaptionTimestampMode
    ) : CaptionBuildResult()

    data class Error(val message: String) : CaptionBuildResult()
}

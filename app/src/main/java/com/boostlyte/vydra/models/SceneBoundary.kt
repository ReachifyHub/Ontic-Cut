package com.boostlyte.vydra.models

import java.io.Serializable

/**
 * Represents the calculated temporal boundaries for a script scene.
 *
 * @property sceneIndex 1-based index of the scene as defined in the script map.
 * @property sceneTitle Title or label of the scene (e.g. "Scene 1: Welcome to the channel").
 * @property startMs Start time in milliseconds within the voiceover timeline.
 * @property endMs End time in milliseconds within the voiceover timeline.
 * @property isMatched True if this scene's text was successfully aligned with the audio transcript.
 * @property scriptText The spoken text associated with this scene.
 * @property confidence Alignment confidence score (0.0 to 1.0).
 */
data class SceneBoundary(
    val sceneIndex: Int,
    val sceneTitle: String,
    val startMs: Long,
    val endMs: Long,
    val isMatched: Boolean,
    val scriptText: String = "",
    val confidence: Float = if (isMatched) 1.0f else 0.0f
) : Serializable {

    val durationMs: Long
        get() = if (isMatched && endMs > startMs) endMs - startMs else 0L

    fun formatTimeRange(): String {
        if (!isMatched) return "Unmatched"
        val startSec = startMs / 1000
        val startMins = startSec / 60
        val startSecRem = startSec % 60
        val endSec = endMs / 1000
        val endMins = endSec / 60
        val endSecRem = endSec % 60
        return String.format("%d:%02d - %d:%02d", startMins, startSecRem, endMins, endSecRem)
    }
}

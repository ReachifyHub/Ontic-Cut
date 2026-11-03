package com.boostlyte.vydra.utils

import kotlin.math.abs

/**
 * Utility for smooth, non-crackling audio volume ramping and curve calculations.
 * Replaces instantaneous per-tick volume writes with a smooth linear ramp (80-120ms)
 * to eliminate zipper noise, clicks, and pops during playback, scrubbing, and edits.
 */
object AudioVolumeRamp {

    /**
     * Default volume ramp duration in milliseconds (strictly within the 80-120ms requirement).
     */
    const val DEFAULT_RAMP_DURATION_MS: Long = 100L

    /**
     * Ducking attenuation target for background audio when foreground voiceover/dialogue is active.
     */
    const val DUCKING_TARGET_MULTIPLIER: Float = 0.2f

    /**
     * Minimum audible volume difference required to justify an ExoPlayer.setVolume call.
     */
    const val AUDIBLE_DIFF_THRESHOLD: Float = 0.002f

    /**
     * Calculate the next volume step toward targetVolume using a linear ramp.
     *
     * @param currentVolume The current playing volume [0.0f, 1.0f]
     * @param targetVolume The target volume [0.0f, 1.0f]
     * @param dtMs Elapsed time in milliseconds since the previous update
     * @param rampDurationMs Total duration of the ramp in milliseconds (default 100ms)
     * @return The updated volume after applying the linear delta
     */
    fun computeNextVolume(
        currentVolume: Float,
        targetVolume: Float,
        dtMs: Long,
        rampDurationMs: Long = DEFAULT_RAMP_DURATION_MS
    ): Float {
        if (dtMs <= 0L) return currentVolume.coerceIn(0f, 1f)
        val safeRampMs = rampDurationMs.coerceAtLeast(1L)
        val maxChange = (dtMs.toFloat() / safeRampMs.toFloat()).coerceIn(0.005f, 1.0f)
        val diff = targetVolume - currentVolume

        return if (abs(diff) <= maxChange) {
            targetVolume
        } else if (diff > 0f) {
            currentVolume + maxChange
        } else {
            currentVolume - maxChange
        }.coerceIn(0f, 1f)
    }

    /**
     * Computes the combined target volume from user level, fade curves, ducking, and mute state.
     *
     * @param rawVolume Base volume from track settings or user slider [0.0f, 2.0f]
     * @param fadeInMult Fade-in multiplier [0.0f, 1.0f]
     * @param fadeOutMult Fade-out multiplier [0.0f, 1.0f]
     * @param duckingTarget Ducking multiplier (e.g. 0.2f when ducked, 1.0f otherwise)
     * @param isMasterMuted Whether the editor master audio is muted
     * @return Target volume clamped to [0.0f, 1.0f]
     */
    fun computeTargetVolume(
        rawVolume: Float,
        fadeInMult: Float = 1.0f,
        fadeOutMult: Float = 1.0f,
        duckingTarget: Float = 1.0f,
        isMasterMuted: Boolean = false
    ): Float {
        if (isMasterMuted) return 0f
        val safeRaw = rawVolume.coerceAtLeast(0f)
        val combined = safeRaw * fadeInMult.coerceIn(0f, 1f) * fadeOutMult.coerceIn(0f, 1f) * duckingTarget.coerceIn(0f, 1f)
        return combined.coerceIn(0f, 1f)
    }

    /**
     * Compute linear fade multiplier based on position within clip and fade duration.
     */
    fun computeFadeMultiplier(
        offsetInOpMs: Long,
        remainingInOpMs: Long,
        fadeInDurationMs: Long,
        fadeOutDurationMs: Long
    ): Float {
        val inMult = if (fadeInDurationMs > 0L) {
            (offsetInOpMs.toFloat() / fadeInDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            1.0f
        }
        val outMult = if (fadeOutDurationMs > 0L) {
            (remainingInOpMs.toFloat() / fadeOutDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            1.0f
        }
        return (inMult * outMult).coerceIn(0f, 1f)
    }

    /**
     * Determine whether the volume difference between current and target is audible.
     */
    fun isAudibleChange(current: Float, target: Float, threshold: Float = AUDIBLE_DIFF_THRESHOLD): Boolean {
        return abs(current - target) > threshold
    }
}

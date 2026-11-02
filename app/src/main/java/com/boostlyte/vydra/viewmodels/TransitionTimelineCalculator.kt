package com.boostlyte.vydra.viewmodels

import com.boostlyte.vydra.models.EditOperation

data class TransitionOffsetInfo(
    val gapIndex: Int,
    val offsetSec: Double,
    val durationSec: Double,
    val transitionType: String
)

data class TransitionTimelineMath(
    val totalDurationSec: Double,
    val transitions: List<TransitionOffsetInfo>
)

/**
 * Pure calculator for video transitions and FFmpeg xfade offset timing.
 *
 * Mathematical Invariant:
 * For any sequence of clips with durations D_0, D_1, ..., D_N and transitions T_k with duration d_k,
 * the total duration of the composite sequence equals:
 *   totalDuration == sum(D_i) - sum(d_k)
 *
 * Each transition offset represents the timestamp in the accumulated timeline where the xfade
 * effect starts. Offsets are strictly monotonically increasing:
 *   offset_0 < offset_1 < ... < offset_k
 */
object TransitionTimelineCalculator {

    fun calculate(
        clipDurationsSec: List<Double>,
        transitions: List<EditOperation.Transition>
    ): TransitionTimelineMath {
        if (clipDurationsSec.isEmpty()) {
            return TransitionTimelineMath(0.0, emptyList())
        }
        if (clipDurationsSec.size == 1) {
            return TransitionTimelineMath(clipDurationsSec[0], emptyList())
        }

        val clipDurationsMs = clipDurationsSec.map { (it * 1000.0).toLong() }
        val layout = TimelineLayoutCalculator.computeTimelineLayoutFromDurations(clipDurationsMs, transitions)
        val transList = layout.transitions.map {
            TransitionOffsetInfo(
                gapIndex = it.gapIndex,
                offsetSec = it.offsetMs / 1000.0,
                durationSec = it.durationMs / 1000.0,
                transitionType = it.transitionType
            )
        }
        return TransitionTimelineMath(
            totalDurationSec = layout.totalDurationMs / 1000.0,
            transitions = transList
        )
    }
}

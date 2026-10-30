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

        val inputCount = clipDurationsSec.size
        val transitionOps = List(inputCount - 1) { gapIdx ->
            transitions.find { it.index == gapIdx }
        }

        val resultTransitions = mutableListOf<TransitionOffsetInfo>()
        var accumulatedDuration = clipDurationsSec[0]

        for (gapIdx in 0 until inputCount - 1) {
            val transOp = transitionOps[gapIdx]
            val nextClipDuration = clipDurationsSec[gapIdx + 1]

            if (transOp == null || transOp.type.equals("none", ignoreCase = true) || transOp.durationMs <= 0) {
                accumulatedDuration += nextClipDuration
            } else {
                val rawTransDuration = transOp.durationMs / 1000.0
                val transDuration = rawTransDuration
                    .coerceAtMost(accumulatedDuration)
                    .coerceAtMost(nextClipDuration)
                val offset = (accumulatedDuration - transDuration).coerceAtLeast(0.0)

                resultTransitions.add(
                    TransitionOffsetInfo(
                        gapIndex = gapIdx,
                        offsetSec = offset,
                        durationSec = transDuration,
                        transitionType = transOp.type
                    )
                )

                accumulatedDuration = offset + nextClipDuration
            }
        }

        return TransitionTimelineMath(
            totalDurationSec = accumulatedDuration,
            transitions = resultTransitions
        )
    }
}

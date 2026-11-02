package com.boostlyte.vydra.viewmodels

import com.boostlyte.vydra.models.EditOperation
import java.io.Serializable

data class ClipLayout(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long
) : Serializable

data class TransitionLayout(
    val gapIndex: Int,
    val offsetMs: Long,
    val durationMs: Long,
    val transitionType: String
) : Serializable

data class TimelineLayout(
    val totalDurationMs: Long,
    val clips: List<ClipLayout>,
    val transitions: List<TransitionLayout>
) : Serializable

/**
 * Pure calculator for timeline geometry and transition timing.
 * Single source of truth across:
 * - Fresh import
 * - Project save & reload
 * - Timeline rendering (tracks & ruler)
 * - Playhead position mapping
 * - Export duration calculation (-t)
 */
object TimelineLayoutCalculator {

    /**
     * Mathematical invariant:
     * totalDurationMs == sum(clip.trimmedDurationMs) - sum(transition.durationMs)
     *
     * Each transition overlaps between clip i and clip i+1.
     * Clip 0 starts at 0ms.
     * Transition i begins at (accumulatedEndMs - clampedTransitionDurationMs).
     * Clip i+1 starts at the transition offset.
     */
    fun computeTimelineLayout(
        sequenceItems: List<EditOperation.MergeItem>,
        transitions: List<EditOperation.Transition>
    ): TimelineLayout {
        return computeTimelineLayoutFromDurations(
            sequenceItems.map { it.trimmedDurationMs },
            transitions
        )
    }

    fun computeTimelineLayoutFromDurations(
        clipDurationsMs: List<Long>,
        transitions: List<EditOperation.Transition>
    ): TimelineLayout {
        if (clipDurationsMs.isEmpty()) {
            return TimelineLayout(0L, emptyList(), emptyList())
        }
        if (clipDurationsMs.size == 1) {
            val dur = clipDurationsMs[0].coerceAtLeast(0L)
            return TimelineLayout(dur, listOf(ClipLayout(0, 0L, dur, dur)), emptyList())
        }

        val clips = mutableListOf<ClipLayout>()
        val resultTransitions = mutableListOf<TransitionLayout>()

        val c0Dur = clipDurationsMs[0].coerceAtLeast(0L)
        clips.add(ClipLayout(index = 0, startMs = 0L, endMs = c0Dur, durationMs = c0Dur))
        var accumulatedEndMs = c0Dur

        for (i in 1 until clipDurationsMs.size) {
            val gapIdx = i - 1
            val transOp = transitions.find { it.index == gapIdx }
            val nextClipDur = clipDurationsMs[i].coerceAtLeast(0L)

            if (transOp != null && !transOp.type.equals("none", ignoreCase = true) && transOp.durationMs > 0) {
                val rawTransDur = transOp.durationMs
                val transDur = rawTransDur
                    .coerceAtMost(accumulatedEndMs)
                    .coerceAtMost(nextClipDur)
                val clipStartMs = (accumulatedEndMs - transDur).coerceAtLeast(0L)
                val clipEndMs = clipStartMs + nextClipDur

                resultTransitions.add(
                    TransitionLayout(
                        gapIndex = gapIdx,
                        offsetMs = clipStartMs,
                        durationMs = transDur,
                        transitionType = transOp.type
                    )
                )

                clips.add(
                    ClipLayout(
                        index = i,
                        startMs = clipStartMs,
                        endMs = clipEndMs,
                        durationMs = nextClipDur
                    )
                )

                accumulatedEndMs = clipEndMs
            } else {
                val clipStartMs = accumulatedEndMs
                val clipEndMs = clipStartMs + nextClipDur

                clips.add(
                    ClipLayout(
                        index = i,
                        startMs = clipStartMs,
                        endMs = clipEndMs,
                        durationMs = nextClipDur
                    )
                )

                accumulatedEndMs = clipEndMs
            }
        }

        return TimelineLayout(
            totalDurationMs = accumulatedEndMs,
            clips = clips,
            transitions = resultTransitions
        )
    }

    /**
     * Pure, deterministic pixels-per-ms calculation for timeline view.
     * Prevents squish between fresh session and project reopen by ensuring identical
     * scaling given the same totalDuration, viewport width, and display density.
     */
    fun calculatePixelsPerMs(
        totalDurationMs: Long,
        viewportWidthPx: Float = 0f,
        density: Float = 1.0f
    ): Float {
        val basePixelsPerMs = 0.15f * density
        if (totalDurationMs <= 0L || viewportWidthPx <= 0f) {
            return basePixelsPerMs
        }
        val minPixelsPerMs = 0.02f * density
        val maxPixelsPerMs = 0.50f * density
        val fitRate = (viewportWidthPx - (32f * density)) / totalDurationMs
        return fitRate.coerceIn(minPixelsPerMs, maxPixelsPerMs)
    }
}

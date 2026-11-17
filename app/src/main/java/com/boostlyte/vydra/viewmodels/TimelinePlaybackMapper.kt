package com.boostlyte.vydra.viewmodels

import com.boostlyte.vydra.models.EditOperation
import kotlin.math.abs

/**
 * A contiguous, non-overlapping playback interval in the authoritative project timeline.
 * Concatenating all [PlaybackSlice]s produces an exact total duration equal to
 * [TimelineLayout.totalDurationMs], even when transitions overlap adjacent clips.
 */
data class PlaybackSlice(
    val sliceIndex: Int,
    val clipIndex: Int,
    val timelineStartMs: Long,
    val timelineEndMs: Long,
    val timelineDurationMs: Long,
    val sliceStartInClipMs: Long
)

/**
 * Authoritative playback snapshot for a given project timeline position.
 */
data class ResolvedPlaybackState(
    val clipIndex: Int,
    val clipStartMs: Long,
    val clipEndMs: Long,
    val clipDurationMs: Long,
    val relTimeMs: Long,
    val activeTransition: TransitionLayout?,
    val transitionProgress: Float
)

/**
 * Single authoritative time model shared across editor ExoPlayer slices, preview canvas,
 * transition overlay, background/voiceover audio sync, timeline UI, and editing tools.
 */
data class AuthoritativeTimelineModel(
    val layout: TimelineLayout,
    val slices: List<PlaybackSlice>
)

object TimelinePlaybackMapper {

    fun buildModel(
        sequenceItems: List<EditOperation.MergeItem>,
        transitions: List<EditOperation.Transition>
    ): AuthoritativeTimelineModel {
        val layout = TimelineLayoutCalculator.computeTimelineLayout(sequenceItems, transitions)
        return buildModelFromLayout(layout)
    }

    fun buildModelFromLayout(layout: TimelineLayout): AuthoritativeTimelineModel {
        val clips = layout.clips
        if (clips.isEmpty() || layout.totalDurationMs <= 0L) {
            return AuthoritativeTimelineModel(layout, emptyList())
        }
        if (clips.size == 1) {
            val dur = layout.totalDurationMs
            return AuthoritativeTimelineModel(
                layout = layout,
                slices = listOf(
                    PlaybackSlice(
                        sliceIndex = 0,
                        clipIndex = 0,
                        timelineStartMs = 0L,
                        timelineEndMs = dur,
                        timelineDurationMs = dur,
                        sliceStartInClipMs = 0L
                    )
                )
            )
        }

        val handoffs = LongArray(clips.size - 1)
        var prevHandoff = 0L
        for (gapIdx in 0 until clips.size - 1) {
            val clipA = clips[gapIdx]
            val trans = layout.transitions.find { it.gapIndex == gapIdx }
            val rawHandoff = if (trans != null && trans.durationMs > 0L) {
                trans.offsetMs + (trans.durationMs / 2L)
            } else {
                clipA.endMs
            }
            val safeHandoff = rawHandoff.coerceIn(prevHandoff, clipA.endMs)
            handoffs[gapIdx] = safeHandoff
            prevHandoff = safeHandoff
        }

        val slices = mutableListOf<PlaybackSlice>()
        for (i in clips.indices) {
            val clip = clips[i]
            val start = if (i == 0) 0L else handoffs[i - 1]
            val end = if (i == clips.lastIndex) layout.totalDurationMs else handoffs[i]
            val dur = (end - start).coerceAtLeast(0L)
            if (dur > 0L) {
                slices.add(
                    PlaybackSlice(
                        sliceIndex = slices.size,
                        clipIndex = i,
                        timelineStartMs = start,
                        timelineEndMs = end,
                        timelineDurationMs = dur,
                        sliceStartInClipMs = (start - clip.startMs).coerceAtLeast(0L)
                    )
                )
            }
        }

        return AuthoritativeTimelineModel(layout = layout, slices = slices)
    }

    fun resolveAt(model: AuthoritativeTimelineModel, globalPosMs: Long): ResolvedPlaybackState? {
        val clips = model.layout.clips
        if (clips.isEmpty()) return null
        val total = model.layout.totalDurationMs
        val pos = globalPosMs.coerceIn(0L, total)

        val activeSlice = if (model.slices.isNotEmpty()) {
            model.slices.firstOrNull { slice ->
                pos >= slice.timelineStartMs && (pos < slice.timelineEndMs || (slice === model.slices.last() && pos <= slice.timelineEndMs))
            } ?: model.slices.last()
        } else {
            null
        }

        val clipIndex = activeSlice?.clipIndex ?: clips.indexOfFirst { clip ->
            pos >= clip.startMs && (pos < clip.endMs || (clip === clips.last() && pos <= clip.endMs))
        }.let { if (it >= 0) it else clips.lastIndex }

        val clip = clips[clipIndex]
        val safeClipDur = maxOf(1L, clip.durationMs)
        val relTimeMs = (pos - clip.startMs).coerceIn(0L, safeClipDur)

        val activeTrans = model.layout.transitions.firstOrNull { t ->
            t.durationMs > 0L && pos >= t.offsetMs && pos < (t.offsetMs + t.durationMs)
        }
        val transProgress = if (activeTrans != null) {
            ((pos - activeTrans.offsetMs).toFloat() / activeTrans.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        return ResolvedPlaybackState(
            clipIndex = clipIndex,
            clipStartMs = clip.startMs,
            clipEndMs = clip.endMs,
            clipDurationMs = clip.durationMs,
            relTimeMs = relTimeMs,
            activeTransition = activeTrans,
            transitionProgress = transProgress
        )
    }

    fun resolveSlicePosition(sliceDurationsMs: List<Long>, globalPosMs: Long): Pair<Int, Long> {
        if (sliceDurationsMs.isEmpty()) return Pair(0, globalPosMs.coerceAtLeast(0L))
        var remaining = globalPosMs.coerceAtLeast(0L)
        for (i in sliceDurationsMs.indices) {
            val dur = sliceDurationsMs[i]
            if (remaining < dur || i == sliceDurationsMs.lastIndex) {
                return Pair(i, remaining.coerceIn(0L, dur))
            }
            remaining -= dur
        }
        val lastIdx = sliceDurationsMs.lastIndex
        return Pair(lastIdx, sliceDurationsMs[lastIdx])
    }

    fun computeGlobalPosition(
        sliceDurationsMs: List<Long>,
        currentSliceIndex: Int,
        rawSlicePosMs: Long,
        lastItemIndex: Int,
        lastGlobalPosMs: Long,
        isPlaying: Boolean
    ): Pair<Long, Int> {
        if (sliceDurationsMs.isEmpty()) {
            return Pair(rawSlicePosMs.coerceAtLeast(0L), 0)
        }
        val safeIndex = currentSliceIndex.coerceIn(0, sliceDurationsMs.lastIndex)
        var basePos = 0L
        for (i in 0 until safeIndex) {
            basePos += sliceDurationsMs[i]
        }
        val chunkDur = sliceDurationsMs[safeIndex]
        // Only treat rawSlicePosMs as stale from the prior item if ExoPlayer just switched items
        // while still reporting a position exceeding the new item's duration.
        val chunkPos = if (lastItemIndex != -1 && safeIndex != lastItemIndex && rawSlicePosMs > chunkDur + 16L) {
            0L
        } else {
            rawSlicePosMs.coerceIn(0L, chunkDur)
        }
        val calculated = basePos + chunkPos
        return if (isPlaying) {
            if (calculated < lastGlobalPosMs && (lastGlobalPosMs - calculated) < 1000L && safeIndex >= lastItemIndex) {
                Pair(lastGlobalPosMs, safeIndex)
            } else {
                Pair(calculated, safeIndex)
            }
        } else {
            Pair(calculated, safeIndex)
        }
    }

    fun clipStartMs(layout: TimelineLayout, clipIndex: Int): Long {
        return layout.clips.getOrNull(clipIndex)?.startMs ?: 0L
    }

    fun clipEndMs(layout: TimelineLayout, clipIndex: Int): Long {
        return layout.clips.getOrNull(clipIndex)?.endMs ?: 0L
    }

    fun calculatePlayheadInsertionIndex(
        layout: TimelineLayout,
        playheadMs: Long,
        toleranceMs: Long = 120L
    ): Int? {
        val clips = layout.clips
        if (clips.isEmpty()) return 0
        if (playheadMs <= toleranceMs) return 0
        if (playheadMs >= layout.totalDurationMs - toleranceMs) return clips.size

        for (gapIdx in 0 until clips.size - 1) {
            val trans = layout.transitions.find { it.gapIndex == gapIdx }
            val inBoundaryWindow = if (trans != null && trans.durationMs > 0L) {
                playheadMs in (trans.offsetMs - toleranceMs)..(trans.offsetMs + trans.durationMs + toleranceMs)
            } else {
                val boundaryMs = clips[gapIdx].endMs
                abs(playheadMs - boundaryMs) <= toleranceMs
            }
            if (inBoundaryWindow) {
                return gapIdx + 1
            }
        }
        return null
    }

    fun findClipForSplit(
        model: AuthoritativeTimelineModel,
        playheadMs: Long,
        selectedIndex: Int? = null,
        minEdgeMarginMs: Long = 50L
    ): ClipLayout? {
        val clips = model.layout.clips
        if (clips.isEmpty()) return null

        if (selectedIndex != null) {
            val selectedClip = clips.getOrNull(selectedIndex) ?: return null
            return if (playheadMs > selectedClip.startMs + minEdgeMarginMs &&
                playheadMs < selectedClip.endMs - minEdgeMarginMs
            ) {
                selectedClip
            } else {
                null
            }
        }

        val resolved = resolveAt(model, playheadMs) ?: return null
        val activeClip = clips.getOrNull(resolved.clipIndex)
        if (activeClip != null &&
            playheadMs > activeClip.startMs + minEdgeMarginMs &&
            playheadMs < activeClip.endMs - minEdgeMarginMs
        ) {
            return activeClip
        }

        return clips.firstOrNull { clip ->
            playheadMs > clip.startMs + minEdgeMarginMs && playheadMs < clip.endMs - minEdgeMarginMs
        }
    }

    fun getSnapTargetsMs(
        layout: TimelineLayout,
        operations: List<EditOperation>
    ): List<Long> {
        val targets = mutableSetOf<Long>()
        targets.add(0L)
        if (layout.totalDurationMs > 0L) {
            targets.add(layout.totalDurationMs)
        }

        for (clip in layout.clips) {
            targets.add(clip.startMs)
            targets.add(clip.endMs)
        }
        for (trans in layout.transitions) {
            targets.add(trans.offsetMs)
            targets.add(trans.offsetMs + trans.durationMs)
        }

        for (op in operations) {
            when (op) {
                is EditOperation.AddBackgroundAudio -> {
                    op.startTimeMs?.let { targets.add(it) }
                    op.endTimeMs?.let { targets.add(it) }
                    val start = op.startTimeMs ?: 0L
                    val end = op.endTimeMs ?: layout.totalDurationMs
                    for (beat in op.beats) {
                        val relative = beat - op.internalStartMs
                        if (relative >= 0) {
                            val timelineMs = start + relative
                            if (timelineMs <= end) {
                                targets.add(timelineMs)
                            }
                        }
                    }
                }
                is EditOperation.AddText -> {
                    op.startTimeMs?.let { targets.add(it) }
                    op.endTimeMs?.let { targets.add(it) }
                }
                is EditOperation.AddSubtitles -> {
                    for (cue in op.cues) {
                        targets.add(cue.startTimeMs)
                        targets.add(cue.endTimeMs)
                    }
                }
                is EditOperation.AddImageOverlay -> {
                    op.startTimeMs?.let { targets.add(it) }
                    op.endTimeMs?.let { targets.add(it) }
                }
                else -> {}
            }
        }

        return targets.sorted()
    }

    /**
     * Determines whether a background/voiceover audio player needs a seek adjustment.
     * Avoids seeking on visual clip boundaries so voiceover stays continuous and glitch-free
     * across 65+ transition cuts, resyncing only on explicit seeks, playback start/jump, or
     * measured A/V drift exceeding [driftThresholdMs].
     */
    fun shouldResyncBgAudio(
        isSeek: Boolean,
        playbackJustStarted: Boolean,
        playheadJumped: Boolean,
        isBgPlaying: Boolean,
        currentBgPosMs: Long,
        targetAudioMs: Long,
        elapsedSinceLastResyncMs: Long,
        driftThresholdMs: Long = 120L,
        resyncCooldownMs: Long = 500L
    ): Boolean {
        if (isSeek || playbackJustStarted || playheadJumped) return true
        if (!isBgPlaying) return true
        return elapsedSinceLastResyncMs >= resyncCooldownMs &&
            abs(currentBgPosMs - targetAudioMs) > driftThresholdMs
    }
}

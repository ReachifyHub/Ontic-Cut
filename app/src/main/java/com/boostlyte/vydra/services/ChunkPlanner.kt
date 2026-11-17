package com.boostlyte.vydra.services

import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.viewmodels.TimelineLayoutCalculator
import java.io.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

data class ChunkClip(
    val globalIndex: Int,
    val localIndex: Int,
    val item: EditOperation.MergeItem,
    val resolvedLocalPath: String? = null,
    val originalUriString: String? = null,
    val fullClipDurationMs: Long = 0L,
    val sliceStartInClipMs: Long = 0L,
    val sliceDurationMs: Long = 0L,
    val exactFrames: Int = 0
) : Serializable

data class ChunkBoundaryTransition(
    val boundaryIndex: Int, // between chunk i and chunk i+1
    val globalGapIndex: Int,
    val transition: EditOperation.Transition?
) : Serializable

data class ChunkSpec(
    val chunkIndex: Int,
    val clips: List<ChunkClip>,
    val internalTransitions: List<EditOperation.Transition>,
    val globalStartMs: Long,
    val globalEndMs: Long,
    val durationMs: Long,
    val totalFrames: Int = 0
) : Serializable

data class ChunkPlan(
    val chunks: List<ChunkSpec>,
    val boundaryTransitions: List<ChunkBoundaryTransition>,
    val totalDurationMs: Long,
    val isChunked: Boolean
) : Serializable

data class ClampedTimeWindow(
    val isVisibleInChunk: Boolean,
    val localStartMs: Long,
    val localEndMs: Long
) : Serializable

data class ChunkCommand(
    val chunkIndex: Int,
    val command: String,
    val outputPath: String,
    val durationSecs: Double,
    val scriptFilePath: String? = null,
    val renderPath: String = "legacy",
    val tailPadFrames: Int = 0
) : Serializable

data class ChunkExportPlan(
    val isChunked: Boolean,
    val singlePassCommand: String? = null,
    val chunkCommands: List<ChunkCommand> = emptyList(),
    val finalPassCommand: String? = null,
    val chunkTempPaths: List<String> = emptyList(),
    val totalDurationSecs: Double = 0.0,
    val chunkDurationsSecs: List<Double> = emptyList(),
    val renderPath: String = "legacy",
    val chunkSpecs: List<ChunkSpec> = emptyList(),
    val targetWidth: Int = 1280,
    val targetHeight: Int = 720,
    val fps: Int = 30,
    val resolution: Int = 720,
    val density: Float = 1.0f,
    val fontFilePath: String? = null,
    val operations: List<EditOperation> = emptyList(),
    val canStreamCopyFinalPass: Boolean = false,
    val canStreamCopyVideoWithAudioMux: Boolean = false,
    val audioMixCommand: String? = null,
    val mixedAudioTempPath: String? = null,
    val audioBitrate: String = "128k",
    val videoDurationSecs: Double = 0.0,
    val needsVideoPadding: Boolean = false,
    val paddingDurationSecs: Double = 0.0,
    val expectedAudioDurationSecs: Double = 0.0,
    val hasAudio: Boolean = false,
    val prepJobDirPath: String? = null
) : Serializable

/**
 * Pure Chunk Planner for large sequence exports (> 16 inputs).
 * Prevents kernel OOM kills on 60+ image projects by planning sub-sequence chunks
 * of at most MAX_CHUNK_SIZE clips (or up to MAX_CHUNK_SIZE + 1 when optimizing cuts).
 */
object ChunkPlanner {

    const val CHUNK_TRIGGER_THRESHOLD = 16
    const val MAX_CHUNK_SIZE = 10
    private const val TRANSITION_SAFE_MARGIN_MS = 150L
    private const val MIN_SPLIT_INTERIOR_MS = 400L

    internal fun getClipFullDurationMs(item: EditOperation.MergeItem): Long {
        return when {
            item.trimmedDurationMs > 0L -> item.trimmedDurationMs
            item.trimEndMs > item.trimStartMs -> ((item.trimEndMs - item.trimStartMs) / item.speed).toLong()
            item.durationMs > 0L -> item.durationMs
            else -> 5000L
        }
    }

    /**
     * Quantizes clip frame counts using cumulative Bresenham rounding along the global timeline
     * so per-clip integer frame rounding never accumulates A/V drift across 100+ clips.
     */
    internal fun computeBresenhamClipFrames(
        sliceDurationsMs: List<Long>,
        internalTransitions: List<EditOperation.Transition>,
        chunkGlobalStartMs: Long,
        chunkGlobalEndMs: Long,
        fps: Int
    ): Pair<List<Int>, Int> {
        if (sliceDurationsMs.isEmpty()) return Pair(emptyList(), 0)
        val safeFps = fps.coerceAtLeast(1)
        val chunkLayout = TimelineLayoutCalculator.computeTimelineLayoutFromDurations(sliceDurationsMs, internalTransitions)
        val chunkStartFrame = ((chunkGlobalStartMs * safeFps) / 1000.0).roundToLong()
        val chunkEndFrame = ((chunkGlobalEndMs * safeFps) / 1000.0).roundToLong()
        val chunkTotalFrames = (chunkEndFrame - chunkStartFrame).toInt().coerceAtLeast(1)

        val clipFrames = IntArray(sliceDurationsMs.size)
        var prevEndFrame = chunkStartFrame

        for (i in sliceDurationsMs.indices) {
            val minBound = minOf(prevEndFrame + 1L, chunkEndFrame)
            val clipEndFrame = if (i == sliceDurationsMs.lastIndex) {
                chunkEndFrame
            } else {
                val localEndMs = chunkLayout.clips.getOrNull(i)?.endMs ?: sliceDurationsMs.take(i + 1).sum()
                ((chunkGlobalStartMs + localEndMs) * safeFps / 1000.0).roundToLong()
                    .coerceIn(minBound, chunkEndFrame)
            }
            val transInFrames = if (i > 0) {
                val tLayout = chunkLayout.transitions.find { it.gapIndex == i - 1 }
                if (tLayout != null && tLayout.durationMs > 0L) {
                    val prevTotalFrames = (prevEndFrame - chunkStartFrame).coerceAtLeast(1L)
                    val maxPrevTrans = (prevTotalFrames - 1L).coerceAtLeast(1L)
                    ((tLayout.durationMs * safeFps) / 1000.0).roundToLong()
                        .coerceIn(1L, maxPrevTrans)
                        .toInt()
                } else 0
            } else 0

            clipFrames[i] = ((clipEndFrame - prevEndFrame).toInt() + transInFrames).coerceAtLeast(1)
            prevEndFrame = clipEndFrame
        }

        return Pair(clipFrames.toList(), chunkTotalFrames)
    }

    /**
     * Plans chunks for a given sequence of clips and transitions.
     * If sequenceItems.size <= chunkTriggerThreshold (default 16), single-pass graph is preserved.
     *
     * When [containBoundaryTransitions] is true, chunk boundaries avoid splitting across
     * transitions either by cutting at a nearby transition-free gap or by slicing a clip
     * cleanly in its transition-free interior so all transitions stay inside chunks and final
     * assembly can use fast `-c copy` concatenation instead of re-encoding the full video.
     */
    fun plan(
        sequenceItems: List<EditOperation.MergeItem>,
        transitions: List<EditOperation.Transition>,
        containBoundaryTransitions: Boolean = false,
        fps: Int = 30,
        maxChunkSize: Int = MAX_CHUNK_SIZE,
        chunkTriggerThreshold: Int = CHUNK_TRIGGER_THRESHOLD
    ): ChunkPlan {
        val safeFps = fps.coerceAtLeast(1)
        val effectiveMaxChunkSize = maxChunkSize.coerceIn(2, MAX_CHUNK_SIZE)
        val effectiveTriggerThreshold = chunkTriggerThreshold.coerceAtLeast(effectiveMaxChunkSize)
        val totalLayout = TimelineLayoutCalculator.computeTimelineLayout(sequenceItems, transitions)
        val totalDurationMs = totalLayout.totalDurationMs

        if (sequenceItems.size <= effectiveTriggerThreshold) {
            val fullDurations = sequenceItems.map { getClipFullDurationMs(it) }
            val (clipFrames, totalFrames) = computeBresenhamClipFrames(
                sliceDurationsMs = fullDurations,
                internalTransitions = transitions,
                chunkGlobalStartMs = 0L,
                chunkGlobalEndMs = totalDurationMs,
                fps = safeFps
            )
            val singleChunkClips = sequenceItems.mapIndexed { idx, item ->
                val fullDur = fullDurations[idx]
                ChunkClip(
                    globalIndex = idx,
                    localIndex = idx,
                    item = item,
                    resolvedLocalPath = item.uri.path ?: item.uri.toString(),
                    originalUriString = item.uri.toString(),
                    fullClipDurationMs = fullDur,
                    sliceStartInClipMs = 0L,
                    sliceDurationMs = fullDur,
                    exactFrames = clipFrames.getOrElse(idx) { 0 }
                )
            }
            val singleChunk = ChunkSpec(
                chunkIndex = 0,
                clips = singleChunkClips,
                internalTransitions = transitions,
                globalStartMs = 0L,
                globalEndMs = totalDurationMs,
                durationMs = totalDurationMs,
                totalFrames = totalFrames
            )
            return ChunkPlan(
                chunks = listOf(singleChunk),
                boundaryTransitions = emptyList(),
                totalDurationMs = totalDurationMs,
                isChunked = false
            )
        }

        val chunks = mutableListOf<ChunkSpec>()
        val boundaryTransitions = mutableListOf<ChunkBoundaryTransition>()

        val totalClips = sequenceItems.size
        val frameDurMs = 1000.0 / safeFps

        fun activeTransitionAtGap(gapIdx: Int): EditOperation.Transition? {
            val t = transitions.find { it.index == gapIdx }
            return if (t != null && !t.type.equals("none", ignoreCase = true) && t.durationMs > 0L) t else null
        }

        var currentGlobalIdx = 0
        var pendingTailStartMs = 0L
        var accumulatedGlobalStartMs = 0L
        var chunkIdx = 0

        while (currentGlobalIdx < totalClips) {
            val remainingClips = totalClips - currentGlobalIdx

            var endGlobalIdx: Int
            var endClipSplitMs: Long? = null
            var nextGlobalIdx: Int
            var nextPendingTailStartMs = 0L
            var chunkBoundaryGapIdx: Int? = null
            var chunkBoundaryTrans: EditOperation.Transition? = null

            if (!containBoundaryTransitions || remainingClips <= effectiveMaxChunkSize) {
                val chunkSize = minOf(effectiveMaxChunkSize, remainingClips)
                endGlobalIdx = currentGlobalIdx + chunkSize - 1
                nextGlobalIdx = endGlobalIdx + 1
                if (nextGlobalIdx < totalClips) {
                    chunkBoundaryGapIdx = endGlobalIdx
                    chunkBoundaryTrans = activeTransitionAtGap(endGlobalIdx)
                }
            } else {
                val idealEnd = currentGlobalIdx + effectiveMaxChunkSize - 1
                val maxEnd = minOf(totalClips - 2, currentGlobalIdx + effectiveMaxChunkSize)
                val minOffset = maxOf(1, (effectiveMaxChunkSize * 0.6).toInt())
                val minEnd = minOf(maxEnd, currentGlobalIdx + minOffset)

                // Priority 1: Cut at a transition-free gap in [minEnd..maxEnd] closest to idealEnd
                val freeGap = (minEnd..maxEnd)
                    .filter { g -> activeTransitionAtGap(g) == null }
                    .minWithOrNull(compareBy<Int> { abs(it - idealEnd) }.thenByDescending { it })

                if (freeGap != null) {
                    endGlobalIdx = freeGap
                    nextGlobalIdx = freeGap + 1
                    chunkBoundaryGapIdx = freeGap
                    chunkBoundaryTrans = null
                } else {
                    // Priority 2: Split a clip cleanly in its transition-free interior
                    data class SplitCandidate(val clipIdx: Int, val splitMs: Long, val isImage: Boolean)

                    val splitCandidate = ((currentGlobalIdx + 1)..maxEnd)
                        .filter { c -> c in minEnd..maxEnd }
                        .mapNotNull { c ->
                            val item = sequenceItems[c]
                            val fullDurMs = getClipFullDurationMs(item)
                            val prevFullDurMs = getClipFullDurationMs(sequenceItems[c - 1])
                            val nextFullDurMs = getClipFullDurationMs(sequenceItems[c + 1])

                            val rawTransIn = activeTransitionAtGap(c - 1)?.durationMs ?: 0L
                            val transInMs = rawTransIn.coerceAtMost(minOf(prevFullDurMs, fullDurMs))
                            val rawTransOut = activeTransitionAtGap(c)?.durationMs ?: 0L
                            val transOutMs = rawTransOut.coerceAtMost(minOf(fullDurMs, nextFullDurMs))

                            val interiorStartMs = transInMs + TRANSITION_SAFE_MARGIN_MS
                            val interiorEndMs = fullDurMs - transOutMs - TRANSITION_SAFE_MARGIN_MS
                            if (interiorEndMs - interiorStartMs >= MIN_SPLIT_INTERIOR_MS) {
                                val rawMidMs = (interiorStartMs + interiorEndMs) / 2L
                                val alignedFrame = (rawMidMs / frameDurMs).roundToLong()
                                val alignedMs = (alignedFrame * frameDurMs).roundToLong()
                                    .coerceIn(interiorStartMs, interiorEndMs)
                                SplitCandidate(c, alignedMs, item.isImage)
                            } else {
                                null
                            }
                        }
                        .minWithOrNull(
                            compareBy<SplitCandidate> { !it.isImage }
                                .thenBy { abs(it.clipIdx - idealEnd) }
                        )

                    if (splitCandidate != null) {
                        endGlobalIdx = splitCandidate.clipIdx
                        endClipSplitMs = splitCandidate.splitMs
                        nextGlobalIdx = splitCandidate.clipIdx
                        nextPendingTailStartMs = splitCandidate.splitMs
                        // Boundary transition eliminated because clip is split cleanly outside transitions
                    } else {
                        // Priority 3: Fallback to standard boundary transition if all clips are ultra-short
                        endGlobalIdx = minOf(totalClips - 2, idealEnd)
                        nextGlobalIdx = endGlobalIdx + 1
                        chunkBoundaryGapIdx = endGlobalIdx
                        chunkBoundaryTrans = activeTransitionAtGap(endGlobalIdx)
                    }
                }
            }

            val rawChunkClips = mutableListOf<ChunkClip>()
            val sliceDurationsMs = mutableListOf<Long>()
            for (gIdx in currentGlobalIdx..endGlobalIdx) {
                val localIdx = gIdx - currentGlobalIdx
                val item = sequenceItems[gIdx]
                val fullDurMs = getClipFullDurationMs(item)
                val sliceStart = if (gIdx == currentGlobalIdx) pendingTailStartMs else 0L
                val sliceEnd = if (gIdx == endGlobalIdx && endClipSplitMs != null) endClipSplitMs else fullDurMs
                val sliceDur = (sliceEnd - sliceStart).coerceAtLeast(100L)

                rawChunkClips.add(
                    ChunkClip(
                        globalIndex = gIdx,
                        localIndex = localIdx,
                        item = item,
                        resolvedLocalPath = item.uri.path ?: item.uri.toString(),
                        originalUriString = item.uri.toString(),
                        fullClipDurationMs = fullDurMs,
                        sliceStartInClipMs = sliceStart,
                        sliceDurationMs = sliceDur
                    )
                )
                sliceDurationsMs.add(sliceDur)
            }

            // Internal transitions for this chunk (between adjacent clips inside the chunk)
            val internalTrans = mutableListOf<EditOperation.Transition>()
            val numLocalGaps = rawChunkClips.size - 1
            for (localGap in 0 until numLocalGaps) {
                val globalGap = currentGlobalIdx + localGap
                val trans = transitions.find { it.index == globalGap }
                if (trans != null) {
                    internalTrans.add(trans.copy(index = localGap))
                }
            }

            val chunkLayout = TimelineLayoutCalculator.computeTimelineLayoutFromDurations(sliceDurationsMs, internalTrans)
            val chunkDurationMs = chunkLayout.totalDurationMs

            val chunkGlobalStartMs = if (!containBoundaryTransitions && pendingTailStartMs == 0L) {
                totalLayout.clips.getOrNull(currentGlobalIdx)?.startMs ?: accumulatedGlobalStartMs
            } else {
                accumulatedGlobalStartMs
            }
            val chunkGlobalEndMs = chunkGlobalStartMs + chunkDurationMs

            val (clipFrames, chunkTotalFrames) = computeBresenhamClipFrames(
                sliceDurationsMs = sliceDurationsMs,
                internalTransitions = internalTrans,
                chunkGlobalStartMs = chunkGlobalStartMs,
                chunkGlobalEndMs = chunkGlobalEndMs,
                fps = safeFps
            )
            val chunkClips = rawChunkClips.mapIndexed { idx, c ->
                c.copy(exactFrames = clipFrames.getOrElse(idx) { 0 })
            }

            chunks.add(
                ChunkSpec(
                    chunkIndex = chunkIdx,
                    clips = chunkClips,
                    internalTransitions = internalTrans,
                    globalStartMs = chunkGlobalStartMs,
                    globalEndMs = chunkGlobalEndMs,
                    durationMs = chunkDurationMs,
                    totalFrames = chunkTotalFrames
                )
            )

            if (nextGlobalIdx < totalClips) {
                if (chunkBoundaryGapIdx != null) {
                    boundaryTransitions.add(
                        ChunkBoundaryTransition(
                            boundaryIndex = chunkIdx,
                            globalGapIndex = chunkBoundaryGapIdx,
                            transition = chunkBoundaryTrans
                        )
                    )
                } else {
                    boundaryTransitions.add(
                        ChunkBoundaryTransition(
                            boundaryIndex = chunkIdx,
                            globalGapIndex = -1,
                            transition = null
                        )
                    )
                }
            }

            val boundaryOverlapMs = if (chunkBoundaryTrans != null) {
                val nextFullDur = getClipFullDurationMs(sequenceItems[nextGlobalIdx])
                chunkBoundaryTrans.durationMs.coerceAtMost(minOf(chunkDurationMs, nextFullDur))
            } else {
                0L
            }
            accumulatedGlobalStartMs = chunkGlobalEndMs - boundaryOverlapMs

            currentGlobalIdx = nextGlobalIdx
            pendingTailStartMs = nextPendingTailStartMs
            chunkIdx++
        }

        return ChunkPlan(
            chunks = chunks,
            boundaryTransitions = boundaryTransitions,
            totalDurationMs = totalDurationMs,
            isChunked = true
        )
    }

    /**
     * Pure function to clamp global overlay or caption time window to a chunk's local time.
     * Handles straddling cues seamlessly:
     * - Cues beginning before chunk and ending during chunk
     * - Cues fully within chunk
     * - Cues beginning during chunk and ending after chunk
     * - Cues entirely outside chunk
     */
    fun clampToChunkWindow(
        cueStartMs: Long,
        cueEndMs: Long,
        chunkGlobalStartMs: Long,
        chunkDurationMs: Long
    ): ClampedTimeWindow {
        val chunkGlobalEndMs = chunkGlobalStartMs + chunkDurationMs
        val overlapStart = maxOf(cueStartMs, chunkGlobalStartMs)
        val overlapEnd = minOf(cueEndMs, chunkGlobalEndMs)

        if (overlapStart >= overlapEnd) {
            return ClampedTimeWindow(
                isVisibleInChunk = false,
                localStartMs = 0L,
                localEndMs = 0L
            )
        }

        val localStartMs = (overlapStart - chunkGlobalStartMs).coerceAtLeast(0L)
        val localEndMs = (overlapEnd - chunkGlobalStartMs).coerceAtMost(chunkDurationMs)

        return ClampedTimeWindow(
            isVisibleInChunk = true,
            localStartMs = localStartMs,
            localEndMs = localEndMs
        )
    }
}

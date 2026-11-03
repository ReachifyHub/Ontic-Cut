package com.boostlyte.vydra.services

import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.viewmodels.TimelineLayoutCalculator
import java.io.Serializable

data class ChunkClip(
    val globalIndex: Int,
    val localIndex: Int,
    val item: EditOperation.MergeItem
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
    val durationMs: Long
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
    val renderPath: String = "legacy"
) : Serializable

data class ChunkExportPlan(
    val isChunked: Boolean,
    val singlePassCommand: String? = null,
    val chunkCommands: List<ChunkCommand> = emptyList(),
    val finalPassCommand: String? = null,
    val chunkTempPaths: List<String> = emptyList(),
    val totalDurationSecs: Double = 0.0,
    val chunkDurationsSecs: List<Double> = emptyList(),
    val renderPath: String = "legacy"
) : Serializable

/**
 * Pure Chunk Planner for large sequence exports (> 24 inputs).
 * Prevents kernel OOM kills on 60+ image projects by planning sub-sequence chunks
 * of at most MAX_CHUNK_SIZE clips.
 */
object ChunkPlanner {

    const val CHUNK_TRIGGER_THRESHOLD = 24
    const val MAX_CHUNK_SIZE = 16

    /**
     * Plans chunks for a given sequence of clips and transitions.
     * If sequenceItems.size <= CHUNK_TRIGGER_THRESHOLD (24), single-pass graph is preserved.
     */
    fun plan(
        sequenceItems: List<EditOperation.MergeItem>,
        transitions: List<EditOperation.Transition>
    ): ChunkPlan {
        val totalLayout = TimelineLayoutCalculator.computeTimelineLayout(sequenceItems, transitions)
        val totalDurationMs = totalLayout.totalDurationMs

        if (sequenceItems.size <= CHUNK_TRIGGER_THRESHOLD) {
            val singleChunkClips = sequenceItems.mapIndexed { idx, item ->
                ChunkClip(globalIndex = idx, localIndex = idx, item = item)
            }
            val singleChunk = ChunkSpec(
                chunkIndex = 0,
                clips = singleChunkClips,
                internalTransitions = transitions,
                globalStartMs = 0L,
                globalEndMs = totalDurationMs,
                durationMs = totalDurationMs
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
        var currentGlobalIdx = 0
        var chunkIdx = 0

        while (currentGlobalIdx < totalClips) {
            val chunkSize = minOf(MAX_CHUNK_SIZE, totalClips - currentGlobalIdx)
            val chunkClipsRaw = sequenceItems.subList(currentGlobalIdx, currentGlobalIdx + chunkSize)

            val chunkClips = chunkClipsRaw.mapIndexed { localIdx, item ->
                ChunkClip(
                    globalIndex = currentGlobalIdx + localIdx,
                    localIndex = localIdx,
                    item = item
                )
            }

            // Internal transitions for this chunk
            val internalTrans = mutableListOf<EditOperation.Transition>()
            for (localGap in 0 until chunkSize - 1) {
                val globalGap = currentGlobalIdx + localGap
                val trans = transitions.find { it.index == globalGap }
                if (trans != null) {
                    // Remap transition index to local gap index
                    internalTrans.add(trans.copy(index = localGap))
                }
            }

            val chunkLayout = TimelineLayoutCalculator.computeTimelineLayout(chunkClipsRaw, internalTrans)
            val chunkDurationMs = chunkLayout.totalDurationMs

            val firstClipGlobalStart = totalLayout.clips.getOrNull(currentGlobalIdx)?.startMs ?: 0L
            val lastClipGlobalEnd = totalLayout.clips.getOrNull(currentGlobalIdx + chunkSize - 1)?.endMs ?: (firstClipGlobalStart + chunkDurationMs)

            chunks.add(
                ChunkSpec(
                    chunkIndex = chunkIdx,
                    clips = chunkClips,
                    internalTransitions = internalTrans,
                    globalStartMs = firstClipGlobalStart,
                    globalEndMs = lastClipGlobalEnd,
                    durationMs = chunkDurationMs
                )
            )

            // Check if there is a boundary transition between this chunk and the next
            val nextGlobalIdx = currentGlobalIdx + chunkSize
            if (nextGlobalIdx < totalClips) {
                val boundaryGap = nextGlobalIdx - 1
                val boundaryTrans = transitions.find { it.index == boundaryGap }
                boundaryTransitions.add(
                    ChunkBoundaryTransition(
                        boundaryIndex = chunkIdx,
                        globalGapIndex = boundaryGap,
                        transition = boundaryTrans
                    )
                )
            }

            currentGlobalIdx = nextGlobalIdx
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

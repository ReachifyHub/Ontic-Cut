package com.tharunbirla.librecuts.autoassemble

import android.net.Uri
import com.boostlyte.vydra.models.SceneBoundary

object MotionEffect {
    const val NONE = "none"
    const val ZOOM_IN = "zoom_in"
    const val ZOOM_OUT = "zoom_out"
    const val PAN_LEFT = "pan_left"
    const val PAN_RIGHT = "pan_right"
    const val DIAGONAL_TL_BR = "diagonal_tl_br"
    const val DIAGONAL_TR_BL = "diagonal_tr_bl"

    /**
     * Deterministic pool of varied Ken Burns motions.
     * Ensures adjacent clips cycle to different motions.
     */
    val POOL = listOf(
        ZOOM_IN,
        PAN_RIGHT,
        ZOOM_OUT,
        PAN_LEFT,
        DIAGONAL_TL_BR,
        DIAGONAL_TR_BL
    )
}

data class AssembledClip(
    val uri: Uri,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val sceneIndex: Int,
    val motionEffect: String = MotionEffect.NONE,
    val isImage: Boolean = true
)

data class SceneDistribution(
    val sceneIndex: Int,
    val sceneTitle: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val isMatched: Boolean,
    val assignedImageCount: Int,
    val assignedImages: List<AssembledClip>
)

sealed class AssemblyPlanResult {
    data class Success(val plan: AssemblyPlan) : AssemblyPlanResult()
    data class TooFewImages(
        val imageCount: Int,
        val sceneCount: Int,
        val requiredCount: Int,
        val message: String
    ) : AssemblyPlanResult()
}

data class AssemblyPlan(
    val scenes: List<SceneDistribution>,
    val totalAudioDurationMs: Long,
    val totalImagesPlaced: Int,
    val matchedSceneCount: Int,
    val interpolatedSceneCount: Int,
    val averageClipDurationMs: Long,
    val isFastPacedWarning: Boolean,
    val warningMessage: String?,
    val limitationNote: String,
    val assembledClips: List<AssembledClip>
)

class TimelineAssemblyEngine {

    companion object {
        const val MIN_CLIP_DURATION_MS = 800L
    }

    /**
     * Builds a continuous coverage model across [0, totalAudioDurationMs].
     * - Matched scenes retain their matched boundaries where valid.
     * - Unmatched scenes interpolate between nearest matched boundaries.
     * - Start of first scene extrapolates to 0 ms; end of last scene extrapolates to totalAudioDurationMs.
     * - Strict monotonicity is enforced: no gaps, no overlaps, and no zero/negative durations.
     */
    fun buildContinuousScenes(
        boundaries: List<SceneBoundary>,
        totalAudioDurationMs: Long
    ): List<SceneBoundary> {
        if (boundaries.isEmpty()) return emptyList()
        val totalMs = totalAudioDurationMs.coerceAtLeast(1L)
        val n = boundaries.size

        if (n == 1) {
            val b = boundaries[0]
            return listOf(
                b.copy(
                    startMs = 0L,
                    endMs = totalMs
                )
            )
        }

        // We determine cut points C_0, C_1, ..., C_n such that:
        // C_0 = 0, C_n = totalMs, and scene i spans [C_i, C_{i+1}].
        val cuts = Array<Long?>(n + 1) { null }
        cuts[0] = 0L
        cuts[n] = totalMs

        for (k in 1 until n) {
            val prev = boundaries[k - 1]
            val next = boundaries[k]
            when {
                prev.isMatched && next.isMatched -> {
                    // Transition midpoint between scenes
                    cuts[k] = (prev.endMs + next.startMs) / 2L
                }
                prev.isMatched && !next.isMatched -> {
                    cuts[k] = prev.endMs
                }
                !prev.isMatched && next.isMatched -> {
                    cuts[k] = next.startMs
                }
                else -> {
                    cuts[k] = null // to be interpolated
                }
            }
        }

        // Interpolate any null cut points between surrounding known anchors
        var idx = 0
        while (idx <= n) {
            if (cuts[idx] == null) {
                val startAnchor = idx - 1
                var endAnchor = idx + 1
                while (endAnchor <= n && cuts[endAnchor] == null) {
                    endAnchor++
                }
                val startVal = cuts[startAnchor] ?: 0L
                val endVal = cuts[endAnchor] ?: totalMs
                val steps = endAnchor - startAnchor
                for (j in (startAnchor + 1) until endAnchor) {
                    val stepFraction = (j - startAnchor).toDouble() / steps.toDouble()
                    cuts[j] = (startVal + (endVal - startVal) * stepFraction).toLong()
                }
                idx = endAnchor
            } else {
                idx++
            }
        }

        // Concrete cuts array (all filled)
        val concreteCuts = LongArray(n + 1) { cuts[it] ?: 0L }

        // Forward pass: ensure strictly increasing by at least 1ms
        for (i in 1..n) {
            if (concreteCuts[i] <= concreteCuts[i - 1]) {
                concreteCuts[i] = concreteCuts[i - 1] + 1L
            }
        }

        // Backward pass: ensure does not exceed totalMs and leaves room for subsequent scenes
        for (i in (n - 1) downTo 0) {
            if (concreteCuts[i] >= concreteCuts[i + 1]) {
                concreteCuts[i] = concreteCuts[i + 1] - 1L
            }
        }

        // Final normalization if totalMs was smaller than n ms
        if (concreteCuts[0] < 0L || concreteCuts[n] != totalMs) {
            concreteCuts[0] = 0L
            for (i in 1 until n) {
                concreteCuts[i] = (totalMs * i) / n
                if (concreteCuts[i] <= concreteCuts[i - 1]) {
                    concreteCuts[i] = concreteCuts[i - 1] + 1L
                }
            }
            concreteCuts[n] = totalMs
        }

        return boundaries.mapIndexed { i, b ->
            b.copy(
                startMs = concreteCuts[i],
                endMs = concreteCuts[i + 1]
            )
        }
    }

    /**
     * Calculates the full timeline assembly plan:
     * 1. Validates preconditions (images >= scenes).
     * 2. Builds continuous scenes from 0 to totalAudioDurationMs.
     * 3. Proportionally assigns images to scenes (min 1 per scene).
     * 4. Sequentially maps images to scene time intervals with exact millisecond math.
     * 5. Assigns deterministic Ken Burns motion effects.
     */
    fun calculateAssemblyPlan(
        boundaries: List<SceneBoundary>,
        availableImages: List<Uri>,
        totalAudioDurationMs: Long
    ): AssemblyPlanResult {
        val totalMs = totalAudioDurationMs.coerceAtLeast(1L)
        val sceneCount = boundaries.size
        val imageCount = availableImages.size

        if (imageCount < sceneCount) {
            val missing = sceneCount - imageCount
            val msg = "Auto Assemble requires at least one image per scene. You have $imageCount images for $sceneCount scenes. Please import at least $missing more images or reduce the number of scenes."
            return AssemblyPlanResult.TooFewImages(
                imageCount = imageCount,
                sceneCount = sceneCount,
                requiredCount = sceneCount,
                message = msg
            )
        }

        val continuousScenes = buildContinuousScenes(boundaries, totalMs)
        val remainingImages = imageCount - sceneCount

        // Distribute remaining images using the largest remainder method
        val extraAllocations = IntArray(sceneCount) { 0 }
        if (remainingImages > 0) {
            val quotas = DoubleArray(sceneCount) { i ->
                val sceneDur = continuousScenes[i].endMs - continuousScenes[i].startMs
                remainingImages.toDouble() * (sceneDur.toDouble() / totalMs.toDouble())
            }

            var allocatedCount = 0
            val remainders = mutableListOf<Pair<Int, Double>>()

            for (i in 0 until sceneCount) {
                val baseExtra = quotas[i].toInt()
                extraAllocations[i] = baseExtra
                allocatedCount += baseExtra
                remainders.add(i to (quotas[i] - baseExtra))
            }

            var leftover = remainingImages - allocatedCount
            remainders.sortByDescending { it.second }

            for (pair in remainders) {
                if (leftover <= 0) break
                extraAllocations[pair.first]++
                leftover--
            }
        }

        val sceneImageCounts = IntArray(sceneCount) { 1 + extraAllocations[it] }

        // Sequential placement of images across scenes
        val sceneDistributions = mutableListOf<SceneDistribution>()
        val allAssembledClips = mutableListOf<AssembledClip>()
        var globalImageIdx = 0

        for (i in 0 until sceneCount) {
            val scene = continuousScenes[i]
            val sceneDur = scene.endMs - scene.startMs
            val assignedCount = sceneImageCounts[i]
            val sceneClips = mutableListOf<AssembledClip>()

            for (k in 0 until assignedCount) {
                val clipStart = scene.startMs + (k.toLong() * sceneDur) / assignedCount.toLong()
                val clipEnd = if (k == assignedCount - 1) {
                    scene.endMs
                } else {
                    scene.startMs + ((k + 1).toLong() * sceneDur) / assignedCount.toLong()
                }
                val clipDur = (clipEnd - clipStart).coerceAtLeast(1L)
                val imgUri = availableImages[globalImageIdx]
                val motion = MotionEffect.POOL[globalImageIdx % MotionEffect.POOL.size]

                val clip = AssembledClip(
                    uri = imgUri,
                    durationMs = clipDur,
                    startMs = clipStart,
                    endMs = clipEnd,
                    sceneIndex = scene.sceneIndex,
                    motionEffect = motion,
                    isImage = true
                )
                sceneClips.add(clip)
                allAssembledClips.add(clip)
                globalImageIdx++
            }

            sceneDistributions.add(
                SceneDistribution(
                    sceneIndex = scene.sceneIndex,
                    sceneTitle = scene.sceneTitle,
                    startMs = scene.startMs,
                    endMs = scene.endMs,
                    durationMs = sceneDur,
                    isMatched = scene.isMatched,
                    assignedImageCount = assignedCount,
                    assignedImages = sceneClips
                )
            )
        }

        // Remainder compensation: verify exact millisecond sum to totalMs
        val currentSum = allAssembledClips.sumOf { it.durationMs }
        val diff = totalMs - currentSum
        if (diff != 0L && allAssembledClips.isNotEmpty()) {
            val lastIdx = allAssembledClips.size - 1
            val lastClip = allAssembledClips[lastIdx]
            val newDur = (lastClip.durationMs + diff).coerceAtLeast(1L)
            allAssembledClips[lastIdx] = lastClip.copy(
                durationMs = newDur,
                endMs = totalMs
            )
        }

        val matchedCount = continuousScenes.count { it.isMatched }
        val interpolatedCount = sceneCount - matchedCount
        val avgClipDurationMs = totalMs / imageCount.coerceAtLeast(1)
        val isFastPaced = avgClipDurationMs < MIN_CLIP_DURATION_MS
        val warningMsg = if (isFastPaced) {
            val secs = (totalMs + 999) / 1000
            "Warning: With $imageCount images across ${secs}s, the average clip duration is only ${avgClipDurationMs}ms, which may feel rushed. Proceed anyway?"
        } else null

        val syncNote = "Plain cuts are used to ensure 100% frame-accurate synchronization with your voiceover audio."

        return AssemblyPlanResult.Success(
            AssemblyPlan(
                scenes = sceneDistributions,
                totalAudioDurationMs = totalMs,
                totalImagesPlaced = allAssembledClips.size,
                matchedSceneCount = matchedCount,
                interpolatedSceneCount = interpolatedCount,
                averageClipDurationMs = avgClipDurationMs,
                isFastPacedWarning = isFastPaced,
                warningMessage = warningMsg,
                limitationNote = syncNote,
                assembledClips = allAssembledClips
            )
        )
    }
}

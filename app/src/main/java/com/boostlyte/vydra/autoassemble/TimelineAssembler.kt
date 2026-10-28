package com.boostlyte.vydra.autoassemble

import android.net.Uri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.SceneBoundary
import com.boostlyte.vydra.models.VideoProject
import java.io.Serializable
import kotlin.math.floor

/**
 * Defines the pool of subtle Ken Burns motion effects.
 */
enum class KenBurnsMotion(val key: String, val label: String) {
    SLOW_ZOOM_IN("zoom_in", "Slow Zoom In"),
    PAN_LEFT("pan_left", "Pan Left"),
    SLOW_ZOOM_OUT("zoom_out", "Slow Zoom Out"),
    PAN_RIGHT("pan_right", "Pan Right"),
    DIAGONAL_DRIFT("drift_up_right", "Diagonal Drift"),
    PAN_UP("pan_up", "Pan Up"),
    PAN_DOWN("pan_down", "Pan Down");

    companion object {
        val MOTION_POOL = listOf(
            SLOW_ZOOM_IN,
            PAN_LEFT,
            SLOW_ZOOM_OUT,
            PAN_RIGHT,
            DIAGONAL_DRIFT,
            PAN_UP,
            SLOW_ZOOM_IN,
            PAN_DOWN
        )

        /**
         * Deterministically selects a motion effect from the pool such that
         * consecutive clips always receive varied effects.
         */
        fun getMotionForIndex(index: Int, seed: Long = 42L): KenBurnsMotion {
            val poolSize = MOTION_POOL.size
            val offset = ((seed % poolSize + poolSize) % poolSize).toInt()
            return MOTION_POOL[(index + offset) % poolSize]
        }
    }
}

/**
 * Represents a scene with guaranteed continuous temporal coverage.
 */
data class SceneCoverage(
    val sceneIndex: Int,
    val sceneTitle: String,
    val startMs: Long,
    val endMs: Long,
    val isMatched: Boolean
) : Serializable {
    val durationMs: Long
        get() = endMs - startMs
}

/**
 * Represents an image clip assigned to the timeline sequence.
 */
data class AssembledClip(
    val uri: Uri,
    val durationMs: Long,
    val sceneIndex: Int,
    val motionEffect: String,
    val isPrimary: Boolean
) : Serializable

/**
 * Complete plan for assembling image clips across script scenes.
 */
data class AssemblyPlan(
    val sceneCoverages: List<SceneCoverage>,
    val clips: List<AssembledClip>,
    val totalAudioDurationMs: Long,
    val matchedScenesCount: Int,
    val unmatchedScenesCount: Int,
    val imagesPerScene: List<Int>,
    val averageClipDurationMs: Long,
    val isTooFewImages: Boolean,
    val isShortClipWarning: Boolean,
    val hasInvalidBoundariesWarning: Boolean = false,
    val errorMessage: String? = null
) : Serializable {
    val totalScenes: Int get() = sceneCoverages.size
    val totalImages: Int get() = clips.size
    val totalClipsDurationMs: Long get() = clips.sumOf { it.durationMs }
}

/**
 * Phase 5 Assembly Engine:
 * Converts SceneBoundary list and imported image clips into an edited timeline.
 */
object TimelineAssembler {

    const val MIN_CLIP_DURATION_MS: Long = 800L

    /**
     * Builds a continuous temporal coverage model from 0 ms to audioDurationMs:
     * - Matched scenes use their matched start/end boundaries.
     * - Unmatched scenes (isMatched = false) are interpolated between the nearest matched neighbors.
     * - If the first or last scene is unmatched, extrapolates to 0 ms or to the audio end respectively.
     * - Guaranteed: no gaps, no overlaps, and strictly positive durations (>= 1ms).
     */
    fun buildContinuousSceneCoverage(
        scenes: List<SceneBoundary>,
        audioDurationMs: Long
    ): List<SceneCoverage> {
        if (scenes.isEmpty() || audioDurationMs <= 0L) return emptyList()

        val k = scenes.size
        // 1. If only one scene, it covers 0 to audioDurationMs
        if (k == 1) {
            val s = scenes[0]
            return listOf(
                SceneCoverage(
                    sceneIndex = s.sceneIndex,
                    sceneTitle = s.sceneTitle,
                    startMs = 0L,
                    endMs = audioDurationMs,
                    isMatched = s.isMatched
                )
            )
        }

        // 2. Identify and sanitize valid matched scenes
        val matchedIndices = mutableListOf<Int>()
        for (i in 0 until k) {
            val sc = scenes[i]
            if (sc.isMatched && sc.endMs > sc.startMs && sc.startMs < audioDurationMs) {
                matchedIndices.add(i)
            }
        }

        // Case A: No valid matched scenes -> divide duration evenly
        if (matchedIndices.isEmpty()) {
            val baseDur = audioDurationMs / k
            val remainder = (audioDurationMs % k).toInt()
            var t = 0L
            val result = mutableListOf<SceneCoverage>()
            for (i in 0 until k) {
                val dur = baseDur + if (i == k - 1) remainder else 0
                val start = t
                val end = t + dur
                t = end
                result.add(
                    SceneCoverage(
                        sceneIndex = scenes[i].sceneIndex,
                        sceneTitle = scenes[i].sceneTitle,
                        startMs = start,
                        endMs = end,
                        isMatched = false
                    )
                )
            }
            return result
        }

        // Case B: Some scenes matched.
        // Array of preliminary start and end bounds
        val startBounds = LongArray(k) { -1L }
        val endBounds = LongArray(k) { -1L }

        // Sanitize matched scenes for monotonicity
        for (idx in matchedIndices) {
            val sc = scenes[idx]
            startBounds[idx] = sc.startMs.coerceIn(0L, audioDurationMs)
            endBounds[idx] = sc.endMs.coerceIn(0L, audioDurationMs)
        }

        // Enforce monotonicity among matched scenes
        for (m in 0 until matchedIndices.size - 1) {
            val currIdx = matchedIndices[m]
            val nextIdx = matchedIndices[m + 1]
            if (endBounds[currIdx] > startBounds[nextIdx]) {
                val mid = (endBounds[currIdx] + startBounds[nextIdx]) / 2
                endBounds[currIdx] = mid
                startBounds[nextIdx] = mid
            }
        }

        val firstMatched = matchedIndices.first()
        val lastMatched = matchedIndices.last()

        // 1. Extrapolate leading unmatched scenes (0 until firstMatched) to 0 ms
        if (firstMatched > 0) {
            val targetStart = startBounds[firstMatched].coerceAtLeast(firstMatched.toLong())
            startBounds[firstMatched] = targetStart
            val span = targetStart
            val count = firstMatched
            val perScene = span / count
            val rem = (span % count).toInt()
            var cur = 0L
            for (i in 0 until firstMatched) {
                val dur = perScene + if (i == firstMatched - 1) rem else 0
                startBounds[i] = cur
                endBounds[i] = cur + dur
                cur += dur
            }
        } else {
            // First scene is matched -> extrapolate start to 0 ms
            startBounds[0] = 0L
        }

        // 2. Interpolate between matched scenes
        for (m in 0 until matchedIndices.size - 1) {
            val leftMatched = matchedIndices[m]
            val rightMatched = matchedIndices[m + 1]
            val unmatchedCount = rightMatched - leftMatched - 1

            if (unmatchedCount > 0) {
                val spanStart = endBounds[leftMatched]
                val spanEnd = startBounds[rightMatched]
                val span = (spanEnd - spanStart).coerceAtLeast(unmatchedCount.toLong())
                val perScene = span / unmatchedCount
                val rem = (span % unmatchedCount).toInt()
                var cur = spanStart
                for (i in leftMatched + 1 until rightMatched) {
                    val isLast = (i == rightMatched - 1)
                    val dur = perScene + if (isLast) rem else 0
                    startBounds[i] = cur
                    endBounds[i] = cur + dur
                    cur += dur
                }
            } else {
                // Adjacent matched scenes: close gap or overlap
                if (endBounds[leftMatched] != startBounds[rightMatched]) {
                    endBounds[leftMatched] = startBounds[rightMatched]
                }
            }
        }

        // 3. Extrapolate trailing unmatched scenes (lastMatched + 1 until k) to audioDurationMs
        if (lastMatched < k - 1) {
            val spanStart = endBounds[lastMatched].coerceAtMost(audioDurationMs - (k - 1 - lastMatched))
            endBounds[lastMatched] = spanStart
            val unmatchedCount = k - 1 - lastMatched
            val span = (audioDurationMs - spanStart).coerceAtLeast(unmatchedCount.toLong())
            val perScene = span / unmatchedCount
            val rem = (span % unmatchedCount).toInt()
            var cur = spanStart
            for (i in lastMatched + 1 until k) {
                val isLast = (i == k - 1)
                val dur = perScene + if (isLast) rem else 0
                startBounds[i] = cur
                endBounds[i] = cur + dur
                cur += dur
            }
        } else {
            // Last scene is matched -> extrapolate end to audioDurationMs
            endBounds[lastMatched] = audioDurationMs
        }

        // Final normalization pass: guarantee continuous coverage 0 to audioDurationMs without gaps or overlaps
        startBounds[0] = 0L
        for (i in 1 until k) {
            startBounds[i] = endBounds[i - 1]
            if (endBounds[i] <= startBounds[i]) {
                endBounds[i] = startBounds[i] + 1L
            }
        }
        endBounds[k - 1] = audioDurationMs

        // Build SceneCoverage list
        val coverages = mutableListOf<SceneCoverage>()
        for (i in 0 until k) {
            coverages.add(
                SceneCoverage(
                    sceneIndex = scenes[i].sceneIndex,
                    sceneTitle = scenes[i].sceneTitle,
                    startMs = startBounds[i],
                    endMs = endBounds[i],
                    isMatched = scenes[i].isMatched
                )
            )
        }
        return coverages
    }

    /**
     * Distributes totalImages across scenes proportionally to each scene's duration:
     * - Every scene receives at least 1 image.
     * - Hamilton-Hare / Largest Remainder method fixes rounding so total assigned == total available.
     * - Returns an integer list where element i is the count of images assigned to scene i.
     */
    fun distributeImagesProportionally(
        scenes: List<SceneCoverage>,
        totalImages: Int,
        audioDurationMs: Long
    ): List<Int> {
        val k = scenes.size
        if (k == 0 || totalImages < k || audioDurationMs <= 0L) {
            return emptyList()
        }

        // Each scene receives a minimum of 1 image
        val counts = IntArray(k) { 1 }
        val remainingImages = totalImages - k

        if (remainingImages > 0) {
            val remainders = DoubleArray(k)
            var assignedCount = 0

            for (i in 0 until k) {
                val sceneDur = scenes[i].durationMs.coerceAtLeast(1L)
                val exactShare = (remainingImages.toDouble() * sceneDur.toDouble()) / audioDurationMs.toDouble()
                val integerShare = floor(exactShare).toInt()
                counts[i] += integerShare
                assignedCount += integerShare
                remainders[i] = exactShare - integerShare
            }

            var leftover = remainingImages - assignedCount
            if (leftover > 0) {
                // Distribute leftover to scenes with the largest fractional remainders
                val sortedIndices = (0 until k).sortedWith(
                    compareByDescending<Int> { remainders[it] }.thenBy { it }
                )
                for (i in 0 until leftover) {
                    counts[sortedIndices[i]] += 1
                }
            }
        }

        return counts.toList()
    }

    /**
     * Computes the complete timeline assembly plan:
     * 1. Validates image counts against scene counts.
     * 2. Builds continuous temporal scene coverage from 0 ms to audioDurationMs.
     * 3. Proportionally assigns images to scenes (preserving sequential image order).
     * 4. Divides each scene duration evenly among its assigned images.
     * 5. Assigns deterministic varied Ken Burns motion effects to every clip.
     * 6. Ensures exact millisecond coverage matching audioDurationMs down to integer precision.
     */
    fun computeAssemblyPlan(
        scenes: List<SceneBoundary>,
        imageUris: List<Uri>,
        audioDurationMs: Long,
        seed: Long = 42L
    ): AssemblyPlan {
        if (scenes.isEmpty()) {
            return AssemblyPlan(
                sceneCoverages = emptyList(),
                clips = emptyList(),
                totalAudioDurationMs = audioDurationMs,
                matchedScenesCount = 0,
                unmatchedScenesCount = 0,
                imagesPerScene = emptyList(),
                averageClipDurationMs = 0L,
                isTooFewImages = true,
                isShortClipWarning = false,
                errorMessage = "No scenes provided for assembly."
            )
        }

        val totalScenes = scenes.size
        val totalImages = imageUris.size
        val matchedCount = scenes.count { it.isMatched }
        val unmatchedCount = totalScenes - matchedCount

        // Edge Case: Too few images
        if (totalImages < totalScenes) {
            return AssemblyPlan(
                sceneCoverages = emptyList(),
                clips = emptyList(),
                totalAudioDurationMs = audioDurationMs,
                matchedScenesCount = matchedCount,
                unmatchedScenesCount = unmatchedCount,
                imagesPerScene = emptyList(),
                averageClipDurationMs = if (totalImages > 0) audioDurationMs / totalImages else 0L,
                isTooFewImages = true,
                isShortClipWarning = false,
                errorMessage = "You have $totalScenes scenes but only $totalImages images. Each scene needs at least 1 image."
            )
        }

        // 1. Build continuous coverage model
        val coverages = buildContinuousSceneCoverage(scenes, audioDurationMs)

        // 2. Distribute images proportionally across scenes
        val imagesPerScene = distributeImagesProportionally(coverages, totalImages, audioDurationMs)

        // 3. Divide scene durations among assigned images
        val assembledClips = mutableListOf<AssembledClip>()
        var globalImageIdx = 0

        for (sceneIdx in 0 until totalScenes) {
            val scene = coverages[sceneIdx]
            val sceneDur = scene.durationMs
            val numImagesInScene = imagesPerScene[sceneIdx]

            val baseClipDur = sceneDur / numImagesInScene
            val remainderMs = (sceneDur % numImagesInScene).toInt()

            for (clipInSceneIdx in 0 until numImagesInScene) {
                val uri = imageUris[globalImageIdx]
                // Place remainder in the last clip of the scene
                val isLastInScene = (clipInSceneIdx == numImagesInScene - 1)
                val clipDur = baseClipDur + if (isLastInScene) remainderMs.toLong() else 0L
                val motion = KenBurnsMotion.getMotionForIndex(globalImageIdx, seed).key

                assembledClips.add(
                    AssembledClip(
                        uri = uri,
                        durationMs = clipDur,
                        sceneIndex = scene.sceneIndex,
                        motionEffect = motion,
                        isPrimary = (globalImageIdx == 0)
                    )
                )
                globalImageIdx++
            }
        }

        // 4. Exact coverage math enforcement:
        // Distribute any final integer rounding remainder into the final clip of the final scene
        val sumGeneratedDuration = assembledClips.sumOf { it.durationMs }
        val totalDiff = audioDurationMs - sumGeneratedDuration
        if (totalDiff != 0L && assembledClips.isNotEmpty()) {
            val lastIdx = assembledClips.size - 1
            val last = assembledClips[lastIdx]
            assembledClips[lastIdx] = last.copy(durationMs = (last.durationMs + totalDiff).coerceAtLeast(1L))
        }

        // Guard against collapsed / invalid boundaries
        val hasInvalidBoundaries = scenes.any { it.isMatched } && (
            scenes.filter { it.isMatched }.all { it.endMs <= it.startMs } ||
            (audioDurationMs > 5000L && scenes.filter { it.isMatched }.sumOf { it.endMs - it.startMs } < 1000L)
        )

        val avgClipDuration = if (assembledClips.isNotEmpty()) {
            assembledClips.map { it.durationMs }.average().toLong()
        } else if (totalImages > 0) {
            audioDurationMs / totalImages
        } else 0L

        val isShortClipWarning = avgClipDuration < MIN_CLIP_DURATION_MS || assembledClips.any { it.durationMs < MIN_CLIP_DURATION_MS }

        return AssemblyPlan(
            sceneCoverages = coverages,
            clips = assembledClips,
            totalAudioDurationMs = audioDurationMs,
            matchedScenesCount = matchedCount,
            unmatchedScenesCount = unmatchedCount,
            imagesPerScene = imagesPerScene,
            averageClipDurationMs = avgClipDuration,
            isTooFewImages = false,
            isShortClipWarning = isShortClipWarning,
            hasInvalidBoundariesWarning = hasInvalidBoundaries
        )
    }

    /**
     * Applies the computed assembly plan to the VideoProject:
     * - Replaces main track clips with the assembled image clips.
     * - Voiceover audio track is left completely untouched.
     * - Motion parameters and keyframes are assigned to each clip.
     * - Consecutive clips use plain cuts to preserve exact duration and audio sync.
     */
    fun applyAssemblyToProject(
        project: VideoProject,
        plan: AssemblyPlan
    ): VideoProject {
        if (plan.clips.isEmpty()) return project

        val clips = plan.clips
        val primaryClip = clips[0]

        // Prepare merge items for secondary clips (indices 1 until N)
        val mergeItems = mutableListOf<EditOperation.MergeItem>()
        for (i in 1 until clips.size) {
            val clip = clips[i]
            mergeItems.add(
                EditOperation.MergeItem(
                    uri = clip.uri,
                    durationMs = clip.durationMs,
                    trimStartMs = 0L,
                    trimEndMs = clip.durationMs,
                    speed = 1.0f,
                    isImage = true,
                    motionEffect = clip.motionEffect
                )
            )
        }

        // Filter operations: keep background audio (voiceover untouched), text/image overlays, and backgrounds
        val preservedOps = project.operations.filter { op ->
            op is EditOperation.AddBackgroundAudio ||
            op is EditOperation.AddText ||
            op is EditOperation.AddImageOverlay ||
            op is EditOperation.CanvasBackground ||
            op is EditOperation.AddSubtitles
        }.toMutableList()

        // 1. Primary trim operation for clip 0
        preservedOps.add(
            EditOperation.Trim(
                startMs = 0L,
                endMs = primaryClip.durationMs
            )
        )

        // 2. Add Merge operation if there are multiple clips
        if (mergeItems.isNotEmpty()) {
            preservedOps.add(EditOperation.Merge(items = mergeItems))
        }

        // 3. Add MotionEffect operations for all clips
        for (i in clips.indices) {
            preservedOps.add(
                EditOperation.MotionEffect(
                    index = i,
                    motionType = clips[i].motionEffect
                )
            )
        }

        return project.copy(
            sourceUri = primaryClip.uri,
            sourceName = primaryClip.uri.lastPathSegment ?: "image_0",
            operations = preservedOps,
            lastModifiedAt = System.currentTimeMillis()
        )
    }
}

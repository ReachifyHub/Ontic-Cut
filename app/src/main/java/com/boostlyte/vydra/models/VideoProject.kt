package com.boostlyte.vydra.models

import android.net.Uri
import java.io.Serializable

import kotlin.math.abs

/**
 * Global aspect ratio for the video project canvas.
 */
enum class CanvasAspectRatio(val ratioX: Int, val ratioY: Int, val label: String) {
    LANDSCAPE_16_9(16, 9, "16:9"),
    PORTRAIT_9_16(9, 16, "9:16"),
    SQUARE_1_1(1, 1, "1:1");

    val aspectRatio: Float get() = ratioX.toFloat() / ratioY.toFloat()

    /**
     * Calculates width and height for a given base resolution (e.g., 720, 1080),
     * ensuring both dimensions are even integers for FFmpeg x264/MediaCodec encoding.
     */
    fun getTargetDimensions(resolution: Int): Pair<Int, Int> {
        val res = resolution.coerceAtLeast(240)
        return when (this) {
            LANDSCAPE_16_9 -> {
                val height = (res / 2) * 2
                val width = (((height.toDouble() * 16.0 / 9.0).toInt()) / 2) * 2
                Pair(width, height)
            }
            PORTRAIT_9_16 -> {
                val width = (res / 2) * 2
                val height = (((width.toDouble() * 16.0 / 9.0).toInt()) / 2) * 2
                Pair(width, height)
            }
            SQUARE_1_1 -> {
                val dim = (res / 2) * 2
                Pair(dim, dim)
            }
        }
    }

    companion object {
        fun fromDimensions(width: Int, height: Int): CanvasAspectRatio {
            if (width <= 0 || height <= 0) return LANDSCAPE_16_9
            val ratio = width.toFloat() / height.toFloat()
            val diff16_9 = abs(ratio - (16f / 9f))
            val diff9_16 = abs(ratio - (9f / 16f))
            val diff1_1 = abs(ratio - 1f)

            return when {
                diff9_16 < diff16_9 && diff9_16 < diff1_1 -> PORTRAIT_9_16
                diff1_1 < diff16_9 && diff1_1 < diff9_16 -> SQUARE_1_1
                else -> LANDSCAPE_16_9
            }
        }
    }
}

/**
 * VideoProject represents the complete state of a video editing session.
 * It stores all pending operations without rendering them to disk.
 * The original source video remains untouched; all edits are stored as operation objects.
 */
data class VideoProject(
    val sourceUri: Uri,
    val sourceName: String,
    val scrubProxyUri: Uri? = null,
    val operations: List<EditOperation> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val canvasAspectRatio: CanvasAspectRatio? = null
) : Serializable {

    /**
     * Resolves the effective canvas aspect ratio, defaulting to source dimensions
     * or 16:9 landscape if not explicitly specified.
     */
    fun getEffectiveCanvasAspectRatio(sourceWidth: Int = 0, sourceHeight: Int = 0): CanvasAspectRatio {
        return canvasAspectRatio ?: if (sourceWidth > 0 && sourceHeight > 0) {
            CanvasAspectRatio.fromDimensions(sourceWidth, sourceHeight)
        } else {
            CanvasAspectRatio.LANDSCAPE_16_9
        }
    }
    
    /**
     * Returns the total video duration that would be rendered.
     * This is computed during the final export and is not stored.
     */
    fun getDurationAfterTrims(): Long? {
        // This will be computed when building the final FFmpeg command
        var effectiveDuration: Long? = null
        
        for (operation in operations) {
            if (operation is EditOperation.Trim) {
                effectiveDuration = operation.endMs - operation.startMs
                // Note: only the last trim is considered; in a real scenario,
                // you'd chain them if multiple trims exist
            }
        }
        
        return effectiveDuration
    }
    
    /**
     * Returns a copy of this project with a new operation added.
     */
    fun addOperation(operation: EditOperation): VideoProject {
        return copy(
            operations = operations + operation,
            lastModifiedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Returns a copy of this project with the last operation removed.
     */
    fun undoLastOperation(): VideoProject? {
        if (operations.isEmpty()) return null
        return copy(
            operations = operations.dropLast(1),
            lastModifiedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Returns a copy of this project with all operations of a given type removed.
     */
    fun removeOperationsOfType(operationType: Class<out EditOperation>): VideoProject {
        return copy(
            operations = operations.filterNot { it::class.java == operationType },
            lastModifiedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Returns true if this project has any pending operations.
     */
    fun hasOperations(): Boolean = operations.isNotEmpty()
    
    /**
     * Returns the count of pending operations.
     */
    fun getOperationCount(): Int = operations.size
    
    /**
     * Returns the count of a specific operation type.
     */
    fun getOperationCount(operationType: Class<out EditOperation>): Int {
        return operations.count { it::class.java == operationType }
    }
}

/**
 * EditRecipe represents a serializable, persistent version of a VideoProject.
 * Can be saved to disk as JSON and restored later, enabling "Save Project" functionality.
 */
data class EditRecipe(
    val projectName: String,
    val sourceUri: Uri,
    val sourceName: String,
    val scrubProxyUri: Uri? = null,
    val operations: List<EditOperation> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val clipCount: Int = 1,
    val canvasAspectRatio: CanvasAspectRatio? = null
) : Serializable {
    
    /**
     * Converts this EditRecipe back to a VideoProject for editing.
     */
    fun toVideoProject(): VideoProject {
        return VideoProject(
            sourceUri = sourceUri,
            sourceName = sourceName,
            scrubProxyUri = scrubProxyUri,
            operations = operations,
            createdAt = createdAt,
            lastModifiedAt = lastModifiedAt,
            canvasAspectRatio = canvasAspectRatio
        )
    }
    
    companion object {
        /**
         * Create an EditRecipe from a VideoProject.
         */
        fun fromVideoProject(projectName: String, project: VideoProject, durationMs: Long = 0L, clipCount: Int = 1): EditRecipe {
            val mergeOps = project.operations.filterIsInstance<EditOperation.Merge>()
            val calculatedClips = 1 + mergeOps.sumOf { it.items.size }
            val finalClipCount = if (clipCount > 1) clipCount else calculatedClips
            val calculatedDuration = if (durationMs > 0L) durationMs else (project.getDurationAfterTrims() ?: 0L)

            return EditRecipe(
                projectName = projectName,
                sourceUri = project.sourceUri,
                sourceName = project.sourceName,
                scrubProxyUri = project.scrubProxyUri,
                operations = project.operations,
                createdAt = project.createdAt,
                lastModifiedAt = project.lastModifiedAt,
                durationMs = calculatedDuration,
                clipCount = finalClipCount,
                canvasAspectRatio = project.canvasAspectRatio
            )
        }
    }
}

/**
 * Represents the UI state for the video editing session.
 * Separates state management concerns from business logic.
 */
data class VideoEditingUiState(
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val exportProgress: Int = 0, // 0-100
    val errorMessage: String? = null,
    val pendingOperationCount: Int = 0,
    val currentPreviewOperationIndex: Int = -1, // -1 means show original, 0+ means after Nth operation
    val canUndo: Boolean = false,
    val canRedo: Boolean = false // Future enhancement
)

/**
 * Represents export/rendering configuration options.
 */
data class ExportConfig(
    val outputPath: String,
    val videoCodec: String = "libx264", // or "copy" for no re-encoding
    val audioCodec: String = "aac",
    val bitrate: String = "2M",
    val preset: String = "medium", // fast, medium, slow for quality/speed tradeoff
    val frameRate: String = "30" // Can be extracted from source if needed
)

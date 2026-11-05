package com.boostlyte.vydra.export

import android.os.StatFs
import android.util.Log
import java.io.File

/**
 * Utility to conservatively check and estimate storage requirements before beginning export.
 * Prevents device out-of-disk hard crashes during large 100-500 clip exports.
 */
object StorageEstimator {

    private const val TAG = "StorageEstimator"

    data class StorageCheckResult(
        val isSufficient: Boolean,
        val requiredBytes: Long,
        val availableBytes: Long,
        val message: String? = null
    )

    fun estimateRequiredBytes(
        durationSecs: Double,
        resolution: Int,
        isChunked: Boolean
    ): Long {
        val bitrateBitsPerSec = when (resolution) {
            2160 -> 30_000_000L
            1440 -> 16_000_000L
            1080 -> 8_000_000L
            720 -> 5_000_000L
            480 -> 2_000_000L
            else -> 1_500_000L
        }
        val bytesPerSec = bitrateBitsPerSec / 8L
        val estimatedFinalVideoBytes = (durationSecs * bytesPerSec).toLong()

        // If chunked, all chunk temp videos exist on disk until final pass finishes
        val chunkTempVideosBytes = if (isChunked) estimatedFinalVideoBytes else 0L

        // Active chunk GL motion intermediates: at most 10 clips at ~10MB each
        val activeChunkMotionBufferBytes = 100_000_000L // 100 MB buffer

        // Base safety buffer (for OS and filesystem metadata)
        val baseSafetyBufferBytes = 100_000_000L // 100 MB

        val totalEstimated = (estimatedFinalVideoBytes * 1.25).toLong() +
                chunkTempVideosBytes +
                activeChunkMotionBufferBytes +
                baseSafetyBufferBytes

        return maxOf(totalEstimated, 150_000_000L) // Minimum 150 MB requirement
    }

    fun checkStorageAvailable(
        directory: File,
        durationSecs: Double,
        resolution: Int,
        isChunked: Boolean
    ): StorageCheckResult {
        return try {
            val stat = StatFs(directory.absolutePath)
            val availableBytes = stat.availableBytes
            val requiredBytes = estimateRequiredBytes(durationSecs, resolution, isChunked)

            if (availableBytes < requiredBytes) {
                val reqMb = requiredBytes / (1024 * 1024)
                val availMb = availableBytes / (1024 * 1024)
                val msg = "Insufficient storage space for export. Required: ~${reqMb} MB, Available: ${availMb} MB."
                Log.w(TAG, msg)
                StorageCheckResult(
                    isSufficient = false,
                    requiredBytes = requiredBytes,
                    availableBytes = availableBytes,
                    message = msg
                )
            } else {
                Log.d(TAG, "Storage check passed. Available: ${availableBytes / (1024 * 1024)} MB, Required: ~${requiredBytes / (1024 * 1024)} MB")
                StorageCheckResult(
                    isSufficient = true,
                    requiredBytes = requiredBytes,
                    availableBytes = availableBytes
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking storage with StatFs: ${e.message}, allowing export to proceed")
            StorageCheckResult(
                isSufficient = true,
                requiredBytes = 0L,
                availableBytes = Long.MAX_VALUE
            )
        }
    }
}

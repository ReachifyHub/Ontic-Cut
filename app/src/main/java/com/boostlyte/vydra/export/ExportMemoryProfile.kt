package com.boostlyte.vydra.export

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.ceil

/**
 * Memory-aware export sizing profile tailored for constrained Android devices
 * (e.g. 2GB RAM Go/entry devices) without silently lowering user-selected
 * export resolution or frame rate.
 */
data class ExportMemoryProfile(
    val totalMemBytes: Long,
    val availMemBytes: Long,
    val isLowMemoryFlag: Boolean,
    val isLowRamDevice: Boolean,
    val isLowMemoryTier: Boolean,
    val isMidMemoryTier: Boolean,
    val maxChunkSize: Int,
    val softwareEncoderThreads: Int,
    val motionSpaceShortEdge: Double,
    val imagePrepMaxEdge: Int
) {
    companion object {
        private const val GB = 1024L * 1024L * 1024L
        private const val MB = 1024L * 1024L

        fun defaultHighMemory(targetWidth: Int = 1280, targetHeight: Int = 720): ExportMemoryProfile {
            return fromMetrics(
                totalMemBytes = 8L * GB,
                availMemBytes = 4L * GB,
                isLowMemoryFlag = false,
                isLowRamDevice = false,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
        }

        fun fromContext(
            context: Context?,
            targetWidth: Int,
            targetHeight: Int
        ): ExportMemoryProfile {
            if (context == null) {
                return defaultHighMemory(targetWidth, targetHeight)
            }
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am == null) {
                    defaultHighMemory(targetWidth, targetHeight)
                } else {
                    val mi = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    val totalMem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) mi.totalMem else 0L
                    val lowRamFlag = am.isLowRamDevice
                    fromMetrics(
                        totalMemBytes = totalMem,
                        availMemBytes = mi.availMem,
                        isLowMemoryFlag = mi.lowMemory,
                        isLowRamDevice = lowRamFlag,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                }
            } catch (_: Exception) {
                defaultHighMemory(targetWidth, targetHeight)
            }
        }

        fun fromMetrics(
            totalMemBytes: Long,
            availMemBytes: Long,
            isLowMemoryFlag: Boolean,
            isLowRamDevice: Boolean,
            targetWidth: Int,
            targetHeight: Int
        ): ExportMemoryProfile {
            val shortEdge = minOf(targetWidth, targetHeight).coerceAtLeast(1)
            val longEdge = maxOf(targetWidth, targetHeight).coerceAtLeast(1)

            val lowTier = isLowRamDevice ||
                isLowMemoryFlag ||
                (totalMemBytes in 1L..(2600L * MB)) ||
                (availMemBytes in 1L..(600L * MB))

            val midTier = !lowTier && (
                (totalMemBytes in 1L..(4400L * MB)) ||
                    (availMemBytes in 1L..(1200L * MB))
                )

            val maxChunkSize = when {
                lowTier -> when {
                    shortEdge >= 1080 -> 4
                    shortEdge >= 720 -> 5
                    else -> 6
                }
                midTier -> when {
                    shortEdge >= 1080 -> 6
                    else -> 8
                }
                else -> 10
            }

            val swThreads = when {
                lowTier -> 2
                midTier && shortEdge >= 1080 -> 3
                else -> 0
            }

            // On low-memory devices exporting <= 720p, keep motion coordinate space at >= 720p
            // instead of upscaling every frame to 2208x1242, halving native buffer & swscale cost
            // while preserving smooth subpixel zoom/pan interpolation.
            val motionShortEdge = if (lowTier && shortEdge < 1080) {
                maxOf(shortEdge.toDouble(), 720.0)
            } else {
                1080.0
            }

            val scaleFactor = maxOf(1.0, motionShortEdge / shortEdge.toDouble())
            val rawPrepMax = ceil(longEdge * scaleFactor * 1.15).toInt().coerceAtLeast(1280)
            val alignedPrepMax = ((rawPrepMax + 1) / 2) * 2

            return ExportMemoryProfile(
                totalMemBytes = totalMemBytes,
                availMemBytes = availMemBytes,
                isLowMemoryFlag = isLowMemoryFlag,
                isLowRamDevice = isLowRamDevice,
                isLowMemoryTier = lowTier,
                isMidMemoryTier = midTier,
                maxChunkSize = maxChunkSize,
                softwareEncoderThreads = swThreads,
                motionSpaceShortEdge = motionShortEdge,
                imagePrepMaxEdge = alignedPrepMax
            )
        }
    }
}

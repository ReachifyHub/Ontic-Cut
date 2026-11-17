package com.boostlyte.vydra.export

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import kotlin.math.ceil

/**
 * Memory-aware export sizing profile tailored for constrained Android devices
 * (e.g. 2GB RAM Go/entry devices with 128 MiB Java heap) without silently
 * lowering user-selected export resolution or frame rate.
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
    val imagePrepMaxEdge: Int,
    val javaHeapMaxBytes: Long = 0L,
    val javaHeapUsedBytes: Long = 0L,
    val javaHeapFreeBytes: Long = 0L,
    val nativeHeapAllocatedBytes: Long = 0L,
    val isJavaHeapConstrained: Boolean = false,
    val memoryTierLabel: String = if (isLowMemoryTier) "LOW" else if (isMidMemoryTier) "MID" else "HIGH"
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
                targetHeight = targetHeight,
                javaHeapMaxBytes = 512L * MB,
                javaHeapUsedBytes = 48L * MB,
                nativeHeapAllocatedBytes = 32L * MB
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
                val rt = Runtime.getRuntime()
                val javaMax = try { rt.maxMemory() } catch (_: Throwable) { 0L }
                val javaUsed = try { (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L) } catch (_: Throwable) { 0L }
                val nativeAlloc = try { Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L) } catch (_: Throwable) { 0L }

                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am == null) {
                    fromMetrics(
                        totalMemBytes = 0L,
                        availMemBytes = 0L,
                        isLowMemoryFlag = false,
                        isLowRamDevice = false,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        javaHeapMaxBytes = javaMax,
                        javaHeapUsedBytes = javaUsed,
                        nativeHeapAllocatedBytes = nativeAlloc
                    )
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
                        targetHeight = targetHeight,
                        javaHeapMaxBytes = javaMax,
                        javaHeapUsedBytes = javaUsed,
                        nativeHeapAllocatedBytes = nativeAlloc
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
            targetHeight: Int,
            javaHeapMaxBytes: Long = 0L,
            javaHeapUsedBytes: Long = 0L,
            nativeHeapAllocatedBytes: Long = 0L
        ): ExportMemoryProfile {
            val shortEdge = minOf(targetWidth, targetHeight).coerceAtLeast(1)
            val longEdge = maxOf(targetWidth, targetHeight).coerceAtLeast(1)

            val javaHeapConstrained = javaHeapMaxBytes in 1L..(192L * MB)
            val javaHeapFree = if (javaHeapMaxBytes > 0L) {
                (javaHeapMaxBytes - javaHeapUsedBytes).coerceAtLeast(0L)
            } else {
                0L
            }
            val criticalJavaPressure = javaHeapMaxBytes > 0L && javaHeapFree < 24L * MB
            val criticalNativePressure = (totalMemBytes in 1L..(2600L * MB)) && nativeHeapAllocatedBytes >= 220L * MB
            val criticalSysPressure = isLowMemoryFlag ||
                (availMemBytes in 1L..(380L * MB)) ||
                criticalJavaPressure ||
                criticalNativePressure

            val lowTier = isLowRamDevice ||
                isLowMemoryFlag ||
                javaHeapConstrained ||
                criticalSysPressure ||
                (totalMemBytes in 1L..(2600L * MB)) ||
                (availMemBytes in 1L..(650L * MB))

            val midTier = !lowTier && (
                (totalMemBytes in 1L..(4400L * MB)) ||
                    (availMemBytes in 1L..(1200L * MB))
                )

            val tierLabel = when {
                criticalSysPressure && lowTier -> "CRITICAL_LOW"
                lowTier -> "LOW"
                midTier -> "MID"
                else -> "HIGH"
            }

            // Constrained 2GB / 128 MiB Java heap devices use small 2-3 clip chunks
            // to keep concurrent decoded frames, filter buffers, and x264 memory low.
            val maxChunkSize = when {
                lowTier -> when {
                    shortEdge >= 1080 || criticalSysPressure -> 2
                    else -> 3
                }
                midTier -> when {
                    shortEdge >= 1080 -> 5
                    else -> 7
                }
                else -> 10
            }

            val swThreads = when {
                lowTier -> 1
                midTier && shortEdge >= 1080 -> 2
                midTier -> 3
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
                imagePrepMaxEdge = alignedPrepMax,
                javaHeapMaxBytes = javaHeapMaxBytes,
                javaHeapUsedBytes = javaHeapUsedBytes,
                javaHeapFreeBytes = javaHeapFree,
                nativeHeapAllocatedBytes = nativeHeapAllocatedBytes,
                isJavaHeapConstrained = javaHeapConstrained,
                memoryTierLabel = tierLabel
            )
        }
    }
}

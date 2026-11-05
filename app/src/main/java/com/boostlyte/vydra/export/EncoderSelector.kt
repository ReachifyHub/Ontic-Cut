package com.boostlyte.vydra.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * Unified capability-aware AVC encoder selection and fallback utility.
 * Replaces scattered encoder selection with a central, validated pipeline:
 * 1. Validated Hardware MediaCodec (h264_mediacodec)
 * 2. Validated Software MediaCodec (c2.android / OMX.google)
 * 3. Validated Software libx264
 */
object EncoderSelector {

    private const val TAG = "EncoderSelector"
    const val MIME_TYPE_AVC = "video/avc"

    enum class EncoderPreference {
        AUTO,
        HARDWARE,
        SOFTWARE
    }

    enum class EncoderBackend {
        HARDWARE_MEDIACODEC,
        SOFTWARE_MEDIACODEC,
        SOFTWARE_LIBX264
    }

    data class SelectedEncoder(
        val backend: EncoderBackend,
        val codecName: String?,
        val ffmpegVideoCodecArgs: String,
        val isHardware: Boolean,
        val isSupportedForTarget: Boolean
    )

    /**
     * Inspects device encoders and selects the optimal encoder for target dimensions and fps.
     */
    fun selectEncoder(
        preference: EncoderPreference = EncoderPreference.AUTO,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
        fps: Int = 30,
        bitrate: String = "8M"
    ): SelectedEncoder {
        val safeW = ((targetWidth + 1) / 2) * 2
        val safeH = ((targetHeight + 1) / 2) * 2

        if (preference == EncoderPreference.SOFTWARE) {
            val swCodec = findSoftwareMediaCodec(safeW, safeH, fps)
            return if (swCodec != null) {
                SelectedEncoder(
                    backend = EncoderBackend.SOFTWARE_MEDIACODEC,
                    codecName = swCodec,
                    ffmpegVideoCodecArgs = "-c:v libx264 -preset veryfast -threads 0 -pix_fmt yuv420p",
                    isHardware = false,
                    isSupportedForTarget = true
                )
            } else {
                SelectedEncoder(
                    backend = EncoderBackend.SOFTWARE_LIBX264,
                    codecName = null,
                    ffmpegVideoCodecArgs = "-c:v libx264 -preset veryfast -threads 0 -pix_fmt yuv420p",
                    isHardware = false,
                    isSupportedForTarget = true
                )
            }
        }

        // Try Hardware first if AUTO or HARDWARE
        val hwCodec = findHardwareMediaCodec(safeW, safeH, fps)
        if (hwCodec != null) {
            Log.d(TAG, "Selected hardware encoder: $hwCodec for ${safeW}x${safeH}@${fps}fps")
            return SelectedEncoder(
                backend = EncoderBackend.HARDWARE_MEDIACODEC,
                codecName = hwCodec,
                ffmpegVideoCodecArgs = "-c:v h264_mediacodec -pix_fmt yuv420p -b:v $bitrate",
                isHardware = true,
                isSupportedForTarget = true
            )
        }

        if (preference == EncoderPreference.HARDWARE) {
            Log.w(TAG, "Hardware encoder requested but not supported for ${safeW}x${safeH}@${fps}fps, falling back to software")
        }

        // Fallback to Software MediaCodec
        val swCodec = findSoftwareMediaCodec(safeW, safeH, fps)
        if (swCodec != null) {
            Log.d(TAG, "Selected software MediaCodec encoder: $swCodec")
            return SelectedEncoder(
                backend = EncoderBackend.SOFTWARE_MEDIACODEC,
                codecName = swCodec,
                ffmpegVideoCodecArgs = "-c:v libx264 -preset veryfast -threads 0 -pix_fmt yuv420p",
                isHardware = false,
                isSupportedForTarget = true
            )
        }

        // Default to libx264
        Log.d(TAG, "Selected software libx264 encoder fallback")
        return SelectedEncoder(
            backend = EncoderBackend.SOFTWARE_LIBX264,
            codecName = null,
            ffmpegVideoCodecArgs = "-c:v libx264 -preset veryfast -threads 0 -pix_fmt yuv420p",
            isHardware = false,
            isSupportedForTarget = true
        )
    }

    /**
     * Determines whether an error string or return code indicates a permanent failure
     * where retrying with another encoder or re-running would be futile.
     */
    fun isPermanentFailure(errorMessage: String?, isCancelled: Boolean): Boolean {
        if (isCancelled) return true
        val msg = errorMessage?.lowercase() ?: return false
        return msg.contains("no space left on device") ||
                msg.contains("enospc") ||
                msg.contains("no such filter") ||
                msg.contains("error initializing complex filters") ||
                msg.contains("invalid argument") ||
                msg.contains("permission denied")
    }

    fun findHardwareMediaCodec(width: Int, height: Int, fps: Int): String? {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME_TYPE_AVC, ignoreCase = true) }) continue
                if (isSoftwareCodec(info.name)) continue

                if (isResolutionAndRateSupported(info, width, height, fps)) {
                    return info.name
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating hardware codecs: ${e.message}")
            null
        }
    }

    fun findSoftwareMediaCodec(width: Int, height: Int, fps: Int): String? {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME_TYPE_AVC, ignoreCase = true) }) continue
                if (!isSoftwareCodec(info.name)) continue

                if (isResolutionAndRateSupported(info, width, height, fps)) {
                    return info.name
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating software codecs: ${e.message}")
            null
        }
    }

    private fun isSoftwareCodec(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("omx.google.") ||
                lower.startsWith("c2.android.") ||
                lower.contains(".sw.") ||
                lower.contains("software")
    }

    private fun isResolutionAndRateSupported(info: MediaCodecInfo, width: Int, height: Int, fps: Int): Boolean {
        return try {
            val caps = info.getCapabilitiesForType(MIME_TYPE_AVC)
            val vCaps = caps.videoCapabilities ?: return true
            val sizeSupported = vCaps.isSizeSupported(width, height)
            val rateSupported = try {
                vCaps.areSizeAndRateSupported(width, height, fps.toDouble())
            } catch (_: Exception) {
                true
            }
            sizeSupported && rateSupported
        } catch (e: Exception) {
            // If capability query fails, assume supported on older APIs
            true
        }
    }
}

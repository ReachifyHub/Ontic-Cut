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
     * Returns an intentional hardware target video bitrate (CBR/VBR target for MediaCodec)
     * based on output resolution and frame rate.
     */
    fun getTargetVideoBitrate(resolution: Int, fps: Int = 30): String {
        val baseKbps = when {
            resolution >= 2160 -> 25_000
            resolution >= 1440 -> 14_000
            resolution >= 1080 -> 8_000
            resolution >= 720 -> 4_000
            resolution >= 480 -> 1_500
            else -> 650 // 360p and below: ~650 kbps avoids excessive bloat (~43MB/5min at 1M) while keeping clean quality
        }
        val fpsMultiplier = if (fps >= 50) 1.35 else 1.0
        val finalKbps = (baseKbps * fpsMultiplier).toInt()
        return if (finalKbps >= 1000 && finalKbps % 1000 == 0) {
            "${finalKbps / 1000}M"
        } else {
            "${finalKbps}k"
        }
    }

    /**
     * Returns an intentional CRF value for libx264 software encoding.
     * Software encoding uses CRF rate control rather than MediaCodec's -b:v target bitrate.
     */
    fun getTargetSoftwareCrf(resolution: Int): Int {
        return when {
            resolution >= 720 -> 23
            else -> 24
        }
    }

    /**
     * Returns an intentional AAC audio bitrate based on export resolution.
     */
    fun getTargetAudioBitrate(resolution: Int): String {
        return if (resolution >= 720) "128k" else "96k"
    }

    /**
     * Inspects device encoders and selects the optimal encoder for target dimensions and fps.
     */
    fun selectEncoder(
        preference: EncoderPreference = EncoderPreference.AUTO,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
        fps: Int = 30,
        bitrate: String? = null,
        crf: Int? = null
    ): SelectedEncoder {
        val safeW = ((targetWidth + 1) / 2) * 2
        val safeH = ((targetHeight + 1) / 2) * 2
        val effectiveRes = minOf(safeW, safeH)
        val effectiveBitrate = bitrate ?: getTargetVideoBitrate(effectiveRes, fps)
        val effectiveCrf = crf ?: getTargetSoftwareCrf(effectiveRes)
        val swArgs = "-c:v libx264 -preset veryfast -crf $effectiveCrf -threads 0 -pix_fmt yuv420p"

        if (preference == EncoderPreference.SOFTWARE) {
            Log.d(TAG, "Software encoder explicitly requested, using libx264 (crf=$effectiveCrf)")
            return SelectedEncoder(
                backend = EncoderBackend.SOFTWARE_LIBX264,
                codecName = null,
                ffmpegVideoCodecArgs = swArgs,
                isHardware = false,
                isSupportedForTarget = true
            )
        }

        // Try Hardware first if AUTO or HARDWARE
        val hwCodec = findHardwareMediaCodec(safeW, safeH, fps)
        if (hwCodec != null) {
            Log.d(TAG, "Selected hardware encoder: $hwCodec for ${safeW}x${safeH}@${fps}fps (bitrate=$effectiveBitrate)")
            return SelectedEncoder(
                backend = EncoderBackend.HARDWARE_MEDIACODEC,
                codecName = hwCodec,
                ffmpegVideoCodecArgs = "-c:v h264_mediacodec -pix_fmt yuv420p -b:v $effectiveBitrate",
                isHardware = true,
                isSupportedForTarget = true
            )
        }

        // Hardware unavailable or unsupported for target parameters -> log exact reason and fall back to libx264
        Log.w(TAG, "Hardware encoder unavailable/unsupported for ${safeW}x${safeH}@${fps}fps, falling back to software libx264 (crf=$effectiveCrf)")

        return SelectedEncoder(
            backend = EncoderBackend.SOFTWARE_LIBX264,
            codecName = null,
            ffmpegVideoCodecArgs = swArgs,
            isHardware = false,
            isSupportedForTarget = true
        )
    }

    /**
     * Resolves user preference from vydra_prefs ('default_encoder') and returns a validated encoder.
     * Defaults to "software" to match MainActivity settings UI when unset.
     */
    fun getEffectiveEncoder(
        context: android.content.Context?,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
        fps: Int = 30,
        bitrate: String? = null,
        crf: Int? = null
    ): SelectedEncoder {
        val prefs = context?.getSharedPreferences("vydra_prefs", android.content.Context.MODE_PRIVATE)
        val defaultEncoder = prefs?.getString("default_encoder", "software") ?: "software"
        val pref = if (defaultEncoder.equals("hardware", ignoreCase = true)) {
            EncoderPreference.HARDWARE
        } else {
            EncoderPreference.SOFTWARE
        }
        return selectEncoder(
            preference = pref,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            fps = fps,
            bitrate = bitrate,
            crf = crf
        )
    }

    /**
     * Rewrites a command that failed on h264_mediacodec to use software libx264 with CRF rate control
     * instead of leaving MediaCodec's -b:v argument attached.
     */
    fun buildSoftwareFallbackCommand(command: String, crf: Int = 23): String {
        val withoutBitrate = command.replace(Regex("""\s+-b:v\s+\S+"""), "")
        return withoutBitrate.replace("h264_mediacodec", "libx264 -preset veryfast -crf $crf -threads 0")
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

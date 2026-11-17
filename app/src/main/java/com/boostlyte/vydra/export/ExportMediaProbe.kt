package com.boostlyte.vydra.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Media & A/V synchronization prober and verifier for rendered chunk MP4s and final outputs.
 * Uses FFprobeKit on Android (with MediaExtractor fallback) and pure Gson JSON parsing so
 * the exact same verification logic can also be exercised against host ffprobe in unit tests.
 */
object ExportMediaProbe {

    private const val TAG = "ExportMediaProbe"

    data class MediaProbeResult(
        val path: String,
        val probeMethod: String,
        val formatDurationSec: Double? = null,
        val formatStartTimeSec: Double? = null,
        val hasVideo: Boolean = false,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val videoFrames: Int? = null,
        val videoRFrameRate: String? = null,
        val videoAvgFrameRate: String? = null,
        val videoFps: Double? = null,
        val videoTimeBase: String? = null,
        val videoStartTimeSec: Double? = null,
        val videoDurationSec: Double? = null,
        val firstVideoPtsSec: Double? = null,
        val lastVideoPtsSec: Double? = null,
        val hasAudio: Boolean = false,
        val audioCodec: String? = null,
        val audioSampleRate: Int? = null,
        val audioStartTimeSec: Double? = null,
        val audioDurationSec: Double? = null,
        val firstAudioPtsSec: Double? = null,
        val lastAudioPtsSec: Double? = null
    ) {
        val effectiveVideoDurationSec: Double?
            get() = videoDurationSec ?: formatDurationSec
    }

    data class SyncVerificationResult(
        val isValid: Boolean,
        val failureReason: String? = null,
        val diagnosticLine: String
    )

    fun probeWithFfprobeKit(filePath: String, probePackets: Boolean = true): MediaProbeResult {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            return MediaProbeResult(path = filePath, probeMethod = "missing")
        }

        try {
            val baseCmd = "-v error -print_format json -show_format -show_streams \"$filePath\""
            val session = FFprobeKit.execute(baseCmd)
            val rc = session?.returnCode
            val rawOut = session?.output ?: ""
            if (ReturnCode.isSuccess(rc) && rawOut.trimStart().startsWith("{")) {
                val baseProbe = parseFfprobeJson(rawOut, null, null, null, filePath)
                if (!probePackets || !baseProbe.hasVideo) {
                    return baseProbe
                }
                val firstVJson = runCatching {
                    val s = FFprobeKit.execute("-v error -read_intervals 0%+0.25 -select_streams v:0 -show_entries packet=pts_time,dts_time,duration_time -print_format json \"$filePath\"")
                    if (ReturnCode.isSuccess(s?.returnCode)) s?.output else null
                }.getOrNull()

                val dur = baseProbe.effectiveVideoDurationSec ?: 0.0
                val tailSeek = maxOf(0.0, dur - 0.5)
                val lastVJson = runCatching {
                    val intervalArg = if (tailSeek > 0.0) "${String.format(Locale.US, "%.3f", tailSeek)}%" else "0%"
                    val s = FFprobeKit.execute("-v error -read_intervals $intervalArg -select_streams v:0 -show_entries packet=pts_time,dts_time,duration_time -print_format json \"$filePath\"")
                    if (ReturnCode.isSuccess(s?.returnCode)) s?.output else null
                }.getOrNull()

                val audioPacketJson = if (baseProbe.hasAudio) {
                    runCatching {
                        val s = FFprobeKit.execute("-v error -read_intervals 0%+0.25 -select_streams a:0 -show_entries packet=pts_time,dts_time,duration_time -print_format json \"$filePath\"")
                        if (ReturnCode.isSuccess(s?.returnCode)) s?.output else null
                    }.getOrNull()
                } else null

                return parseFfprobeJson(rawOut, firstVJson, lastVJson, audioPacketJson, filePath)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "FFprobeKit probe failed for $filePath: ${t.message}")
        }

        return probeWithMediaExtractorFallback(filePath)
    }

    private fun probeWithMediaExtractorFallback(filePath: String): MediaProbeResult {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            var hasVideo = false
            var width = 0
            var height = 0
            var vFps: Double? = null
            var vDurSec: Double? = null
            var hasAudio = false
            var aSampleRate: Int? = null
            var aDurSec: Double? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && !hasVideo) {
                    hasVideo = true
                    width = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
                    height = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    vFps = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() else null
                    vDurSec = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else null
                } else if (mime.startsWith("audio/") && !hasAudio) {
                    hasAudio = true
                    aSampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else null
                    aDurSec = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else null
                }
            }
            MediaProbeResult(
                path = filePath,
                probeMethod = "media_extractor",
                formatDurationSec = vDurSec ?: aDurSec,
                hasVideo = hasVideo,
                videoWidth = width,
                videoHeight = height,
                videoFps = vFps,
                videoDurationSec = vDurSec,
                hasAudio = hasAudio,
                audioSampleRate = aSampleRate,
                audioDurationSec = aDurSec
            )
        } catch (t: Throwable) {
            Log.w(TAG, "MediaExtractor fallback failed for $filePath: ${t.message}")
            MediaProbeResult(path = filePath, probeMethod = "none")
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {}
        }
    }

    fun parseFfprobeJson(
        streamsFormatJson: String,
        firstVideoPacketJson: String? = null,
        lastVideoPacketJson: String? = null,
        audioPacketJson: String? = null,
        path: String = ""
    ): MediaProbeResult {
        return try {
            val root = extractJsonObject(streamsFormatJson) ?: return MediaProbeResult(path = path, probeMethod = "parse_error")
            val formatObj = root.getAsJsonObject("format")
            val formatDur = formatObj.optDouble("duration")
            val formatStart = formatObj.optDouble("start_time")

            var hasVideo = false
            var vWidth = 0
            var vHeight = 0
            var vFrames: Int? = null
            var vRRate: String? = null
            var vAvgRate: String? = null
            var vFps: Double? = null
            var vTimeBase: String? = null
            var vStart: Double? = null
            var vDur: Double? = null

            var hasAudio = false
            var aCodec: String? = null
            var aSampleRate: Int? = null
            var aStart: Double? = null
            var aDur: Double? = null

            val streamsArr = root.getAsJsonArray("streams")
            if (streamsArr != null) {
                for (el in streamsArr) {
                    val s = el.asJsonObject
                    when (s.optString("codec_type")) {
                        "video" -> if (!hasVideo) {
                            hasVideo = true
                            vWidth = s.optInt("width") ?: 0
                            vHeight = s.optInt("height") ?: 0
                            vFrames = s.optInt("nb_frames") ?: s.optInt("nb_read_frames")
                            vRRate = s.optString("r_frame_rate")
                            vAvgRate = s.optString("avg_frame_rate")
                            vFps = parseRational(vAvgRate) ?: parseRational(vRRate)
                            vTimeBase = s.optString("time_base")
                            vStart = s.optDouble("start_time")
                            vDur = s.optDouble("duration")
                        }
                        "audio" -> if (!hasAudio) {
                            hasAudio = true
                            aCodec = s.optString("codec_name")
                            aSampleRate = s.optInt("sample_rate")
                            aStart = s.optDouble("start_time")
                            aDur = s.optDouble("duration")
                        }
                    }
                }
            }

            val firstVPts = firstVideoPacketJson?.let { parseEdgePacketPts(it, first = true) }
            val lastVPts = lastVideoPacketJson?.let { parseEdgePacketPts(it, first = false) }
            val firstAPts = audioPacketJson?.let { parseEdgePacketPts(it, first = true) }
            val lastAPts = audioPacketJson?.let { parseEdgePacketPts(it, first = false) }

            MediaProbeResult(
                path = path,
                probeMethod = "ffprobe",
                formatDurationSec = formatDur,
                formatStartTimeSec = formatStart,
                hasVideo = hasVideo,
                videoWidth = vWidth,
                videoHeight = vHeight,
                videoFrames = vFrames,
                videoRFrameRate = vRRate,
                videoAvgFrameRate = vAvgRate,
                videoFps = vFps,
                videoTimeBase = vTimeBase,
                videoStartTimeSec = vStart,
                videoDurationSec = vDur,
                firstVideoPtsSec = firstVPts,
                lastVideoPtsSec = lastVPts,
                hasAudio = hasAudio,
                audioCodec = aCodec,
                audioSampleRate = aSampleRate,
                audioStartTimeSec = aStart,
                audioDurationSec = aDur,
                firstAudioPtsSec = firstAPts,
                lastAudioPtsSec = lastAPts
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Error parsing ffprobe JSON: ${t.message}")
            MediaProbeResult(path = path, probeMethod = "parse_error")
        }
    }

    fun verifyChunkMedia(
        probe: MediaProbeResult,
        chunkIndex: Int,
        plannedStartSec: Double,
        plannedEndSec: Double,
        plannedDurationSec: Double,
        expectedFrames: Int,
        expectedFps: Int,
        transitionOverlapSec: Double,
        plannedCumulativeEndSec: Double,
        actualCumulativeEndSec: Double
    ): SyncVerificationResult {
        val actualDur = probe.effectiveVideoDurationSec
        val actualFrames = probe.videoFrames
        val frameDiff = if (actualFrames != null && expectedFrames > 0) actualFrames - expectedFrames else null
        val durDiffSec = if (actualDur != null) actualDur - plannedDurationSec else null
        val cumDriftSec = actualCumulativeEndSec - plannedCumulativeEndSec
        val actualEndSec = plannedStartSec + (actualDur ?: plannedDurationSec)

        val diag = String.format(
            Locale.US,
            "chunk=%d method=%s planned=[%.3f..%.3f dur=%.3f expFrames=%d expFps=%d transOverlap=%.3f] " +
                "actual=[end=%.3f dur=%s frames=%s fps=%s(%s) tb=%s firstPts=%s lastPts=%s] " +
                "drift=[durDiff=%s frameDiff=%s cumDrift=%.3fs]",
            chunkIndex + 1,
            probe.probeMethod,
            plannedStartSec,
            plannedEndSec,
            plannedDurationSec,
            expectedFrames,
            expectedFps,
            transitionOverlapSec,
            actualEndSec,
            fmtOpt(actualDur),
            actualFrames?.toString() ?: "n/a",
            probe.videoFps?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a",
            probe.videoAvgFrameRate ?: "n/a",
            probe.videoTimeBase ?: "n/a",
            fmtOpt(probe.firstVideoPtsSec),
            fmtOpt(probe.lastVideoPtsSec),
            fmtOptSigned(durDiffSec),
            frameDiff?.let { if (it >= 0) "+$it" else "$it" } ?: "n/a",
            cumDriftSec
        )

        if (probe.probeMethod == "none" || probe.probeMethod == "missing") {
            return SyncVerificationResult(
                isValid = false,
                failureReason = "Chunk ${chunkIndex + 1} output unreadable or missing (${probe.path})",
                diagnosticLine = diag
            )
        }
        if (!probe.hasVideo || (actualFrames != null && actualFrames <= 0)) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = "Chunk ${chunkIndex + 1} has no valid video stream (frames=$actualFrames)",
                diagnosticLine = diag
            )
        }
        val frameTol = maxOf(2, (expectedFps * 0.1).toInt())
        if (frameDiff != null && abs(frameDiff) > frameTol) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = "Chunk ${chunkIndex + 1} frame count mismatch: expected=$expectedFrames actual=$actualFrames (diff=$frameDiff)",
                diagnosticLine = diag
            )
        }
        val durTolSec = maxOf(0.15, 3.0 / expectedFps.coerceAtLeast(1))
        if (durDiffSec != null && abs(durDiffSec) > durTolSec) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = String.format(
                    Locale.US,
                    "Chunk %d duration drift exceeded tolerance: expected=%.3fs actual=%.3fs (diff=%.3fs)",
                    chunkIndex + 1, plannedDurationSec, actualDur, durDiffSec
                ),
                diagnosticLine = diag
            )
        }
        if (abs(cumDriftSec) > 0.35) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = String.format(
                    Locale.US,
                    "Cumulative chunk timeline drift at chunk %d: %.3fs (plannedEnd=%.3fs actualEnd=%.3fs)",
                    chunkIndex + 1, cumDriftSec, plannedCumulativeEndSec, actualCumulativeEndSec
                ),
                diagnosticLine = diag
            )
        }

        return SyncVerificationResult(isValid = true, diagnosticLine = diag)
    }

    fun verifyFinalMedia(
        probe: MediaProbeResult,
        expectedVideoDurationSec: Double,
        expectedTotalFrames: Int,
        expectedFps: Int,
        expectedAudio: Boolean,
        expectedAudioDurationSec: Double,
        expectedAudioStartSec: Double = 0.0
    ): SyncVerificationResult {
        val vDur = probe.effectiveVideoDurationSec
        val vFrames = probe.videoFrames
        val aDur = probe.audioDurationSec
        val vStart = probe.firstVideoPtsSec ?: probe.videoStartTimeSec
        val aStart = probe.firstAudioPtsSec ?: probe.audioStartTimeSec
        val vDurDiff = if (vDur != null) vDur - expectedVideoDurationSec else null
        val frameDiff = if (vFrames != null && expectedTotalFrames > 0) vFrames - expectedTotalFrames else null
        val aDurDiff = if (expectedAudio && aDur != null && expectedAudioDurationSec > 0.0) {
            aDur - expectedAudioDurationSec
        } else null
        val avStartOffset = if (expectedAudio && vStart != null && aStart != null && expectedAudioStartSec <= 0.05) {
            aStart - vStart
        } else null
        val avDurDiff = if (expectedAudio && vDur != null && aDur != null && expectedAudioDurationSec >= expectedVideoDurationSec - 0.25) {
            vDur - aDur
        } else null

        val diag = String.format(
            Locale.US,
            "final method=%s expected=[vDur=%.3f frames=%d fps=%d hasAudio=%b aDur=%.3f] " +
                "actual=[vDur=%s frames=%s fps=%s(%s) tb=%s firstVPts=%s lastVPts=%s hasAudio=%b aDur=%s aStart=%s] " +
                "drift=[vDurDiff=%s frameDiff=%s aDurDiff=%s avStartOffset=%s avEndDiff=%s]",
            probe.probeMethod,
            expectedVideoDurationSec,
            expectedTotalFrames,
            expectedFps,
            expectedAudio,
            expectedAudioDurationSec,
            fmtOpt(vDur),
            vFrames?.toString() ?: "n/a",
            probe.videoFps?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a",
            probe.videoAvgFrameRate ?: "n/a",
            probe.videoTimeBase ?: "n/a",
            fmtOpt(vStart),
            fmtOpt(probe.lastVideoPtsSec),
            probe.hasAudio,
            fmtOpt(aDur),
            fmtOpt(aStart),
            fmtOptSigned(vDurDiff),
            frameDiff?.let { if (it >= 0) "+$it" else "$it" } ?: "n/a",
            fmtOptSigned(aDurDiff),
            fmtOptSigned(avStartOffset),
            fmtOptSigned(avDurDiff)
        )

        if (probe.probeMethod == "none" || probe.probeMethod == "missing") {
            return SyncVerificationResult(
                isValid = false,
                failureReason = "Final output file unreadable or missing (${probe.path})",
                diagnosticLine = diag
            )
        }
        if (!probe.hasVideo || (vFrames != null && vFrames <= 0)) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = "Final exported MP4 has no valid video stream",
                diagnosticLine = diag
            )
        }
        if (expectedAudio && !probe.hasAudio) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = "Final exported MP4 is missing expected audio stream",
                diagnosticLine = diag
            )
        }
        val frameTol = maxOf(3, (expectedFps * 0.15).toInt())
        if (frameDiff != null && expectedTotalFrames > 0 && abs(frameDiff) > frameTol) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = "Final frame count mismatch: expected=$expectedTotalFrames actual=$vFrames (diff=$frameDiff)",
                diagnosticLine = diag
            )
        }
        val durTol = maxOf(0.25, 4.0 / expectedFps.coerceAtLeast(1))
        if (vDurDiff != null && expectedVideoDurationSec > 0.0 && abs(vDurDiff) > durTol) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = String.format(
                    Locale.US,
                    "Final video duration drift exceeded tolerance: expected=%.3fs actual=%.3fs (diff=%.3fs)",
                    expectedVideoDurationSec, vDur, vDurDiff
                ),
                diagnosticLine = diag
            )
        }
        if (avStartOffset != null && abs(avStartOffset) > 0.20) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = String.format(
                    Locale.US,
                    "Final A/V start PTS offset exceeded tolerance: vStart=%.3fs aStart=%.3fs (diff=%.3fs)",
                    vStart, aStart, avStartOffset
                ),
                diagnosticLine = diag
            )
        }
        if (avDurDiff != null && abs(avDurDiff) > 0.35) {
            return SyncVerificationResult(
                isValid = false,
                failureReason = String.format(
                    Locale.US,
                    "Final A/V duration mismatch exceeded tolerance: vDur=%.3fs aDur=%.3fs (diff=%.3fs)",
                    vDur, aDur, avDurDiff
                ),
                diagnosticLine = diag
            )
        }

        return SyncVerificationResult(isValid = true, diagnosticLine = diag)
    }

    private fun parseEdgePacketPts(jsonText: String, first: Boolean): Double? {
        val root = extractJsonObject(jsonText) ?: return null
        val packets = root.getAsJsonArray("packets") ?: return null
        if (packets.size() == 0) return null
        val pkt = if (first) packets[0].asJsonObject else packets[packets.size() - 1].asJsonObject
        return pkt.optDouble("pts_time") ?: pkt.optDouble("dts_time")
    }

    private fun extractJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        val braceIdx = trimmed.indexOf('{')
        if (braceIdx < 0) return null
        val candidate = if (braceIdx == 0) trimmed else trimmed.substring(braceIdx)
        val el: JsonElement = JsonParser.parseString(candidate)
        return if (el.isJsonObject) el.asJsonObject else null
    }

    internal fun parseRational(rational: String?): Double? {
        if (rational.isNullOrBlank() || rational == "0/0") return null
        val parts = rational.split("/")
        if (parts.size == 2) {
            val num = parts[0].toDoubleOrNull() ?: return null
            val den = parts[1].toDoubleOrNull() ?: return null
            if (den == 0.0) return null
            return num / den
        }
        return rational.toDoubleOrNull()
    }

    private fun JsonObject.optString(key: String): String? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        return runCatching { el.asString }.getOrNull()
    }

    private fun JsonObject.optDouble(key: String): Double? {
        val str = optString(key) ?: return null
        if (str.equals("N/A", ignoreCase = true)) return null
        return str.toDoubleOrNull()
    }

    private fun JsonObject.optInt(key: String): Int? {
        val str = optString(key) ?: return null
        if (str.equals("N/A", ignoreCase = true)) return null
        return str.toIntOrNull() ?: str.toDoubleOrNull()?.toInt()
    }

    private fun fmtOpt(v: Double?): String = if (v != null) String.format(Locale.US, "%.3f", v) else "n/a"
    private fun fmtOptSigned(v: Double?): String = if (v != null) String.format(Locale.US, "%+.3f", v) else "n/a"
}

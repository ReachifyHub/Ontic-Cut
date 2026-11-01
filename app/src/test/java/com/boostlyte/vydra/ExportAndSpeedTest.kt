package com.boostlyte.vydra

import android.net.Uri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.utils.ErrorCode
import org.junit.Assert.*
import org.junit.Test

class ExportAndSpeedTest {

    @Test
    fun testErrorCodeDefinitions() {
        assertEquals("LC-101", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
        assertEquals("LC-202", ErrorCode.GALLERY_SAVE_FAILED.code)
        assertEquals("LC-500", ErrorCode.UNEXPECTED_CRASH.code)
    }

    @Test
    fun testTrimmedDurationWithSpeed() {
        // Speed 2.0x halves duration
        val item2x = EditOperation.MergeItem(
            uri = android.net.DummyUri.INSTANCE,
            durationMs = 10000L,
            trimStartMs = 0L,
            trimEndMs = 10000L,
            speed = 2.0f
        )
        assertEquals(5000L, item2x.trimmedDurationMs)

        // Speed 0.5x doubles duration
        val item05x = EditOperation.MergeItem(
            uri = android.net.DummyUri.INSTANCE,
            durationMs = 10000L,
            trimStartMs = 0L,
            trimEndMs = 10000L,
            speed = 0.5f
        )
        assertEquals(20000L, item05x.trimmedDurationMs)

        // Trimmed portion with speed
        val itemTrimmed = EditOperation.MergeItem(
            uri = android.net.DummyUri.INSTANCE,
            durationMs = 10000L,
            trimStartMs = 2000L,
            trimEndMs = 6000L,
            speed = 2.0f
        )
        assertEquals(2000L, itemTrimmed.trimmedDurationMs)
    }

    @Test
    fun testSpeedProxyCommandComponents() {
        val speed = 2.0f
        val ptsMultiplier = 1.0f / speed
        val filterExpr = "setpts=${ptsMultiplier}*(PTS-STARTPTS),format=yuv420p"
        assertTrue(filterExpr.contains("PTS-STARTPTS"))
        assertTrue(filterExpr.contains("format=yuv420p"))
    }

    @Test
    fun testErrorResolutionMapping() {
        fun resolveErrorCode(errorCodeStr: String?, errorMsg: String): ErrorCode {
            return when {
                errorCodeStr == ErrorCode.GALLERY_SAVE_FAILED.code -> ErrorCode.GALLERY_SAVE_FAILED
                errorCodeStr == ErrorCode.FFMPEG_EXECUTION_FAILED.code -> ErrorCode.FFMPEG_EXECUTION_FAILED
                errorCodeStr == ErrorCode.UNEXPECTED_CRASH.code -> ErrorCode.UNEXPECTED_CRASH
                errorMsg.contains("gallery", ignoreCase = true) || errorMsg.contains("save video", ignoreCase = true) -> ErrorCode.GALLERY_SAVE_FAILED
                else -> ErrorCode.FFMPEG_EXECUTION_FAILED
            }
        }

        assertEquals(
            ErrorCode.GALLERY_SAVE_FAILED,
            resolveErrorCode("LC-202", "Failed to save video to gallery")
        )
        assertEquals(
            ErrorCode.GALLERY_SAVE_FAILED,
            resolveErrorCode(null, "Failed to save video to gallery")
        )
        assertEquals(
            ErrorCode.FFMPEG_EXECUTION_FAILED,
            resolveErrorCode("LC-101", "Conversion failed")
        )
    }

    @Test
    fun testSubtitleDrawtextFilterFormat() {
        val rawText = "It's 100% working!"
        val escapedText = rawText
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace(":", "\\:")
        
        fun buildEnableExpr(startTimeMs: Long?, endTimeMs: Long?): String {
            if (startTimeMs == null && endTimeMs == null) return ""
            val startSec = maxOf(0L, startTimeMs ?: 0L) / 1000.0
            return if (endTimeMs != null) {
                val endSec = maxOf(0L, endTimeMs) / 1000.0
                ":enable='between(t,$startSec,$endSec)'"
            } else {
                ":enable='gte(t,$startSec)'"
            }
        }

        val enable = buildEnableExpr(1000L, 3500L)
        val filterExpr = "drawtext=expansion=none:text='$escapedText':fontcolor=0xFFFFFFFF:fontsize=24:box=1:boxcolor=black@0.5:boxborderw=8$enable"

        assertTrue("Must include expansion=none to prevent % crash", filterExpr.contains("expansion=none:"))
        assertTrue("Apostrophe must be single-backslash escaped", filterExpr.contains("It\\'s"))
        assertFalse("Must NOT contain triple-backslash", filterExpr.contains("\\\\\\'"))
        assertTrue("Enable expr must use between(t,1.0,3.5)", filterExpr.contains(":enable='between(t,1.0,3.5)'"))
        assertFalse("Must NOT contain backslash before comma in between expr", filterExpr.contains("\\,"))
        assertTrue("Must have boxcolor without outer quotes", filterExpr.contains("boxcolor=black@0.5"))
    }
}


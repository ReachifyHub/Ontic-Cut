package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.utils.AppLogger
import com.boostlyte.vydra.utils.FFmpegFilterUtils
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit tests for Task 1 (Filter escaping/quoting) and Task 3 (Duration fidelity).
 */
class FilterEscapeAndExportFidelityTest {

    @Test
    fun testCaptionGraphEscapesCommasInDecimalTimestamps() {
        val startMs = 188160L // 188.16s
        val endMs = 191020L   // 191.02s

        val enableFilter = FFmpegFilterUtils.buildEnableFilter(startMs, endMs)

        // Verify enable option is safely formatted with protective quotes and escaped commas
        assertTrue("Enable filter must start with :enable='", enableFilter.startsWith(":enable='"))
        assertTrue("Enable filter must end with '", enableFilter.endsWith("'"))
        assertTrue("Must contain escaped commas before 188.160", enableFilter.contains("between(t\\,188.160\\,191.020)"))

        // Verify that FFmpeg token splitting on unescaped comma cannot extract '188.160' as a filter name
        // Simulate FFmpeg filterchain parser: split on unescaped comma outside quotes
        val tokens = parseFFmpegFilterTokens(enableFilter)
        for (token in tokens) {
            assertNotEquals("188.16", token.trim())
            assertNotEquals("188.160", token.trim())
            assertNotEquals("191.02", token.trim())
            assertNotEquals("191.020", token.trim())
        }
    }

    @Test
    fun testDrawtextTextEscapingProtectsSpecialCharacters() {
        val rawText = "Wait, it's 10:00 [intro]; action!"
        val escaped = FFmpegFilterUtils.escapeDrawtextText(rawText)

        // Verify commas and colons are escaped with backslash
        assertTrue("Comma must be escaped", escaped.contains("\\,"))
        assertTrue("Colon must be escaped", escaped.contains("\\:"))
        assertTrue("Semicolon must be escaped", escaped.contains("\\;"))
        assertTrue("Brackets must be escaped", escaped.contains("\\[intro\\]"))
        assertTrue("Single quote must be escaped for shell/ffmpeg", escaped.contains("'\\''"))
    }

    @Test
    fun testThreeClipsUnequalDurationsFidelity() {
        // Test 3 image clips with unequal durations: 3.5s, 7.2s, 4.1s
        val clip0DurationMs = 3500L
        val clip1DurationMs = 7200L
        val clip2DurationMs = 4100L

        val sourceUri = DummyUri.fromPath("/tmp/source_image.jpg")
        val clip1Uri = DummyUri.fromPath("/tmp/image_1.jpg")
        val clip2Uri = DummyUri.fromPath("/tmp/image_2.png")

        val trim0 = EditOperation.Trim(startMs = 0L, endMs = clip0DurationMs)
        val mergeItems = listOf(
            EditOperation.MergeItem(
                uri = clip1Uri,
                durationMs = clip1DurationMs,
                trimStartMs = 0L,
                trimEndMs = clip1DurationMs,
                isImage = true
            ),
            EditOperation.MergeItem(
                uri = clip2Uri,
                durationMs = clip2DurationMs,
                trimStartMs = 0L,
                trimEndMs = clip2DurationMs,
                isImage = true
            )
        )
        val mergeOp = EditOperation.Merge(items = mergeItems)

        val project = VideoProject(
            sourceUri = sourceUri,
            sourceName = "source_image.jpg",
            operations = listOf(trim0, mergeOp)
        )

        val viewModel = VideoEditingViewModel()
        viewModel.loadProject(project)

        val cmd = viewModel.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/tmp/source_image.jpg",
            outputFilePath = "/tmp/output.mp4"
        )
        assertNotNull("Command must be generated", cmd)

        // Verify each clip's -t flag equals the persisted timeline duration
        assertTrue("Clip 0 must have -t 3.5", cmd!!.contains("-framerate 30 -loop 1 -t 3.5 -i \"/tmp/source_image.jpg\""))
        assertTrue("Clip 1 must have -t 7.2", cmd.contains("-framerate 30 -loop 1 -t 7.2 -i \"/tmp/image_1.jpg\""))
        assertTrue("Clip 2 must have -t 4.1", cmd.contains("-framerate 30 -loop 1 -t 4.1 -i \"/tmp/image_2.png\""))

        // Total timeline duration: 3.5 + 7.2 + 4.1 = 14.8s
        val expectedTotal = (clip0DurationMs + clip1DurationMs + clip2DurationMs) / 1000.0
        assertTrue("Final output -t must match total accumulated duration 14.8", cmd.contains("-t $expectedTotal"))
    }

    @Test
    fun testAppLoggerRedactsSensitiveTokens() {
        val secretInput = "Starting export with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 and apikey: secret_12345"
        val redacted = AppLogger.redact(secretInput)

        assertFalse("Must not contain Bearer token", redacted.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse("Must not contain secret_12345", redacted.contains("secret_12345"))
        assertTrue("Must contain [REDACTED]", redacted.contains("[REDACTED]"))
    }

    /**
     * Simulates FFmpeg's filter chain parsing:
     * Splits into tokens separated by unescaped commas outside single quotes.
     */
    private fun parseFFmpegFilterTokens(filterString: String): List<String> {
        val tokens = mutableListOf<String>()
        var inQuote = false
        var isEscaped = false
        val currentToken = StringBuilder()

        for (c in filterString) {
            if (isEscaped) {
                currentToken.append(c)
                isEscaped = false
                continue
            }
            if (c == '\\') {
                isEscaped = true
                currentToken.append(c)
                continue
            }
            if (c == '\'') {
                inQuote = !inQuote
                currentToken.append(c)
                continue
            }
            if (c == ',' && !inQuote) {
                tokens.add(currentToken.toString())
                currentToken.setLength(0)
                continue
            }
            currentToken.append(c)
        }
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }
        return tokens
    }
}

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
        assertTrue("Single quote must survive 2-level FFmpeg filtergraph parsing", escaped.contains("'\\\\\\''"))
    }

    @Test
    fun testDrawtextWithSingleQuotesPreservesCoordinatesAndEnableThroughTwoLevelParser() {
        val rawCue = "It's 10:00 [intro]; don't blink, keep watching!"
        val filter = FFmpegFilterUtils.buildValidatedDrawtextFilter(
            text = rawCue,
            fontFilePath = "/tmp/Roboto-Regular.ttf",
            fontSize = 36,
            fontColor = "white",
            x = "(w-tw)/2",
            y = "h-48-th",
            startTimeMs = 1500L,
            endTimeMs = 4250L,
            boxColor = "black@0.5",
            boxBorderW = 8
        )

        // Pass 1: libavfilter parse_filter splits on "[],;" outside single quotes and strips one quote/escape layer
        val pass1Tokens = parseFFmpegFilterPass1(filter)
        assertEquals("Must remain a single drawtext filter without splitting on ',' or ';'", 1, pass1Tokens.size)
        val pass1Filter = pass1Tokens[0]
        assertTrue("Must begin with drawtext=", pass1Filter.startsWith("drawtext="))

        // Pass 2: av_set_options_string splits on ':' outside quotes/escapes
        val options = parseDrawtextOptionsPass2(pass1Filter.removePrefix("drawtext="))
        assertEquals("Decoded text must match raw cue", rawCue, options["text"])
        assertEquals("X coordinate must not be swallowed into text", "(w-tw)/2", options["x"])
        assertEquals("Y coordinate must not be swallowed into text", "h-48-th", options["y"])
        assertEquals("36", options["fontsize"])
        assertEquals("white", options["fontcolor"])
        assertEquals("between(t,1.500,4.250)", options["enable"])
    }

    @Test
    fun testNormalizeCaptionOperationsUnifiesAutoCaptionsAndPreventsTopDuplicates() {
        val legacyAuto1 = EditOperation.AddText(
            text = "It's a test",
            fontSize = 24,
            position = TextPosition.BOTTOM_CENTER,
            relativeX = 0.5f,
            relativeY = 0.85f,
            startTimeMs = 0L,
            endTimeMs = 2000L,
            fontPath = "/tmp/CustomFont.ttf",
            groupId = com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID
        )
        val normalizedFromLegacy = com.boostlyte.vydra.captions.CaptionCueBuilder.normalizeCaptionOperations(listOf(legacyAuto1))
        assertEquals(1, normalizedFromLegacy.size)
        val unified = normalizedFromLegacy[0] as EditOperation.AddSubtitles
        assertEquals("Auto Captions", unified.fileName)
        assertEquals(1, unified.cues.size)
        assertEquals("It's a test", unified.cues[0].text)
        assertEquals("/tmp/CustomFont.ttf", unified.fontPath)
        assertNull("Default auto-caption anchor must normalize to default position", unified.relativeX)

        // When explicit .srt AddSubtitles exists alongside legacy auto-caption AddText, legacy must be stripped
        val srtSub = EditOperation.AddSubtitles(
            subtitlesUri = DummyUri.fromPath("/tmp/test.srt"),
            srtContent = "1\n00:00:00,000 --> 00:00:02,000\nReal SRT cue\n",
            fileName = "test.srt",
            cues = listOf(com.boostlyte.vydra.models.SubtitleCue(0L, 2000L, "Real SRT cue"))
        )
        val cleaned = com.boostlyte.vydra.captions.CaptionCueBuilder.normalizeCaptionOperations(listOf(legacyAuto1, srtSub))
        assertEquals(1, cleaned.size)
        assertTrue(cleaned[0] is EditOperation.AddSubtitles)
        assertEquals("test.srt", (cleaned[0] as EditOperation.AddSubtitles).fileName)
    }

    @Test
    fun testSmallAndLargeProjectUnifiedSubtitlesProduceBottomPositionedDrawtextWithoutTopText() {
        val sampleCues = listOf(
            com.boostlyte.vydra.captions.CaptionCue(500L, 2500L, "It's sunny outside!"),
            com.boostlyte.vydra.captions.CaptionCue(3000L, 5000L, "Second cue: ready.")
        )
        val autoSubOp = com.boostlyte.vydra.captions.CaptionCueBuilder.cuesToAddSubtitles(sampleCues)
        val srtSubOp = EditOperation.AddSubtitles(
            subtitlesUri = DummyUri.fromPath("/tmp/subs.srt"),
            srtContent = "",
            fileName = "subs.srt",
            cues = autoSubOp.cues
        )

        // Small project (<= 16 clips): compare Auto Captions vs .SRT outputs
        val smallItems = (1..3).map { idx ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/img_$idx.jpg"),
                durationMs = 2000L,
                trimStartMs = 0L,
                trimEndMs = 2000L,
                isImage = true
            )
        }
        val vmSmallAuto = VideoEditingViewModel()
        vmSmallAuto.loadProject(
            VideoProject(
                sourceUri = DummyUri.fromPath("/tmp/img_0.jpg"),
                sourceName = "img_0.jpg",
                operations = listOf(EditOperation.Trim(0L, 2000L), EditOperation.Merge(smallItems), autoSubOp)
            )
        )
        val smallAutoCmd = vmSmallAuto.buildConsolidatedFFmpegCommand("/tmp/img_0.jpg", "/tmp/out_auto.mp4")!!

        val vmSmallSrt = VideoEditingViewModel()
        vmSmallSrt.loadProject(
            VideoProject(
                sourceUri = DummyUri.fromPath("/tmp/img_0.jpg"),
                sourceName = "img_0.jpg",
                operations = listOf(EditOperation.Trim(0L, 2000L), EditOperation.Merge(smallItems), srtSubOp)
            )
        )
        val smallSrtCmd = vmSmallSrt.buildConsolidatedFFmpegCommand("/tmp/img_0.jpg", "/tmp/out_srt.mp4")!!

        // Both small commands use the exact same bottom-aligned drawtext structure with escaped quotes
        assertTrue("Small auto-captions must use bottom y=h-...-th", smallAutoCmd.contains(":y=h-") && smallAutoCmd.contains(":x=(w-tw)/2"))
        assertTrue("Small .srt subtitles must use bottom y=h-...-th", smallSrtCmd.contains(":y=h-") && smallSrtCmd.contains(":x=(w-tw)/2"))
        assertTrue("Apostrophe must survive 2-level escape in small export", smallAutoCmd.contains("It'\\\\\\''s sunny outside!"))
        assertFalse("Must not contain top-left fallback y=0", smallAutoCmd.contains(":y=0:"))
        assertFalse("Must not contain top-left fallback y=0", smallSrtCmd.contains(":y=0:"))

        // Large project (> 16 clips): verify chunked export uses the same AddSubtitles system cleanly
        val largeItems = (1..18).map { idx ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/large_$idx.jpg"),
                durationMs = 1500L,
                trimStartMs = 0L,
                trimEndMs = 1500L,
                isImage = true
            )
        }
        val vmLarge = VideoEditingViewModel()
        vmLarge.loadProject(
            VideoProject(
                sourceUri = DummyUri.fromPath("/tmp/large_0.jpg"),
                sourceName = "large_0.jpg",
                operations = listOf(EditOperation.Trim(0L, 1500L), EditOperation.Merge(largeItems), autoSubOp)
            )
        )
        val chunkPlan = vmLarge.buildChunkExportPlan("/tmp/large_0.jpg", "/tmp/out_large.mp4")
        assertNotNull("Chunk plan must be built for >16 clips", chunkPlan)
        assertTrue("Large project must use chunked export", chunkPlan!!.isChunked)
        assertTrue("First chunk must carry bottom-positioned subtitle drawtext", chunkPlan.chunkCommands[0].command.contains(":y=h-"))
        assertTrue("First chunk must keep 2-level quote escape", chunkPlan.chunkCommands[0].command.contains("It'\\\\\\''s sunny outside!"))
        assertFalse("First chunk must not contain top-left y=0", chunkPlan.chunkCommands[0].command.contains(":y=0:"))
        assertFalse("Final assembly must not re-apply drawtext", (chunkPlan.finalPassCommand ?: "").contains("drawtext="))
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

        // Verify each non-animated-mask image is decoded once (no -loop 1) and uses explicit trim duration
        assertFalse("Static/Ken Burns images must not use -loop 1", cmd!!.contains("-loop 1"))
        assertTrue("Clip 0 must be decoded once at 30fps", cmd.contains("-framerate 30 -i \"/tmp/source_image.jpg\""))
        assertTrue("Clip 1 must be decoded once at 30fps", cmd.contains("-framerate 30 -i \"/tmp/image_1.jpg\""))
        assertTrue("Clip 2 must be decoded once at 30fps", cmd.contains("-framerate 30 -i \"/tmp/image_2.png\""))
        assertTrue("Clip 0 must have explicit 3.5s duration", cmd.contains("trim=duration=3.5") || cmd.contains("d=105"))
        assertTrue("Clip 1 must have explicit 7.2s duration", cmd.contains("trim=duration=7.2") || cmd.contains("d=216"))
        assertTrue("Clip 2 must have explicit 4.1s duration", cmd.contains("trim=duration=4.1") || cmd.contains("d=123"))

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

    /**
     * Simulates libavfilter's exact `av_get_token(&p, "[],;")` pass (Pass 1):
     * inside `'...'`, everything is copied literally; outside quotes, `\` escapes the next char.
     */
    private fun parseFFmpegFilterPass1(graph: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < graph.length) {
            val c = graph[i]
            when {
                c == '\'' -> {
                    i++
                    while (i < graph.length && graph[i] != '\'') {
                        sb.append(graph[i++])
                    }
                    if (i < graph.length) i++
                }
                c == '\\' && i + 1 < graph.length -> {
                    sb.append(graph[i + 1])
                    i += 2
                }
                c == ',' || c == ';' -> {
                    result.add(sb.toString())
                    sb.setLength(0)
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    /**
     * Simulates FFmpeg `av_set_options_string` option splitting on `:` using `av_get_token(..., ":")`.
     */
    private fun parseDrawtextOptionsPass2(opts: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < opts.length) {
            val eqIdx = opts.indexOf('=', i)
            if (eqIdx == -1) break
            val key = opts.substring(i, eqIdx)
            i = eqIdx + 1
            val valSb = StringBuilder()
            while (i < opts.length) {
                val c = opts[i]
                when {
                    c == '\'' -> {
                        i++
                        while (i < opts.length && opts[i] != '\'') {
                            valSb.append(opts[i++])
                        }
                        if (i < opts.length) i++
                    }
                    c == '\\' && i + 1 < opts.length -> {
                        valSb.append(opts[i + 1])
                        i += 2
                    }
                    c == ':' -> {
                        i++
                        break
                    }
                    else -> {
                        valSb.append(c)
                        i++
                    }
                }
            }
            map[key] = valSb.toString()
        }
        return map
    }
}

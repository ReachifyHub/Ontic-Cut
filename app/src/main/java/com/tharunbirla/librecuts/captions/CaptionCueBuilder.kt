package com.tharunbirla.librecuts.captions

import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.TextPosition
import com.tharunbirla.librecuts.transcription.models.TranscriptionResponse
import com.tharunbirla.librecuts.transcription.models.TranscriptionSegment
import com.tharunbirla.librecuts.transcription.models.TranscriptionWord

object CaptionCueBuilder {

    // Tuning constants as required by Phase 6
    const val MAX_CHARS_PER_CUE = 42
    const val MAX_WORDS_PER_CUE = 6
    const val MAX_CUE_DURATION_MS = 3000L
    const val MIN_CUE_DURATION_MS = 300L

    // Dedicated caption group identifier for idempotent replace & selective deletion
    const val CAPTION_GROUP_ID = "auto-captions"

    // Default style constants (Horizontally centered, bottom safe margin, outline for high readability)
    const val DEFAULT_FONT_SIZE = 22
    const val DEFAULT_COLOR = "#FFFFFF"
    const val DEFAULT_BORDER_THICKNESS = 3
    const val DEFAULT_BORDER_COLOR = "#000000"
    const val DEFAULT_TEXT_ALIGN = "center"
    val DEFAULT_POSITION = TextPosition.BOTTOM_CENTER
    const val DEFAULT_RELATIVE_X = 0.5f
    const val DEFAULT_RELATIVE_Y = 0.85f // Centered near bottom with safe margin

    /**
     * Builds timed caption cues from a TranscriptionResponse.
     * Accurately distinguishes between word-level and segment-based timing.
     */
    fun buildCues(
        transcription: TranscriptionResponse,
        audioDurationMs: Long
    ): CaptionBuildResult {
        val fullText = transcription.getFullText().trim()
        if (fullText.isEmpty()) {
            return CaptionBuildResult.Error("Transcription is empty. No captions can be generated.")
        }

        // 1. Check for word-level timestamps
        val allWords = extractAllValidWords(transcription)
        val hasWordTimestamps = allWords.isNotEmpty() && allWords.any { (it.end ?: 0.0) > (it.start ?: 0.0) }

        val rawCues = mutableListOf<CaptionCue>()
        val mode: CaptionTimestampMode

        if (hasWordTimestamps) {
            mode = CaptionTimestampMode.WORD_LEVEL
            rawCues.addAll(buildCuesFromWords(allWords))
        } else {
            // 2. Fall back to segment-based distribution if segments exist
            val segments = transcription.segments?.filter { (it.end > it.start) && it.text.isNotBlank() }
            if (!segments.isNullOrEmpty()) {
                mode = CaptionTimestampMode.SEGMENT_BASED
                rawCues.addAll(buildCuesFromSegments(segments))
            } else {
                // Neither words nor timed segments exist
                return CaptionBuildResult.Error(
                    "Transcription contains no usable timing data. Captions cannot be generated."
                )
            }
        }

        if (rawCues.isEmpty()) {
            return CaptionBuildResult.Error("No valid caption cues could be extracted from transcription.")
        }

        // 3. Post-process cues: sort, eliminate overlaps, clamp to audio duration, wrap lines
        val sanitizedCues = sanitizeAndClampCues(rawCues, audioDurationMs)
        if (sanitizedCues.isEmpty()) {
            return CaptionBuildResult.Error("Generated cues fell outside audio duration.")
        }

        return CaptionBuildResult.Success(
            cues = sanitizedCues,
            mode = mode
        )
    }

    /**
     * Converts CaptionCue objects to native EditOperation.AddText overlays.
     */
    fun cuesToEditOperations(cues: List<CaptionCue>): List<EditOperation.AddText> {
        return cues.map { cue ->
            EditOperation.AddText(
                text = cue.text,
                fontSize = DEFAULT_FONT_SIZE,
                position = DEFAULT_POSITION,
                relativeX = DEFAULT_RELATIVE_X,
                relativeY = DEFAULT_RELATIVE_Y,
                color = DEFAULT_COLOR,
                startTimeMs = cue.startMs,
                endTimeMs = cue.endMs,
                borderThickness = DEFAULT_BORDER_THICKNESS,
                borderColor = DEFAULT_BORDER_COLOR,
                textAlign = DEFAULT_TEXT_ALIGN,
                groupId = CAPTION_GROUP_ID
            )
        }
    }

    private fun extractAllValidWords(transcription: TranscriptionResponse): List<TranscriptionWord> {
        // Words can be at root level or within segments
        val list = mutableListOf<TranscriptionWord>()
        transcription.words?.let { list.addAll(it) }
        if (list.isEmpty()) {
            transcription.segments?.forEach { seg ->
                seg.words?.let { list.addAll(it) }
            }
        }
        return list.filter { word ->
            word.start != null && word.end != null && word.word.isNotBlank()
        }.sortedBy { it.start ?: 0.0 }
    }

    private fun buildCuesFromWords(words: List<TranscriptionWord>): List<CaptionCue> {
        val cues = mutableListOf<CaptionCue>()
        var currentWords = mutableListOf<TranscriptionWord>()
        var currentChars = 0

        fun flushCurrentCue() {
            if (currentWords.isEmpty()) return
            val startMs = (currentWords.first().start!! * 1000.0).toLong()
            val endMs = (currentWords.last().end!! * 1000.0).toLong().coerceAtLeast(startMs + MIN_CUE_DURATION_MS)
            val text = currentWords.joinToString(" ") { it.word.trim() }.trim()
            if (text.isNotEmpty()) {
                cues.add(CaptionCue(startMs, endMs, wrapTextToTwoLines(text)))
            }
            currentWords = mutableListOf()
            currentChars = 0
        }

        for (word in words) {
            val wordText = word.word.trim()
            val wordStartMs = (word.start!! * 1000.0).toLong()
            val wordEndMs = (word.end!! * 1000.0).toLong()

            val potentialChars = if (currentWords.isEmpty()) wordText.length else currentChars + 1 + wordText.length
            val cueStartMs = if (currentWords.isEmpty()) wordStartMs else (currentWords.first().start!! * 1000.0).toLong()
            val potentialDuration = wordEndMs - cueStartMs

            val exceedsWords = currentWords.size >= MAX_WORDS_PER_CUE
            val exceedsChars = potentialChars > MAX_CHARS_PER_CUE
            val exceedsDuration = potentialDuration > MAX_CUE_DURATION_MS

            // Break preference on sentence punctuation
            val prevWord = currentWords.lastOrNull()?.word?.trim() ?: ""
            val prevEndsTerminal = prevWord.endsWith(".") || prevWord.endsWith("?") || prevWord.endsWith("!")
            val prevEndsClause = prevWord.endsWith(",") || prevWord.endsWith(";") || prevWord.endsWith(":")

            val shouldFlushBefore = (prevEndsTerminal && currentWords.size >= 2) ||
                    (prevEndsClause && (currentWords.size >= 4 || currentChars >= 25)) ||
                    exceedsWords || exceedsChars || exceedsDuration

            if (shouldFlushBefore && currentWords.isNotEmpty()) {
                flushCurrentCue()
            }

            currentWords.add(word)
            currentChars = if (currentWords.size == 1) wordText.length else currentChars + 1 + wordText.length
        }

        flushCurrentCue()
        return cues
    }

    private fun buildCuesFromSegments(segments: List<TranscriptionSegment>): List<CaptionCue> {
        val cues = mutableListOf<CaptionCue>()

        for (seg in segments) {
            val segStartMs = (seg.start * 1000.0).toLong()
            val segEndMs = (seg.end * 1000.0).toLong()
            val segDuration = segEndMs - segStartMs
            if (segDuration <= 0) continue

            val words = seg.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) continue

            // Chunk words into cue text blocks
            val chunks = mutableListOf<String>()
            var currentChunk = mutableListOf<String>()
            var currentChunkChars = 0

            for (w in words) {
                val potentialChars = if (currentChunk.isEmpty()) w.length else currentChunkChars + 1 + w.length
                val exceedsWords = currentChunk.size >= MAX_WORDS_PER_CUE
                val exceedsChars = potentialChars > MAX_CHARS_PER_CUE

                val prev = currentChunk.lastOrNull() ?: ""
                val prevPunct = prev.endsWith(".") || prev.endsWith("?") || prev.endsWith("!")

                if ((prevPunct && currentChunk.size >= 2) || exceedsWords || exceedsChars) {
                    if (currentChunk.isNotEmpty()) {
                        chunks.add(currentChunk.joinToString(" "))
                        currentChunk = mutableListOf()
                        currentChunkChars = 0
                    }
                }

                currentChunk.add(w)
                currentChunkChars = if (currentChunk.size == 1) w.length else currentChunkChars + 1 + w.length
            }

            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.joinToString(" "))
            }

            if (chunks.isEmpty()) continue

            if (chunks.size == 1) {
                cues.add(CaptionCue(segStartMs, segEndMs, wrapTextToTwoLines(chunks[0])))
            } else {
                // Distribute segment duration proportionally across chunks
                val totalChars = chunks.sumOf { it.length }.coerceAtLeast(1)
                var currentStart = segStartMs
                for (i in chunks.indices) {
                    val chunk = chunks[i]
                    val isLast = (i == chunks.size - 1)
                    val chunkDur = if (isLast) {
                        (segEndMs - currentStart).coerceAtLeast(MIN_CUE_DURATION_MS)
                    } else {
                        ((chunk.length.toDouble() / totalChars) * segDuration).toLong().coerceAtLeast(MIN_CUE_DURATION_MS)
                    }
                    val chunkEnd = if (isLast) segEndMs else (currentStart + chunkDur).coerceAtMost(segEndMs)
                    cues.add(CaptionCue(currentStart, chunkEnd, wrapTextToTwoLines(chunk)))
                    currentStart = chunkEnd
                }
            }
        }

        return cues
    }

    private fun sanitizeAndClampCues(
        rawCues: List<CaptionCue>,
        audioDurationMs: Long
    ): List<CaptionCue> {
        val sorted = rawCues.sortedBy { it.startMs }.toMutableList()
        val result = mutableListOf<CaptionCue>()

        for (i in 0 until sorted.size) {
            var start = sorted[i].startMs
            var end = sorted[i].endMs

            // 1. Non-overlap enforcement: if current cue overlaps next cue, trim current cue end
            if (i < sorted.size - 1) {
                val nextStart = sorted[i + 1].startMs
                if (end > nextStart) {
                    end = nextStart
                }
            }

            // Ensure positive duration
            if (end <= start) {
                end = start + MIN_CUE_DURATION_MS
            }

            // 2. Clamp to audioDurationMs
            if (audioDurationMs > 0) {
                if (start >= audioDurationMs) {
                    continue // Drop cues that start after audio ends
                }
                if (end > audioDurationMs) {
                    end = audioDurationMs
                }
            }

            if (end > start) {
                result.add(CaptionCue(start, end, sorted[i].text))
            }
        }

        return result
    }

    /**
     * Wraps caption text into at most 2 lines at the word boundary closest to the center
     * if the text exceeds 24 characters and does not already contain a newline.
     */
    fun wrapTextToTwoLines(text: String, thresholdChars: Int = 24): String {
        if (text.contains("\n") || text.length <= thresholdChars) return text
        val words = text.split(" ")
        if (words.size <= 2) return text

        val targetMid = text.length / 2
        var bestIndex = -1
        var minDiff = Int.MAX_VALUE
        var runningLen = 0

        for (i in 0 until words.size - 1) {
            runningLen += words[i].length
            val diff = kotlin.math.abs(runningLen - targetMid)
            if (diff < minDiff) {
                minDiff = diff
                bestIndex = i
            }
            runningLen += 1 // space
        }

        return if (bestIndex in 0 until words.size - 1) {
            val line1 = words.subList(0, bestIndex + 1).joinToString(" ")
            val line2 = words.subList(bestIndex + 1, words.size).joinToString(" ")
            "$line1\n$line2"
        } else {
            text
        }
    }
}

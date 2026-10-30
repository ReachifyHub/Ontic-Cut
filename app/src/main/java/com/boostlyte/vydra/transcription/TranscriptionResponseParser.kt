package com.boostlyte.vydra.transcription

import android.content.Context
import android.util.Log
import com.boostlyte.vydra.BuildConfig
import com.boostlyte.vydra.transcription.models.TranscriptionResponse
import com.boostlyte.vydra.transcription.models.TranscriptionSegment
import com.boostlyte.vydra.transcription.models.TranscriptionWord
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlin.math.roundToLong

/**
 * Robust, schema-adaptive parser for Grok Speech-to-Text via NanoGPT and Whisper responses.
 *
 * Supports:
 * 1. Structured JSON with root "words" or "segments" array.
 * 2. Plain-text lines with anchored word timestamps: `[<start>s - <end>s] <word>`.
 * 3. Decimal seconds to milliseconds conversion with exact sub-second rounding (e.g. 0.06s -> 60ms).
 * 4. Header duration extraction (e.g. "Duration: 0.59 minutes" -> 35.4s).
 * 5. String vs numeric timestamps (e.g. "0.06s", "0.06", 0.06).
 */
object TranscriptionResponseParser {

    private const val TAG = "TranscriptionParser"
    private const val CAPTURE_TAG = "GrokRawCapture"

    // Anchored regex matching plain-text timestamped word lines:
    // e.g. "[0.06s - 0.18s] You", "[30.73s - 31.07s] doesn't", "[1.32 - 1.90] everywhere,"
    private val WORD_LINE_REGEX = Regex(
        """^\[\s*([0-9]+(?:\.[0-9]+)?)\s*s?\s*-\s*([0-9]+(?:\.[0-9]+)?)\s*s?\s*\]\s+(.+)$"""
    )

    // Regex matching human-readable duration headers:
    // e.g. "Duration: 0.59 minutes", "0.59 minutes", "Duration: 35.4 seconds", "Duration: 35.4s"
    private val DURATION_HEADER_REGEX = Regex(
        """(?:Duration:\s*)?([0-9]+(?:\.[0-9]+)?)\s*(minutes?|mins?|seconds?|secs?|s)?""",
        RegexOption.IGNORE_CASE
    )

    private val gson = Gson()

    /**
     * Converts fractional/decimal seconds to milliseconds with exact rounding.
     * Guaranteed: 0.06 s -> 60 ms (never truncated to 0).
     */
    fun secondsToMillis(seconds: Double): Long {
        if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return 0L
        return (seconds * 1000.0).roundToLong()
    }

    /**
     * Parses a single plain-text line into a [TranscriptionWord] if it matches the pattern:
     * `[<start>s - <end>s] <word>`
     */
    fun parseWordTimestampLine(line: String): TranscriptionWord? {
        val trimmed = line.trim()
        val match = WORD_LINE_REGEX.find(trimmed) ?: return null
        val startSec = match.groupValues[1].toDoubleOrNull() ?: return null
        val endSec = match.groupValues[2].toDoubleOrNull() ?: return null
        val wordText = match.groupValues[3].trim()
        if (wordText.isEmpty()) return null

        return TranscriptionWord(
            word = wordText,
            start = startSec,
            end = endSec
        )
    }

    /**
     * Parses a duration string (e.g. "0.59 minutes", "35.4s", "36") into duration in seconds.
     */
    fun parseDurationString(durationStr: String?): Double? {
        if (durationStr.isNullOrBlank()) return null
        val match = DURATION_HEADER_REGEX.find(durationStr)
        if (match != null) {
            val value = match.groupValues[1].toDoubleOrNull() ?: return null
            val unit = match.groupValues[2].lowercase()
            return if (unit.startsWith("min")) value * 60.0 else value
        }
        val cleanNumber = durationStr.replace("s", "").trim()
        return cleanNumber.toDoubleOrNull()
    }

    /**
     * Parses the response from the Edge Function into a [TranscriptionResponse].
     * Handles both structured JSON and plain-text output.
     */
    fun parse(rawResponse: String): TranscriptionResponse {
        if (rawResponse.isBlank()) {
            return TranscriptionResponse(rawJson = rawResponse)
        }

        return try {
            val jsonElement = JsonParser.parseString(rawResponse)
            if (jsonElement.isJsonObject) {
                parseJsonObject(jsonElement.asJsonObject, rawResponse)
            } else {
                parsePlainText(rawResponse)
            }
        } catch (_: Exception) {
            // Not valid JSON -> treat directly as plain text
            parsePlainText(rawResponse)
        }
    }

    private fun parseJsonObject(root: JsonObject, rawResponse: String): TranscriptionResponse {
        // 1. Check for text / transcript / transcription at root or inside envelopes
        val text = root.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("transcript")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("transcription")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("result")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.getAsJsonObject("metadata")?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.getAsJsonObject("metadata")?.get("transcription")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.getAsJsonObject("response")?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.getAsJsonObject("response")?.get("transcription")?.takeIf { it.isJsonPrimitive }?.asString

        // 2. Check for duration at root or in metadata/result
        var durationSec: Double? = null
        val durationElem = root.get("duration")
            ?: root.getAsJsonObject("metadata")?.get("duration")
            ?: root.getAsJsonObject("result")?.get("duration")
        if (durationElem != null) {
            durationSec = if (durationElem.isJsonPrimitive && durationElem.asJsonPrimitive.isNumber) {
                durationElem.asDouble
            } else {
                parseDurationString(durationElem.asString)
            }
        }

        // 3. Check for language
        val language = root.get("language")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.getAsJsonObject("metadata")?.get("language")?.takeIf { it.isJsonPrimitive }?.asString

        // 4. Check for structured words array at root, metadata, result, or response
        val wordsArray = root.getAsJsonArray("words")
            ?: root.getAsJsonObject("metadata")?.getAsJsonArray("words")
            ?: root.getAsJsonObject("result")?.getAsJsonArray("words")
            ?: root.getAsJsonObject("data")?.getAsJsonArray("words")
            ?: root.getAsJsonObject("response")?.getAsJsonArray("words")
            ?: root.getAsJsonObject("transcription")?.getAsJsonArray("words")

        val structuredWords = mutableListOf<TranscriptionWord>()
        if (wordsArray != null) {
            for (elem in wordsArray) {
                if (!elem.isJsonObject) continue
                val wObj = elem.asJsonObject
                val wordText = wObj.get("word")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: wObj.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: continue
                val start = parseFlexibleTimestamp(wObj.get("start") ?: wObj.get("startTime") ?: wObj.get("start_time"))
                val end = parseFlexibleTimestamp(wObj.get("end") ?: wObj.get("endTime") ?: wObj.get("end_time"))
                val score = wObj.get("score")?.takeIf { it.isJsonPrimitive }?.asDouble
                    ?: wObj.get("confidence")?.takeIf { it.isJsonPrimitive }?.asDouble
                    ?: wObj.get("prob")?.takeIf { it.isJsonPrimitive }?.asDouble

                structuredWords.add(
                    TranscriptionWord(
                        word = wordText,
                        start = start,
                        end = end,
                        score = score
                    )
                )
            }
        }

        // 5. Check for structured segments
        val segmentsArray = root.getAsJsonArray("segments")
            ?: root.getAsJsonObject("metadata")?.getAsJsonArray("segments")
            ?: root.getAsJsonObject("result")?.getAsJsonArray("segments")
            ?: root.getAsJsonObject("response")?.getAsJsonArray("segments")
        var segmentsList: List<TranscriptionSegment>? = null
        if (segmentsArray != null && segmentsArray.size() > 0) {
            segmentsList = try {
                segmentsArray.mapNotNull { segElem ->
                    if (!segElem.isJsonObject) null else gson.fromJson(segElem, TranscriptionSegment::class.java)
                }
            } catch (_: Exception) {
                null
            }
        }

        // 6. If no structured words found, check if text contains embedded word-timestamp lines:
        // "[0.06s - 0.18s] You..."
        val extractedWords = if (structuredWords.isNotEmpty()) {
            structuredWords
        } else {
            text?.let { extractWordsFromTextLines(it) } ?: emptyList()
        }

        // 7. If duration was not found in JSON root, try extracting from text or last word
        if (durationSec == null || durationSec <= 0.0) {
            if (!text.isNullOrBlank()) {
                durationSec = parseDurationFromTextHeader(text)
            }
            if ((durationSec == null || durationSec <= 0.0) && extractedWords.isNotEmpty()) {
                durationSec = extractedWords.mapNotNull { it.end }.lastOrNull()
            }
        }

        // Extract clean text (strip "--- Detailed Information ---" block if present)
        val cleanText = text?.let { cleanTranscriptText(it) }

        return TranscriptionResponse(
            text = cleanText,
            segments = segmentsList,
            words = if (extractedWords.isNotEmpty()) extractedWords else null,
            language = language,
            duration = durationSec,
            rawJson = rawResponse
        )
    }

    private fun parsePlainText(plainText: String): TranscriptionResponse {
        val words = extractWordsFromTextLines(plainText)
        val duration = parseDurationFromTextHeader(plainText)
            ?: words.mapNotNull { it.end }.lastOrNull()
        val cleanText = cleanTranscriptText(plainText)

        return TranscriptionResponse(
            text = cleanText,
            segments = null,
            words = if (words.isNotEmpty()) words else null,
            language = extractLanguageFromHeader(plainText),
            duration = duration,
            rawJson = plainText
        )
    }

    private fun parseFlexibleTimestamp(elem: JsonElement?): Double? {
        if (elem == null || elem.isJsonNull) return null
        return if (elem.isJsonPrimitive && elem.asJsonPrimitive.isNumber) {
            elem.asDouble
        } else {
            val str = elem.asString.replace("s", "").trim()
            str.toDoubleOrNull()
        }
    }

    /**
     * Extracts [TranscriptionWord] entries from lines of the form:
     * `[0.06s - 0.18s] You`
     */
    fun extractWordsFromTextLines(text: String): List<TranscriptionWord> {
        val words = mutableListOf<TranscriptionWord>()
        val lines = text.lineSequence()
        for (line in lines) {
            val word = parseWordTimestampLine(line)
            if (word != null) {
                words.add(word)
            }
        }
        return words
    }

    private fun parseDurationFromTextHeader(text: String): Double? {
        val match = DURATION_HEADER_REGEX.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        return if (unit.startsWith("min")) value * 60.0 else value
    }

    private fun extractLanguageFromHeader(text: String): String? {
        val match = Regex("""Language:\s*([a-zA-Z_-]+)""").find(text)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun cleanTranscriptText(text: String): String {
        // If the text contains the "--- Detailed Information ---" separator,
        // extract the transcript paragraph before it.
        val separator = "--- Detailed Information ---"
        return if (text.contains(separator)) {
            text.substringBefore(separator).trim()
        } else {
            // If lines start with [0.00s - 0.00s], filter them out for clean text
            val nonTimestampLines = text.lines().filter { !it.trim().startsWith("[") }
            if (nonTimestampLines.isNotEmpty()) {
                nonTimestampLines.joinToString(" ").trim()
            } else {
                text.trim()
            }
        }
    }

    /**
     * Dev-only capture: writes the exact raw response body and parsed model to
     * app storage and logs to Logcat under [CAPTURE_TAG].
     * Excludes all secrets (tokens, api keys, signed URLs).
     * Strictly disabled in release builds.
     */
    fun saveDevCapture(rawResponse: String, parsed: TranscriptionResponse, context: Context?) {
        if (!BuildConfig.DEBUG) return

        try {
            Log.d(CAPTURE_TAG, "=== RAW GROK/NANOGPT RESPONSE (${rawResponse.length} bytes) ===")
            // Log in chunks if very long to prevent logcat truncation
            val maxChunk = 3000
            var i = 0
            while (i < rawResponse.length) {
                val end = minOf(i + maxChunk, rawResponse.length)
                Log.d(CAPTURE_TAG, rawResponse.substring(i, end))
                i = end
            }
            Log.d(
                CAPTURE_TAG,
                "=== PARSED MODEL: words=${parsed.words?.size ?: 0}, segments=${parsed.segments?.size ?: 0}, duration=${parsed.duration}s ==="
            )

            // Save to app-specific file
            val targetDir = context?.getExternalFilesDir("transcription_captures")
                ?: context?.filesDir?.let { File(it, "transcription_captures") }
                ?: File("transcription_captures")

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val timestamp = System.currentTimeMillis()
            val captureFile = File(targetDir, "raw_grok_response_$timestamp.json")

            val capturePayload = JsonObject().apply {
                addProperty("_captured_at_ms", timestamp)
                addProperty("_response_length_bytes", rawResponse.length)
                addProperty("rawResponse", rawResponse)
                add("parsedModel", gson.toJsonTree(parsed))
            }

            FileOutputStream(captureFile).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    writer.write(gson.toJson(capturePayload))
                    writer.flush()
                }
            }

            Log.i(CAPTURE_TAG, "Dev capture saved to: ${captureFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(CAPTURE_TAG, "Failed to write dev capture: ${e.message}")
        }
    }
}

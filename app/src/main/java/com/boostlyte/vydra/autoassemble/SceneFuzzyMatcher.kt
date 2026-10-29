package com.boostlyte.vydra.autoassemble

import android.util.Log
import com.boostlyte.vydra.models.SceneBoundary
import com.boostlyte.vydra.transcription.TranscriptionResponseParser
import com.boostlyte.vydra.transcription.models.TranscriptionResponse
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Timestamped word token extracted from a Whisper or Grok transcription.
 */
data class TimestampedToken(
    val normalized: String,
    val original: String,
    val startMs: Long,
    val endMs: Long,
    val segmentIndex: Int
)

/**
 * Robust fuzzy-matching alignment algorithm that maps script scenes to transcription timestamps.
 *
 * Architecture:
 * 1. Text Normalization: Converts text to lowercase, unifies apostrophes/contractions identically
 *    (e.g. "doesn't" and "doesnt" -> "doesnt", "it's" and "its" -> "its"), and strips punctuation.
 * 2. Token Extraction: Extracts real timestamps from structured word arrays, line-by-line word timestamps,
 *    or segment timestamps. Missing timestamps return empty tokens instead of synthesizing fake spans.
 * 3. Sequential Monotonic Alignment: Advances monotonically without backwards jumping across scenes.
 * 4. Invariant Enforcement: Validates endMs > startMs, rejects zero or negative spans, and applies
 *    a sanity guard (>= 4 words requires span >= 200ms) to reject collapsed 0:00 - 0:00 matches.
 */
object SceneFuzzyMatcher {

    private const val TAG = "SceneFuzzyMatcher"

    /**
     * Normalizes text for comparison by lowercasing, standardizing apostrophes/contractions,
     * and stripping remaining non-alphanumeric punctuation.
     */
    fun normalize(text: String): String {
        return text.lowercase()
            // Standardize typographic apostrophes
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('´', '\'')
            .replace('`', '\'')
            // Remove apostrophes directly so contractions align identically on both sides:
            // "doesn't" -> "doesnt", "it's" -> "its", "don't" -> "dont", "can't" -> "cant"
            .replace("'", "")
            // Replace remaining non-alphanumerics with spaces
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Splits normalized text into individual word tokens.
     */
    fun tokenize(text: String): List<String> {
        val norm = normalize(text)
        return if (norm.isEmpty()) emptyList() else norm.split(" ").filter { it.isNotBlank() }
    }

    /**
     * Calculates the Levenshtein edit distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val d = IntArray(s2.length + 1) { it }
        for (i in s1.indices) {
            var prevDiag = d[0]
            d[0] = i + 1
            for (j in s2.indices) {
                val temp = d[j + 1]
                val cost = if (s1[i] == s2[j]) 0 else 1
                d[j + 1] = min(min(d[j + 1] + 1, d[j] + 1), prevDiag + cost)
                prevDiag = temp
            }
        }
        return d[s2.length]
    }

    /**
     * Checks if two individual words match either exactly or within a small fuzzy edit threshold.
     */
    fun wordsFuzzyMatch(w1: String, w2: String): Boolean {
        if (w1 == w2) return true
        val lenDiff = abs(w1.length - w2.length)
        if (lenDiff > 2) return false
        val minLen = min(w1.length, w2.length)
        return when {
            minLen <= 3 -> false // Short words (e.g. to, in, at) require exact matches
            minLen <= 6 -> levenshteinDistance(w1, w2) <= 1
            else -> levenshteinDistance(w1, w2) <= 2
        }
    }

    /**
     * Extracts all timestamped tokens from a [TranscriptionResponse].
     * Prefers direct word-level timestamps (from Grok or Whisper words array), then segment timestamps.
     * If neither exists, returns empty list so missing timestamps produce unmatched scenes rather
     * than fabricated collapsed boundaries.
     */
    fun extractTokens(response: TranscriptionResponse): List<TimestampedToken> {
        val tokens = mutableListOf<TimestampedToken>()

        // 1. Direct word-level timestamps (from Grok speech-to-text, Whisper word timestamps, etc.)
        val directWords = response.words
        if (!directWords.isNullOrEmpty()) {
            for ((wIdx, w) in directWords.withIndex()) {
                val norm = normalize(w.word)
                if (norm.isEmpty()) continue
                val startMs = w.start?.let { TranscriptionResponseParser.secondsToMillis(it) } ?: 0L
                val endMs = w.end?.let { TranscriptionResponseParser.secondsToMillis(it) } ?: (startMs + 100L)
                tokens.add(TimestampedToken(norm, w.word, startMs, max(startMs + 20L, endMs), 0))
            }
            if (tokens.isNotEmpty()) return tokens
        }

        // 2. Segment-level timestamps (with nested words or distributed)
        val segments = response.segments
        if (!segments.isNullOrEmpty()) {
            for ((segIdx, seg) in segments.withIndex()) {
                val segStartMs = TranscriptionResponseParser.secondsToMillis(seg.start)
                val segEndMs = max(segStartMs + 50L, TranscriptionResponseParser.secondsToMillis(seg.end))

                val words = seg.words
                if (!words.isNullOrEmpty()) {
                    for (w in words) {
                        val norm = normalize(w.word)
                        if (norm.isEmpty()) continue
                        val wStartMs = w.start?.let { TranscriptionResponseParser.secondsToMillis(it) } ?: segStartMs
                        val wEndMs = w.end?.let { TranscriptionResponseParser.secondsToMillis(it) } ?: segEndMs
                        tokens.add(TimestampedToken(norm, w.word, wStartMs, max(wStartMs + 20L, wEndMs), segIdx))
                    }
                } else {
                    val rawWords = seg.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val wordCount = rawWords.size
                    if (wordCount > 0) {
                        val totalDuration = max(1L, segEndMs - segStartMs)
                        for ((wIdx, rawWord) in rawWords.withIndex()) {
                            val norm = normalize(rawWord)
                            if (norm.isEmpty()) continue
                            val wStart = segStartMs + (wIdx * totalDuration) / wordCount
                            val wEnd = segStartMs + ((wIdx + 1) * totalDuration) / wordCount
                            tokens.add(TimestampedToken(norm, rawWord, wStart, wEnd, segIdx))
                        }
                    }
                }
            }
            if (tokens.isNotEmpty()) return tokens
        }

        // 3. If no word or segment timestamps are present, we return empty tokens.
        // Fabricating timestamps over a dummy 1000ms duration is explicitly avoided.
        return emptyList()
    }

    /**
     * Matches parsed scenes against the timestamped transcript.
     * Produces a [SceneBoundary] for each scene.
     */
    fun matchScenes(
        scenes: List<ParsedScene>,
        response: TranscriptionResponse,
        audioDurationMs: Long = 0L
    ): List<SceneBoundary> {
        val transcriptTokens = extractTokens(response)
        val boundaries = mutableListOf<SceneBoundary>()

        if (transcriptTokens.isEmpty()) {
            return scenes.map {
                SceneBoundary(
                    sceneIndex = it.sceneIndex,
                    sceneTitle = it.sceneTitle,
                    startMs = 0L,
                    endMs = 0L,
                    isMatched = false,
                    scriptText = it.scriptText,
                    confidence = 0f
                )
            }
        }

        var searchTokenIndex = 0
        var lastMatchedEndMs = 0L
        val totalTranscriptTokens = transcriptTokens.size

        for (scene in scenes) {
            val sceneTokens = tokenize(scene.scriptText)
            if (sceneTokens.isEmpty() || searchTokenIndex >= totalTranscriptTokens) {
                boundaries.add(
                    SceneBoundary(
                        sceneIndex = scene.sceneIndex,
                        sceneTitle = scene.sceneTitle,
                        startMs = 0L,
                        endMs = 0L,
                        isMatched = false,
                        scriptText = scene.scriptText,
                        confidence = 0f
                    )
                )
                continue
            }

            // Strictly sequential & monotonic alignment: search begins at or after scene k-1's end
            val matchResult = findBestWindowMatch(
                sceneTokens = sceneTokens,
                transcriptTokens = transcriptTokens,
                fromIndex = searchTokenIndex
            )

            if (matchResult != null && matchResult.score >= 0.35f) {
                var startMs = transcriptTokens[matchResult.startIndex].startMs
                var endMs = transcriptTokens[matchResult.endIndex].endMs

                // Clamp to audio bounds if provided
                if (audioDurationMs > 0L) {
                    startMs = startMs.coerceIn(0L, audioDurationMs)
                    endMs = endMs.coerceIn(0L, audioDurationMs)
                }

                // Monotonic progression: cannot start before previous matched scene ended
                if (startMs < lastMatchedEndMs) {
                    startMs = lastMatchedEndMs
                }
                if (endMs < startMs) {
                    endMs = startMs
                }

                val duration = endMs - startMs

                // Sanity Guard:
                // If scene contains more than 3 words but computed span is under 200 ms,
                // treat it as suspicious (collapsed timestamps) and mark unmatched.
                val isSuspicious = (sceneTokens.size > 3 && duration < 200L) || (duration <= 0L)

                if (isSuspicious) {
                    Log.w(
                        TAG,
                        "Scene ${scene.sceneIndex} '${scene.sceneTitle}' match rejected by sanity guard: " +
                                "words=${sceneTokens.size}, span=${duration}ms (<200ms). Marking unmatched."
                    )
                    boundaries.add(
                        SceneBoundary(
                            sceneIndex = scene.sceneIndex,
                            sceneTitle = scene.sceneTitle,
                            startMs = 0L,
                            endMs = 0L,
                            isMatched = false,
                            scriptText = scene.scriptText,
                            confidence = matchResult.score
                        )
                    )
                } else {
                    boundaries.add(
                        SceneBoundary(
                            sceneIndex = scene.sceneIndex,
                            sceneTitle = scene.sceneTitle,
                            startMs = startMs,
                            endMs = endMs,
                            isMatched = true,
                            scriptText = scene.scriptText,
                            confidence = matchResult.score
                        )
                    )
                    lastMatchedEndMs = endMs
                    searchTokenIndex = matchResult.endIndex + 1
                }
            } else {
                // Scene omitted or no match found
                boundaries.add(
                    SceneBoundary(
                        sceneIndex = scene.sceneIndex,
                        sceneTitle = scene.sceneTitle,
                        startMs = 0L,
                        endMs = 0L,
                        isMatched = false,
                        scriptText = scene.scriptText,
                        confidence = matchResult?.score ?: 0f
                    )
                )
            }
        }

        return boundaries
    }

    private data class WindowMatch(
        val startIndex: Int,
        val endIndex: Int,
        val score: Float
    )

    private fun findBestWindowMatch(
        sceneTokens: List<String>,
        transcriptTokens: List<TimestampedToken>,
        fromIndex: Int
    ): WindowMatch? {
        val sceneLen = sceneTokens.size
        val transcriptLen = transcriptTokens.size
        if (fromIndex >= transcriptLen) return null

        var bestMatch: WindowMatch? = null
        var highestScore = 0f

        val minWindowSize = max(1, (sceneLen * 0.6).toInt())
        val maxWindowSize = min(transcriptLen - fromIndex, (sceneLen * 1.5).toInt() + 3)

        // Iterate possible start positions in the remaining transcript
        for (start in fromIndex until transcriptLen) {
            val firstSceneWord = sceneTokens.first()
            val secondSceneWord = sceneTokens.getOrNull(1)
            val thirdSceneWord = sceneTokens.getOrNull(2)

            val currentNorm = transcriptTokens[start].normalized
            val matchesAnchor = wordsFuzzyMatch(firstSceneWord, currentNorm) ||
                    (secondSceneWord != null && wordsFuzzyMatch(secondSceneWord, currentNorm)) ||
                    (thirdSceneWord != null && wordsFuzzyMatch(thirdSceneWord, currentNorm))

            val testAnchor = if (matchesAnchor) true else (sceneLen <= 3)

            if (testAnchor) {
                val endLimit = min(transcriptLen - 1, start + maxWindowSize - 1)
                val startLimit = min(endLimit, start + minWindowSize - 1)

                for (end in startLimit..endLimit) {
                    val windowTokens = transcriptTokens.subList(start, end + 1).map { it.normalized }
                    val score = computeAlignmentScore(sceneTokens, windowTokens)

                    val windowLen = end - start + 1
                    val isBetter = when {
                        score > highestScore -> true
                        score == highestScore && bestMatch != null -> {
                            val currDiff = abs(windowLen - sceneLen)
                            val bestDiff = abs((bestMatch.endIndex - bestMatch.startIndex + 1) - sceneLen)
                            currDiff < bestDiff
                        }
                        else -> false
                    }

                    if (isBetter) {
                        highestScore = score
                        bestMatch = WindowMatch(start, end, score)
                        // If exact 100% match with ideal length, we can return immediately
                        if (score >= 0.999f && windowLen == sceneLen) {
                            return bestMatch
                        }
                    }
                }
            }
        }

        // Secondary fallback search if anchor filtering missed a modified opening
        if (bestMatch == null || highestScore < 0.35f) {
            val step = max(1, sceneLen / 3)
            var start = fromIndex
            while (start < transcriptLen) {
                val endLimit = min(transcriptLen - 1, start + maxWindowSize - 1)
                val startLimit = min(endLimit, start + minWindowSize - 1)
                for (end in startLimit..endLimit) {
                    val windowTokens = transcriptTokens.subList(start, end + 1).map { it.normalized }
                    val score = computeAlignmentScore(sceneTokens, windowTokens)
                    val windowLen = end - start + 1
                    val isBetter = when {
                        score > highestScore -> true
                        score == highestScore && bestMatch != null -> {
                            val currDiff = abs(windowLen - sceneLen)
                            val bestDiff = abs((bestMatch.endIndex - bestMatch.startIndex + 1) - sceneLen)
                            currDiff < bestDiff
                        }
                        else -> false
                    }
                    if (isBetter) {
                        highestScore = score
                        bestMatch = WindowMatch(start, end, score)
                        if (score >= 0.999f && windowLen == sceneLen) {
                            return bestMatch
                        }
                    }
                }
                start += step
            }
        }

        return bestMatch
    }

    /**
     * Computes similarity using Longest Common Subsequence (LCS) ratio with fuzzy token matching.
     */
    fun computeAlignmentScore(sceneWords: List<String>, windowWords: List<String>): Float {
        if (sceneWords.isEmpty() || windowWords.isEmpty()) return 0f

        val n = sceneWords.size
        val m = windowWords.size

        val dp = Array(n + 1) { FloatArray(m + 1) }

        for (i in 1..n) {
            val sWord = sceneWords[i - 1]
            for (j in 1..m) {
                val wWord = windowWords[j - 1]
                if (sWord == wWord) {
                    dp[i][j] = dp[i - 1][j - 1] + 1.0f
                } else if (wordsFuzzyMatch(sWord, wWord)) {
                    dp[i][j] = dp[i - 1][j - 1] + 0.85f
                } else {
                    dp[i][j] = max(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val lcs = dp[n][m]
        val recall = lcs / n.toFloat()
        val precision = lcs / m.toFloat()
        return if (recall + precision > 0) (2 * recall * precision) / (recall + precision) else 0f
    }
}

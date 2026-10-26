package com.tharunbirla.librecuts.autoassemble

import com.tharunbirla.librecuts.models.SceneBoundary
import com.tharunbirla.librecuts.transcription.models.TranscriptionResponse
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Timestamped word token extracted from a Whisper transcription.
 */
data class TimestampedToken(
    val normalized: String,
    val original: String,
    val startMs: Long,
    val endMs: Long,
    val segmentIndex: Int
)

/**
 * Fuzzy-matching alignment algorithm that maps script scenes to Whisper transcription timestamps.
 *
 * Architecture:
 * 1. Text Normalization: Converts text to lowercase and strips all punctuation.
 * 2. Token Extraction: Parses segments/words into timestamped tokens.
 * 3. Sequence Alignment: Employs a forward-moving sliding window with fuzzy Levenshtein token distance
 *    and Longest Common Subsequence (LCS) scoring.
 * 4. Graceful Degradation: Missing scenes are flagged as unmatched without failing or distorting
 *    surrounding scenes.
 */
object SceneFuzzyMatcher {

    /**
     * Normalizes text for comparison by lowercasing and stripping non-alphanumeric characters.
     */
    fun normalize(text: String): String {
        return text.lowercase()
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
     * Uses word-level timestamps when available, or distributes segment timestamps across words.
     */
    fun extractTokens(response: TranscriptionResponse): List<TimestampedToken> {
        val tokens = mutableListOf<TimestampedToken>()
        val segments = response.segments

        if (!segments.isNullOrEmpty()) {
            for ((segIdx, seg) in segments.withIndex()) {
                val segStartMs = (seg.start * 1000.0).toLong().coerceAtLeast(0L)
                val segEndMs = (seg.end * 1000.0).toLong().coerceAtLeast(segStartMs)

                val words = seg.words
                if (!words.isNullOrEmpty()) {
                    for (w in words) {
                        val norm = normalize(w.word)
                        if (norm.isEmpty()) continue
                        val wStartMs = ((w.start ?: seg.start) * 1000.0).toLong().coerceAtLeast(0L)
                        val wEndMs = ((w.end ?: seg.end) * 1000.0).toLong().coerceAtLeast(wStartMs)
                        tokens.add(TimestampedToken(norm, w.word, wStartMs, wEndMs, segIdx))
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
        } else if (!response.text.isNullOrBlank()) {
            val totalDurationMs = ((response.duration ?: 0.0) * 1000.0).toLong().coerceAtLeast(1000L)
            val rawWords = response.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val wordCount = rawWords.size
            if (wordCount > 0) {
                for ((wIdx, rawWord) in rawWords.withIndex()) {
                    val norm = normalize(rawWord)
                    if (norm.isEmpty()) continue
                    val wStart = (wIdx * totalDurationMs) / wordCount
                    val wEnd = ((wIdx + 1) * totalDurationMs) / wordCount
                    tokens.add(TimestampedToken(norm, rawWord, wStart, wEnd, 0))
                }
            }
        }
        return tokens
    }

    /**
     * Matches parsed scenes against the timestamped transcript.
     * Produces a [SceneBoundary] for each scene.
     */
    fun matchScenes(
        scenes: List<ParsedScene>,
        response: TranscriptionResponse
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
        val totalTranscriptTokens = transcriptTokens.size

        for (scene in scenes) {
            val sceneTokens = tokenize(scene.scriptText)
            if (sceneTokens.isEmpty()) {
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

            // Monotonic progression with a slight 3-token lookback tolerance for transitions
            val startSearchFrom = max(0, searchTokenIndex - 3)

            val matchResult = findBestWindowMatch(
                sceneTokens = sceneTokens,
                transcriptTokens = transcriptTokens,
                fromIndex = startSearchFrom
            )

            if (matchResult != null && matchResult.score >= 0.35f) {
                val startMs = transcriptTokens[matchResult.startIndex].startMs
                val endMs = transcriptTokens[matchResult.endIndex].endMs
                boundaries.add(
                    SceneBoundary(
                        sceneIndex = scene.sceneIndex,
                        sceneTitle = scene.sceneTitle,
                        startMs = startMs,
                        endMs = max(startMs + 100L, endMs),
                        isMatched = true,
                        scriptText = scene.scriptText,
                        confidence = matchResult.score
                    )
                )
                searchTokenIndex = matchResult.endIndex + 1
            } else {
                // Scene was omitted or completely missing from the voiceover
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
                // searchTokenIndex is intentionally preserved so subsequent scenes can match
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
            var matchesAnchor = false
            for (offset in 0..min(2, transcriptLen - start - 1)) {
                if (wordsFuzzyMatch(firstSceneWord, transcriptTokens[start + offset].normalized)) {
                    matchesAnchor = true
                    break
                }
            }

            if (!matchesAnchor && sceneLen > 1) {
                val secondSceneWord = sceneTokens[1]
                for (offset in 0..min(3, transcriptLen - start - 1)) {
                    if (wordsFuzzyMatch(secondSceneWord, transcriptTokens[start + offset].normalized)) {
                        matchesAnchor = true
                        break
                    }
                }
            }

            val testAnchor = if (matchesAnchor) true else (sceneLen <= 3)

            if (testAnchor) {
                val endLimit = min(transcriptLen - 1, start + maxWindowSize)
                val startLimit = min(endLimit, start + minWindowSize - 1)

                for (end in startLimit..endLimit) {
                    val windowTokens = transcriptTokens.subList(start, end + 1).map { it.normalized }
                    val score = computeAlignmentScore(sceneTokens, windowTokens)

                    if (score > highestScore) {
                        highestScore = score
                        bestMatch = WindowMatch(start, end, score)
                        if (score >= 0.95f) {
                            return bestMatch
                        }
                    }
                }
            }
        }

        // Secondary fallback search if anchor filtering missed a heavily modified opening
        if (bestMatch == null || highestScore < 0.35f) {
            val step = max(1, sceneLen / 3)
            var start = fromIndex
            while (start < transcriptLen) {
                val end = min(transcriptLen - 1, start + sceneLen)
                val windowTokens = transcriptTokens.subList(start, end + 1).map { it.normalized }
                val score = computeAlignmentScore(sceneTokens, windowTokens)
                if (score > highestScore) {
                    highestScore = score
                    bestMatch = WindowMatch(start, end, score)
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

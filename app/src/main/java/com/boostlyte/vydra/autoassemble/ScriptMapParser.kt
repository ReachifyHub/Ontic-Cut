package com.boostlyte.vydra.autoassemble

import java.io.Serializable

data class ParsedScene(
    val sceneIndex: Int,
    val sceneTitle: String,
    val scriptText: String
) : Serializable

/**
 * Parses user-provided script map text into structured [ParsedScene] items.
 *
 * Supported formats:
 * - "Scene 1: Welcome to the channel.\nScene 2: Today we will discuss history."
 * - "Scene 1 - Introduction\nSpoken line one\nSpoken line two\n\nScene 2 - Second part\n..."
 * - Multi-paragraph or numbered lines.
 */
object ScriptMapParser {

    private val SCENE_HEADER_REGEX = Regex(
        """^(?:\[?Scene\s*(\d+)\]?|[#*]+\s*Scene\s*(\d+))[:\-\s]*(.*)$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(rawScript: String): List<ParsedScene> {
        val trimmed = rawScript.trim()
        if (trimmed.isEmpty()) return emptyList()

        val lines = trimmed.lines()
        val scenes = mutableListOf<ParsedScene>()
        var currentSceneIndex: Int? = null
        var currentSceneTitle = ""
        var currentHeaderTrailingText = ""
        val currentBodyLines = mutableListOf<String>()

        fun finalizeCurrentScene() {
            val idx = currentSceneIndex ?: return
            val body = currentBodyLines.joinToString(" ").trim()
            val finalTitle = if (currentSceneTitle.isNotBlank()) {
                currentSceneTitle
            } else {
                "Scene $idx"
            }
            // If body text exists from subsequent lines, use it; otherwise fallback to trailing header text
            val finalText = if (body.isNotEmpty()) {
                body
            } else {
                if (currentHeaderTrailingText.isNotEmpty()) currentHeaderTrailingText else finalTitle
            }
            scenes.add(ParsedScene(idx, finalTitle, finalText))
            currentBodyLines.clear()
            currentHeaderTrailingText = ""
        }

        for (line in lines) {
            val lineTrimmed = line.trim()
            if (lineTrimmed.isEmpty()) continue

            val match = SCENE_HEADER_REGEX.find(lineTrimmed)
            if (match != null) {
                finalizeCurrentScene()
                val idx1 = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                val idx2 = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                val sceneNum = idx1 ?: idx2 ?: (scenes.size + 1)
                val trailingText = match.groupValues.getOrNull(3)?.trim() ?: ""

                currentSceneIndex = sceneNum
                currentSceneTitle = lineTrimmed
                currentHeaderTrailingText = trailingText
            } else {
                if (currentSceneIndex == null) {
                    currentSceneIndex = scenes.size + 1
                    currentSceneTitle = "Scene $currentSceneIndex"
                }
                currentBodyLines.add(lineTrimmed)
            }
        }
        finalizeCurrentScene()

        // Fallback: If no headers were recognized, treat double-spaced paragraphs as individual scenes
        if (scenes.isEmpty() && trimmed.isNotEmpty()) {
            val paragraphs = trimmed.split(Regex("\n\\s*\n"))
            paragraphs.forEachIndexed { index, paragraph ->
                val pTrimmed = paragraph.trim()
                if (pTrimmed.isNotEmpty()) {
                    scenes.add(
                        ParsedScene(
                            sceneIndex = index + 1,
                            sceneTitle = "Scene ${index + 1}",
                            scriptText = pTrimmed
                        )
                    )
                }
            }
        }

        return scenes
    }
}

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

    private val SCRIPT_LABEL_PREFIX = Regex(
        """^(?:script|narration|voiceover|audio|dialogue|spoken|text)\s*[:\-]\s*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Cleans script text lines by stripping leading labels (e.g. "Script:", "Narration:")
     * and outer enclosing quotation marks.
     */
    fun cleanScriptText(raw: String): String {
        var text = raw.trim()
        text = SCRIPT_LABEL_PREFIX.replace(text, "").trim()
        if ((text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) ||
            (text.startsWith("“") && text.endsWith("”") && text.length >= 2) ||
            (text.startsWith("‘") && text.endsWith("’") && text.length >= 2)
        ) {
            text = text.substring(1, text.length - 1).trim()
        }
        text = SCRIPT_LABEL_PREFIX.replace(text, "").trim()
        return text
    }

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
            val cleanedBodyLines = currentBodyLines.map { cleanScriptText(it) }.filter { it.isNotBlank() }
            val body = cleanedBodyLines.joinToString(" ").trim()
            val cleanedHeaderTrailing = cleanScriptText(currentHeaderTrailingText)

            val (finalTitle, finalText) = when {
                body.isNotEmpty() -> {
                    val title = if (currentSceneTitle.isNotBlank()) currentSceneTitle else "Scene $idx"
                    Pair(title, body)
                }
                cleanedHeaderTrailing.isNotEmpty() -> {
                    // Header contained trailing text without subsequent body lines
                    // e.g. "Scene 1: Welcome to the channel."
                    val title = if (currentSceneTitle.isNotBlank()) currentSceneTitle else "Scene $idx"
                    Pair(title, cleanedHeaderTrailing)
                }
                else -> {
                    val title = if (currentSceneTitle.isNotBlank()) currentSceneTitle else "Scene $idx"
                    Pair(title, title)
                }
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
                val pCleaned = cleanScriptText(paragraph)
                if (pCleaned.isNotEmpty()) {
                    scenes.add(
                        ParsedScene(
                            sceneIndex = index + 1,
                            sceneTitle = "Scene ${index + 1}",
                            scriptText = pCleaned
                        )
                    )
                }
            }
        }

        return scenes
    }
}

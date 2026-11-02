package com.boostlyte.vydra.utils

import java.util.Locale

/**
 * Utility for safely constructing and escaping FFmpeg filter expressions.
 *
 * FFmpeg's graph parser (`libavfilter/graphparser.c`) splits filter chains on commas (',')
 * unless they are escaped with a backslash ('\,') or properly protected inside quotes.
 * If dynamic expressions (such as `enable=between(t,188.16,191.02)`) contain unescaped commas,
 * FFmpeg interprets numeric tokens (like `188.16`) as filter names, triggering:
 *   "[AVFilterGraph] No such filter: '188.16'"
 *
 * This utility ensures all dynamic expressions (enable, text, alpha, position, etc.)
 * are escaped and quoted so they survive both direct `-filter_complex` command-line parsing
 * and `-filter_complex_script` file parsing.
 */
object FFmpegFilterUtils {

    /**
     * Builds a safe `:enable='...'` filter option for FFmpeg filters.
     * Escapes commas with `\,` and wraps in single quotes to protect against
     * filterchain splitting.
     */
    fun buildEnableFilter(startTimeMs: Long?, endTimeMs: Long?): String {
        if (startTimeMs == null && endTimeMs == null) return ""
        val startSec = String.format(Locale.US, "%.3f", maxOf(0L, startTimeMs ?: 0L) / 1000.0)
        return if (endTimeMs != null) {
            val endSec = String.format(Locale.US, "%.3f", maxOf(0L, endTimeMs) / 1000.0)
            ":enable='between(t\\,$startSec\\,$endSec)'"
        } else {
            ":enable='gte(t\\,$startSec)'"
        }
    }

    /**
     * Escapes arbitrary user text for FFmpeg's `drawtext` filter with `expansion=none`.
     */
    fun escapeDrawtextText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace(":", "\\:")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("[", "\\[")
            .replace("]", "\\]")
    }

    /**
     * Escapes a file path (such as a font path) for FFmpeg filter arguments.
     */
    fun escapeFontPath(path: String): String {
        return path
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace(":", "\\:")
    }

    /**
     * Ensures an expression containing commas is safely escaped and quoted for filter arguments.
     */
    fun escapeFilterArgument(arg: String): String {
        // If it already has escaped commas or no commas, ensure single quoting
        val escaped = arg.replace("'", "\\'")
        return "'$escaped'"
    }
}

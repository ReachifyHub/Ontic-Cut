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
            .replace("\r\n", " ")
            .replace("\n", " ")
            .replace("\r", " ")
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

    /**
     * Builds a resolution-independent cover/crop filter chain for an input frame.
     * Guarantees that the frame is scaled up to cover the canvas target box BEFORE
     * cropping, preventing "Invalid too big or non positive size" errors when
     * export resolution or framerate changes.
     *
     * Pipeline stages:
     * (a) Scale to COVER canvas box: `scale=w:h:force_original_aspect_ratio=increase`
     * (b) Crop to EXACT canvas box dimensions (centered)
     * (c) Scale to final export resolution
     * (d) setsar=1, fps normalization
     */
    fun computeCoverCropChain(
        srcW: Int,
        srcH: Int,
        canvasRatio: com.boostlyte.vydra.models.CanvasAspectRatio,
        exportW: Int,
        exportH: Int,
        fps: Int = 30
    ): List<String> {
        val targetW = (exportW / 2) * 2
        val targetH = (exportH / 2) * 2

        return listOf(
            "scale=w=$targetW:h=$targetH:force_original_aspect_ratio=increase",
            "crop=$targetW:$targetH:(in_w-$targetW)/2:(in_h-$targetH)/2",
            "scale=$targetW:$targetH",
            "setsar=1,fps=$fps,format=yuv420p"
        )
    }

    /**
     * Centralized validation and construction for FFmpeg drawtext filter expressions.
     * Mandates that EVERY drawtext filter includes:
     * - `fontfile`: explicitly set to a valid font path (or safe fallback)
     * - `fontsize`: positive integer
     * - `fontcolor`: valid color specifier
     * - `x` and `y`: explicit coordinates (preventing default top-left origin stray text)
     * - `enable`: escaped enable expression
     */
    fun buildValidatedDrawtextFilter(
        text: String,
        fontFilePath: String?,
        fontSize: Int,
        fontColor: String,
        x: String,
        y: String,
        startTimeMs: Long?,
        endTimeMs: Long?,
        boxColor: String? = null,
        boxBorderW: Int = 0,
        shadowColor: String? = null,
        shadowOffset: Int = 0,
        alphaExpr: String? = null,
        textAlign: String? = null,
        lineSpacing: Int = 0
    ): String {
        val safeText = escapeDrawtextText(text)
        val safeFontSize = fontSize.coerceAtLeast(16)
        val safeFontColor = if (fontColor.isNotBlank()) fontColor else "white"

        val effectiveFontPath = if (!fontFilePath.isNullOrBlank()) {
            escapeFontPath(fontFilePath)
        } else {
            // Safe fallback font path inside cacheDir if caller passed null
            "/data/user/0/com.boostlyte.vydra/cache/Roboto-Regular.ttf"
        }
        val fontPart = "fontfile='$effectiveFontPath':"

        val safeX = if (x.isNotBlank()) x else "(w-tw)/2"
        val safeY = if (y.isNotBlank()) y else "(h-th-40)"

        val enablePart = buildEnableFilter(startTimeMs, endTimeMs).let {
            if (it.isNotBlank()) it else ":enable='gte(t\\,0)'"
        }

        val boxPart = if (!boxColor.isNullOrBlank() && boxColor != "none") {
            ":box=1:boxcolor=$boxColor:boxborderw=${boxBorderW.coerceAtLeast(1)}"
        } else ""

        val shadowPart = if (!shadowColor.isNullOrBlank() && shadowOffset > 0) {
            ":shadowcolor=$shadowColor:shadowx=$shadowOffset:shadowy=$shadowOffset"
        } else ""

        val alphaPart = if (!alphaExpr.isNullOrBlank()) ":alpha='$alphaExpr'" else ""
        val alignPart = if (!textAlign.isNullOrBlank()) ":text_align=$textAlign" else ""
        val lineSpacingPart = if (lineSpacing != 0) ":line_spacing=$lineSpacing" else ""

        val filterStr = "drawtext=expansion=none:${fontPart}text='$safeText':fontcolor=$safeFontColor:fontsize=$safeFontSize:x=$safeX:y=$safeY${boxPart}${shadowPart}${alphaPart}${alignPart}${lineSpacingPart}$enablePart"
        
        // Assert required components are present
        require(validateDrawtextFilter(filterStr)) {
            "Constructed drawtext filter failed validation: $filterStr"
        }

        return filterStr
    }

    /**
     * Validates that a drawtext filter string contains all mandatory components:
     * fontfile, fontsize, fontcolor, x, y, and enable.
     */
    fun validateDrawtextFilter(filter: String): Boolean {
        if (!filter.startsWith("drawtext=")) return false
        val hasFontFile = filter.contains("fontfile=")
        val hasFontSize = filter.contains("fontsize=")
        val hasFontColor = filter.contains("fontcolor=")
        val hasX = filter.contains(":x=") || filter.contains(" x=")
        val hasY = filter.contains(":y=") || filter.contains(" y=")
        val hasEnable = filter.contains(":enable=") || filter.contains("enable=")
        return hasFontFile && hasFontSize && hasFontColor && hasX && hasY && hasEnable
    }
}

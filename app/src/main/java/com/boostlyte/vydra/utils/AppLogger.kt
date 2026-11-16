package com.boostlyte.vydra.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central diagnostic and crash logging utility for Vydra.
 * Manages persistent crash and export breadcrumb logs in cacheDir/logs/
 * with automatic size capping and sensitive data redaction.
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val MAX_LOG_SIZE_BYTES = 1024 * 1024L // 1 MB max per log file
    private const val CRASH_LOG_NAME = "vydra_crash.log"
    private const val EXPORT_LOG_NAME = "vydra_export.log"
    private const val FILTER_GRAPH_LOG_NAME = "vydra_filter_graph.log"

    private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    private fun getLogsDir(context: Context): File {
        val dir = File(context.cacheDir, "logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCrashLogFile(context: Context): File = File(getLogsDir(context), CRASH_LOG_NAME)
    fun getExportLogFile(context: Context): File = File(getLogsDir(context), EXPORT_LOG_NAME)
    fun getFilterGraphLogFile(context: Context): File = File(getLogsDir(context), FILTER_GRAPH_LOG_NAME)

    fun getIsoTimestamp(): String = synchronized(ISO_DATE_FORMAT) {
        ISO_DATE_FORMAT.format(Date())
    }

    /**
     * Redacts authentication tokens, API keys, and sensitive credentials.
     */
    fun redact(input: String): String {
        var result = input
        // Bearer tokens
        result = result.replace(Regex("""(?i)Bearer\s+[A-Za-z0-9_\-\.]+"""), "Bearer [REDACTED]")
        // Key-value pairs for secrets/tokens/keys
        result = result.replace(
            Regex("""(?i)(apikey|api_key|token|secret|password|supabase_key|auth_token)\s*[:=]\s*["']?[^"'\s\n,]+["']?"""),
            "$1=[REDACTED]"
        )
        // URL query parameters with keys/tokens
        result = result.replace(
            Regex("""(?i)([?&](apikey|key|token|auth)=)[^&\s]+"""),
            "$1[REDACTED]"
        )
        return result
    }

    private fun appendToFileCapped(file: File, line: String) {
        try {
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                // Keep the last ~64KB of lines
                val lines = file.readLines()
                val trimmed = lines.takeLast(lines.size / 2)
                file.writeText(trimmed.joinToString("\n") + "\n")
            }
            FileWriter(file, true).use { writer ->
                writer.appendLine(line)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write log to ${file.name}: ${e.message}")
        }
    }

    fun getDeviceMemorySnapshot(context: Context): String {
        return try {
            val rt = Runtime.getRuntime()
            val nativeAlloc = try { android.os.Debug.getNativeHeapAllocatedSize() } catch (_: Throwable) { -1L }
            val am = try { context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager } catch (_: Throwable) { null }
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val totalSys = if (am != null) mi.totalMem else -1L
            val availSys = if (am != null) mi.availMem else -1L
            val lowSys = am?.let { mi.lowMemory } ?: false
            val isLowRam = try { am?.isLowRamDevice ?: false } catch (_: Throwable) { false }
            "maxMemory=${rt.maxMemory()} totalMemory=${rt.totalMemory()} freeMemory=${rt.freeMemory()} " +
                "nativeHeapAlloc=$nativeAlloc sysTotalMem=$totalSys sysAvailMem=$availSys lowMem=$lowSys isLowRam=$isLowRam"
        } catch (_: Throwable) {
            val rt = Runtime.getRuntime()
            "maxMemory=${rt.maxMemory()} totalMemory=${rt.totalMemory()} freeMemory=${rt.freeMemory()}"
        }
    }

    private fun describeExitReason(reason: Int): String = when (reason) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    /**
     * Checks if the previous export died midway (process kill / OOM / crash)
     * by looking for an unfinished export breadcrumb (EXPORT_STARTED / PASS_COMPLETED / STAGE_TIMING
     * without EXPORT_ENDED / EXPORT_FAILED / EXPORT_CANCELLED), and records process exit reasons
     * on API 30+ to distinguish LMK kills from native/Java crashes.
     */
    fun checkDiedMidway(context: Context) {
        try {
            val file = getExportLogFile(context)
            if (!file.exists()) return
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return
            val lastLine = lines.last()
            val diedMidway = lastLine.startsWith("EXPORT_STARTED") ||
                lastLine.startsWith("EXPORT_CONFIG") ||
                lastLine.startsWith("PASS_COMPLETED") ||
                lastLine.startsWith("STAGE_TIMING")
            if (diedMidway) {
                val memoryInfo = getDeviceMemorySnapshot(context)
                val exitDetails = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                        val latestExit = am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)?.firstOrNull()
                        if (latestExit != null) {
                            val desc = latestExit.description?.replace("\n", " ")?.take(120) ?: "none"
                            " exitReason=${describeExitReason(latestExit.reason)}(${latestExit.reason}) " +
                                "status=${latestExit.status} importance=${latestExit.importance} " +
                                "pssKb=${latestExit.pss} rssKb=${latestExit.rss} exitDesc=$desc"
                        } else ""
                    } catch (_: Throwable) { "" }
                } else ""
                val lastStage = lastLine.take(120).replace("\n", " ")
                val midwayLine = "EXPORT_DIED_MIDWAY ${getIsoTimestamp()} $memoryInfo$exitDetails lastBreadcrumb=[$lastStage]"
                appendToFileCapped(file, midwayLine)
                Log.w(TAG, "Detected unfinished export: $midwayLine")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking died midway export: ${e.message}")
        }
    }

    /**
     * Records export start breadcrumb.
     */
    fun logExportStarted(context: Context, projectStats: String) {
        val line = "EXPORT_STARTED ${getIsoTimestamp()} ${redact(projectStats)}"
        appendToFileCapped(getExportLogFile(context), line)
    }

    /**
     * Records export success breadcrumb.
     */
    fun logExportEnded(context: Context, returnCode: Int, outputPath: String) {
        val line = "EXPORT_ENDED ${getIsoTimestamp()} returnCode=$returnCode outputPath=${redact(outputPath)}"
        appendToFileCapped(getExportLogFile(context), line)
    }

    /**
     * Records pass completion breadcrumb with start/end ISO timestamps, duration, returnCode, and render path.
     */
    fun logPassResult(
        context: Context,
        passName: String,
        startTimeIso: String,
        endTimeIso: String,
        durationSecs: Double,
        returnCode: Int,
        renderPath: String
    ) {
        val line = "PASS_COMPLETED ${getIsoTimestamp()} pass=$passName start=$startTimeIso end=$endTimeIso duration=${String.format(Locale.US, "%.2f", durationSecs)}s returnCode=$returnCode RENDER_PATH=$renderPath"
        appendToFileCapped(getExportLogFile(context), line)
    }

    /**
     * Records pass failure breadcrumb with returnCode, script path, line count, render path,
     * and writes up to 300 lines of FFmpeg log to cacheDir/logs/vydra_export.log.
     */
    fun logPassFailed(
        context: Context,
        passName: String,
        returnCode: Int?,
        scriptPath: String?,
        renderPath: String,
        ffmpegLogs: List<String> = emptyList()
    ) {
        val file = getExportLogFile(context)
        val scriptLineCount = if (!scriptPath.isNullOrBlank()) {
            try {
                val f = File(scriptPath)
                if (f.exists()) f.readLines().size else 0
            } catch (_: Exception) { 0 }
        } else 0

        val line = "PASS_FAILED ${getIsoTimestamp()} pass=$passName returnCode=${returnCode ?: -1} scriptPath=${scriptPath ?: "none"} scriptLineCount=$scriptLineCount RENDER_PATH=$renderPath"
        appendToFileCapped(file, line)

        if (ffmpegLogs.isNotEmpty()) {
            val tailLogs = ffmpegLogs.takeLast(300)
            val logBlock = buildString {
                appendLine("--- BEGIN FFMPEG LOG TAIL (${tailLogs.size} lines) ---")
                tailLogs.forEach { appendLine(redact(it)) }
                appendLine("--- END FFMPEG LOG TAIL ---")
            }
            appendToFileCapped(file, logBlock)
        }
    }

    /**
     * Records export failure breadcrumb.
     */
    fun logExportFailed(context: Context, reason: String, returnCode: Int?, scriptPath: String?) {
        val cleanReason = redact(reason.replace("\n", " ").take(400))
        val line = "EXPORT_FAILED ${getIsoTimestamp()} returnCode=${returnCode ?: -1} scriptPath=${scriptPath ?: "none"} reason=$cleanReason"
        appendToFileCapped(getExportLogFile(context), line)
    }

    /**
     * Records export cancellation breadcrumb.
     */
    fun logExportCancelled(context: Context) {
        val line = "EXPORT_CANCELLED ${getIsoTimestamp()}"
        appendToFileCapped(getExportLogFile(context), line)
    }

    /**
     * Records full project & encoder configuration at the start of export.
     */
    fun logExportConfig(context: Context, configDetails: String) {
        val line = "EXPORT_CONFIG ${getIsoTimestamp()} ${redact(configDetails)}"
        Log.i("ExportProfiler", line)
        appendToFileCapped(getExportLogFile(context), line)
    }

    /**
     * Records fine-grained timing for an individual export stage to Logcat and vydra_export.log.
     */
    fun logStageTiming(
        context: Context,
        stageName: String,
        durationSecs: Double,
        details: String = ""
    ) {
        val formattedDur = String.format(Locale.US, "%.3f", durationSecs)
        val suffix = if (details.isNotBlank()) " ${redact(details)}" else ""
        val line = "STAGE_TIMING ${getIsoTimestamp()} stage=$stageName duration=${formattedDur}s$suffix"
        Log.i("ExportProfiler", line)
        appendToFileCapped(getExportLogFile(context), line)
    }

    /**
     * Records an end-to-end export performance summary covering all pipeline stages.
     */
    fun logExportProfileSummary(
        context: Context,
        mode: String,
        prepSecs: Double,
        chunksSecs: Double,
        audioSecs: Double,
        finalAssemblySecs: Double,
        gallerySaveSecs: Double,
        totalSecs: Double,
        videoDurationSecs: Double,
        outputSizeBytes: Long,
        resolution: Int,
        fps: Int,
        encoder: String,
        numChunks: Int,
        requestedEncoderPref: String = "unknown",
        hwRetryTriggered: Boolean = false,
        usedStreamCopy: Boolean = false,
        reencodedFullVideo: Boolean = false,
        targetBitrate: String = "unknown",
        extraDetails: String = ""
    ) {
        val bitrateKbps = if (videoDurationSecs > 0.0) {
            ((outputSizeBytes * 8.0) / videoDurationSecs / 1000.0).toLong()
        } else 0L
        val sizeMb = String.format(Locale.US, "%.2f", outputSizeBytes / (1024.0 * 1024.0))
        val line = buildString {
            append("EXPORT_PROFILE_SUMMARY ${getIsoTimestamp()} ")
            append("mode=$mode numChunks=$numChunks ")
            append("prep=${String.format(Locale.US, "%.2f", prepSecs)}s ")
            append("chunks=${String.format(Locale.US, "%.2f", chunksSecs)}s ")
            append("audio=${String.format(Locale.US, "%.2f", audioSecs)}s ")
            append("finalAssembly=${String.format(Locale.US, "%.2f", finalAssemblySecs)}s ")
            append("gallerySave=${String.format(Locale.US, "%.2f", gallerySaveSecs)}s ")
            append("total=${String.format(Locale.US, "%.2f", totalSecs)}s ")
            append("videoDuration=${String.format(Locale.US, "%.2f", videoDurationSecs)}s ")
            append("outputSizeBytes=$outputSizeBytes (${sizeMb}MB) ")
            append("avgBitrate=${bitrateKbps}kbps targetBitrate=$targetBitrate ")
            append("res=${resolution}p@$fps encoderPref=$requestedEncoderPref actualEncoder=$encoder ")
            append("hwRetryTriggered=$hwRetryTriggered usedStreamCopy=$usedStreamCopy reencodedFullVideo=$reencodedFullVideo")
            if (extraDetails.isNotBlank()) {
                append(" ").append(redact(extraDetails))
            }
        }
        Log.i("ExportProfiler", line)
        appendToFileCapped(getExportLogFile(context), line)
    }

    /**
     * Records an uncaught crash with full device and memory details.
     */
    fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()

            val rt = Runtime.getRuntime()
            val crashEntry = buildString {
                appendLine("==================================================")
                appendLine("CRASH: ${getIsoTimestamp()}")
                appendLine("Thread: ${thread.name} (id=${thread.id})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
                appendLine("Memory: max=${rt.maxMemory()} total=${rt.totalMemory()} free=${rt.freeMemory()}")
                appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                appendLine("--------------------------------------------------")
                appendLine(redact(stackTrace))
                appendLine("==================================================")
            }
            appendToFileCapped(getCrashLogFile(context), crashEntry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash: ${e.message}", e)
        }
    }

    /**
     * Retrieves the last [maxLines] of combined export and crash logs.
     * Default maxLines is 350 to ensure the 300-line failure tail is readable.
     */
    fun getRecentLogs(context: Context, maxLines: Int = 350): String {
        val lines = mutableListOf<String>()
        val exportFile = getExportLogFile(context)
        if (exportFile.exists()) {
            lines.add("--- EXPORT LOGS (${exportFile.name}) ---")
            lines.addAll(exportFile.readLines().takeLast(maxLines - 50))
        }
        val crashFile = getCrashLogFile(context)
        if (crashFile.exists()) {
            lines.add("\n--- CRASH LOGS (${crashFile.name}) ---")
            lines.addAll(crashFile.readLines().takeLast(50))
        }
        if (lines.isEmpty()) {
            return "No log records found."
        }
        return lines.takeLast(maxLines).joinToString("\n")
    }

    /**
     * Clears/initializes the filter graph & command log at the start of a new export run.
     */
    fun resetFilterGraphLog(context: Context, headerInfo: String = "") {
        try {
            val destFile = getFilterGraphLogFile(context)
            destFile.writeText(
                buildString {
                    appendLine("=== VYDRA EXPORT FILTER GRAPH & COMMANDS (${getIsoTimestamp()}) ===")
                    if (headerInfo.isNotBlank()) {
                        appendLine(redact(headerInfo))
                    }
                    appendLine()
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reset filter graph log: ${e.message}")
        }
    }

    /**
     * Appends an executed FFmpeg command and its filter_complex script into vydra_filter_graph.log
     * (bounded cleanly under ~512 KB so Share App Logs remains reliable).
     */
    fun appendFilterGraphEntry(
        context: Context,
        passName: String,
        command: String?,
        filterScriptContent: String? = null,
        sourceFile: File? = null
    ) {
        try {
            val destFile = getFilterGraphLogFile(context)
            val maxFilterLogBytes = 512 * 1024L
            if (destFile.exists() && destFile.length() >= maxFilterLogBytes) {
                return
            }
            val scriptBody = when {
                !filterScriptContent.isNullOrBlank() -> filterScriptContent
                sourceFile != null && sourceFile.exists() -> sourceFile.readText()
                else -> null
            }
            val entry = buildString {
                appendLine("--- PASS: $passName (${getIsoTimestamp()}) ---")
                if (!command.isNullOrBlank()) {
                    appendLine("CMD: ${redact(command)}")
                }
                if (!scriptBody.isNullOrBlank()) {
                    val lines = scriptBody.lines()
                    appendLine("FILTER_SCRIPT (${lines.size} lines, ${scriptBody.length} chars):")
                    if (scriptBody.length > 64 * 1024) {
                        appendLine(redact(scriptBody.take(64 * 1024)))
                        appendLine("... [TRUNCATED ${scriptBody.length - 64 * 1024} chars] ...")
                    } else {
                        appendLine(redact(scriptBody))
                    }
                }
                appendLine()
            }
            FileWriter(destFile, true).use { writer ->
                writer.append(entry)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append filter graph entry: ${e.message}")
        }
    }

    /**
     * Saves or copies the generated filter_complex_script into cacheDir/logs/
     * so it is permanently preserved in the "Share App Logs" diagnostic bundle.
     */
    fun saveFilterComplexScriptLog(context: Context, scriptContent: String, sourceFile: File? = null) {
        appendFilterGraphEntry(
            context = context,
            passName = sourceFile?.nameWithoutExtension ?: "filter_script",
            command = null,
            filterScriptContent = scriptContent,
            sourceFile = sourceFile
        )
    }

    /**
     * Returns list of existing log files for sharing.
     */
    fun getLogFiles(context: Context): List<File> {
        val dir = getLogsDir(context)
        return listOf(
            File(dir, EXPORT_LOG_NAME),
            File(dir, CRASH_LOG_NAME),
            File(dir, FILTER_GRAPH_LOG_NAME)
        ).filter { it.exists() && it.length() > 0L }
    }
}

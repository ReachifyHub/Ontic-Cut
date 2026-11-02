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
    private const val MAX_LOG_SIZE_BYTES = 256 * 1024L // 256 KB max per log file
    private const val CRASH_LOG_NAME = "vydra_crash.log"
    private const val EXPORT_LOG_NAME = "vydra_export.log"

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

    /**
     * Checks if the previous export died midway (process kill / OOM / crash)
     * by looking for an orphan EXPORT_STARTED.
     */
    fun checkDiedMidway(context: Context) {
        try {
            val file = getExportLogFile(context)
            if (!file.exists()) return
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return
            val lastLine = lines.last()
            if (lastLine.startsWith("EXPORT_STARTED")) {
                val rt = Runtime.getRuntime()
                val memoryInfo = "maxMemory=${rt.maxMemory()} totalMemory=${rt.totalMemory()} freeMemory=${rt.freeMemory()}"
                val midwayLine = "EXPORT_DIED_MIDWAY ${getIsoTimestamp()} $memoryInfo"
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
     */
    fun getRecentLogs(context: Context, maxLines: Int = 50): String {
        val lines = mutableListOf<String>()
        val exportFile = getExportLogFile(context)
        if (exportFile.exists()) {
            lines.add("--- EXPORT LOGS (${exportFile.name}) ---")
            lines.addAll(exportFile.readLines().takeLast(maxLines / 2))
        }
        val crashFile = getCrashLogFile(context)
        if (crashFile.exists()) {
            lines.add("\n--- CRASH LOGS (${crashFile.name}) ---")
            lines.addAll(crashFile.readLines().takeLast(maxLines / 2))
        }
        if (lines.isEmpty()) {
            return "No log records found."
        }
        return lines.takeLast(maxLines).joinToString("\n")
    }

    /**
     * Returns list of existing log files for sharing.
     */
    fun getLogFiles(context: Context): List<File> {
        val dir = getLogsDir(context)
        return listOf(File(dir, EXPORT_LOG_NAME), File(dir, CRASH_LOG_NAME)).filter { it.exists() && it.length() > 0L }
    }
}

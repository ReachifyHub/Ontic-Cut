package com.boostlyte.vydra.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.boostlyte.vydra.MainActivity
import com.boostlyte.vydra.R
import com.boostlyte.vydra.utils.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class ExportService : Service() {

    private val TAG = "ExportService"
    private val CHANNEL_ID = "ExportChannel"
    private val NOTIFICATION_ID = 1001

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    companion object {
        const val ACTION_EXPORT_PROGRESS = "com.boostlyte.vydra.ACTION_EXPORT_PROGRESS"
        const val ACTION_EXPORT_SUCCESS = "com.boostlyte.vydra.ACTION_EXPORT_SUCCESS"
        const val ACTION_EXPORT_FAILURE = "com.boostlyte.vydra.ACTION_EXPORT_FAILURE"
        const val ACTION_EXPORT_CANCELLED = "com.boostlyte.vydra.ACTION_EXPORT_CANCELLED"
        const val ACTION_CANCEL_EXPORT = "com.boostlyte.vydra.ACTION_CANCEL_EXPORT"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SAVED_URI = "extra_saved_uri"
        const val EXTRA_ERROR = "extra_error"
        const val EXTRA_ERROR_CODE = "extra_error_code"

        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TEMP_OUTPUT_PATH = "extra_temp_output_path"
        const val EXTRA_CONCAT_FILE_PATH = "extra_concat_file_path"
        const val EXTRA_TOTAL_DURATION_SECS = "extra_total_duration_secs"
        const val EXTRA_IS_AUDIO_ONLY = "extra_is_audio_only"
        const val EXTRA_CHUNK_PLAN_FILE = "extra_chunk_plan_file"
        const val EXTRA_RENDER_PATH = "extra_render_path"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ffmpegEngine = FFmpegRenderEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_CANCEL_EXPORT) {
            Log.d(TAG, "Received ACTION_CANCEL_EXPORT")
            serviceScope.launch {
                try {
                    ffmpegEngine.cancelAllSessions()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cancelling sessions: ${e.message}")
                } finally {
                    broadcastCancelled()
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.cancel(NOTIFICATION_ID)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        val tempOutputPath = intent.getStringExtra(EXTRA_TEMP_OUTPUT_PATH) ?: return START_NOT_STICKY
        val isAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)

        val chunkPlanFilePath = intent.getStringExtra(EXTRA_CHUNK_PLAN_FILE)
        if (chunkPlanFilePath != null) {
            val planFile = File(chunkPlanFilePath)
            if (planFile.exists()) {
                val chunkPlan = try {
                    java.io.ObjectInputStream(java.io.FileInputStream(planFile)).use {
                        it.readObject() as? ChunkExportPlan
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing chunk plan: ${e.message}", e)
                    null
                }

                if (chunkPlan != null && chunkPlan.isChunked) {
                    handleChunkedExport(chunkPlan, tempOutputPath, isAudioOnly, planFile)
                    return START_NOT_STICKY
                }
            }
        }

        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
        val concatFilePath = intent.getStringExtra(EXTRA_CONCAT_FILE_PATH)
        val durationSecs = intent.getDoubleExtra(EXTRA_TOTAL_DURATION_SECS, 0.0)

        val scriptRegex = Regex("""-filter_complex_script\s+["']?([^"'\s]+)["']?""")
        val scriptFilePath = scriptRegex.find(command)?.groupValues?.getOrNull(1)

        val renderPath = intent.getStringExtra(EXTRA_RENDER_PATH) ?: "legacy"
        val projectStats = "durationSecs=$durationSecs isAudioOnly=$isAudioOnly tempOutput=$tempOutputPath script=$scriptFilePath RENDER_PATH=$renderPath"
        com.boostlyte.vydra.utils.AppLogger.logExportStarted(this@ExportService, projectStats)

        startForeground(NOTIFICATION_ID, buildNotification(0, "Exporting..."))

        serviceScope.launch {
            try {
                var lastReportedProgress = -1
                var lastNotificationTime = 0L

                val passStartIso = com.boostlyte.vydra.utils.AppLogger.getIsoTimestamp()
                val passStartTimeMs = System.currentTimeMillis()

                val result = ffmpegEngine.exportFinal(
                    ffmpegCommand = command,
                    totalDurationSecs = durationSecs,
                    onProgress = { progress ->
                        try {
                            val now = System.currentTimeMillis()
                            if (progress != lastReportedProgress && (now - lastNotificationTime >= 250L || progress >= 100)) {
                                lastReportedProgress = progress
                                lastNotificationTime = now
                                updateNotification(progress, "Exporting...")
                                broadcastProgress(progress)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error updating export progress: ${e.message}")
                        }
                    }
                )

                val passEndTimeMs = System.currentTimeMillis()
                val passEndIso = com.boostlyte.vydra.utils.AppLogger.getIsoTimestamp()
                val passDurationSec = (passEndTimeMs - passStartTimeMs) / 1000.0

                val tempFile = File(tempOutputPath)
                val concatFile = concatFilePath?.let { File(it) }

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        com.boostlyte.vydra.utils.AppLogger.logPassResult(
                            context = this@ExportService,
                            passName = "single_pass",
                            startTimeIso = passStartIso,
                            endTimeIso = passEndIso,
                            durationSecs = passDurationSec,
                            returnCode = 0,
                            renderPath = renderPath
                        )
                        updateNotification(100, "Saving video...")
                        broadcastProgress(100)

                        if (!tempFile.exists() || tempFile.length() == 0L) {
                            Log.e(TAG, "Rendered output file is missing or 0 bytes: $tempOutputPath")
                            com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, "Rendered file is missing or empty", -1, scriptFilePath)
                            showCompletionNotification("Export Failed", "Rendered file is missing or empty", null)
                            broadcastFailure("Rendered output file is empty or missing", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        } else {
                            val savedUri = try {
                                saveVideoToGallery(tempFile, isAudioOnly)
                            } catch (e: Exception) {
                                Log.e(TAG, "Exception saving to gallery", e)
                                null
                            }
                            if (savedUri != null) {
                                com.boostlyte.vydra.utils.AppLogger.logExportEnded(this@ExportService, 0, tempOutputPath)
                                showCompletionNotification("Export Complete", "Video saved to gallery", savedUri)
                                broadcastSuccess(savedUri.toString())
                                Log.d(TAG, "Export successful: $savedUri")
                            } else {
                                com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, "Failed to save video to gallery", -1, scriptFilePath)
                                showCompletionNotification("Export Failed", "Failed to save video to gallery", null)
                                broadcastFailure("Failed to save video to gallery", ErrorCode.GALLERY_SAVE_FAILED.code)
                            }
                        }
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        val returnCode = result.session?.returnCode?.value
                        val errorLines = result.error.split("\n")
                        com.boostlyte.vydra.utils.AppLogger.logPassFailed(
                            context = this@ExportService,
                            passName = "single_pass",
                            returnCode = returnCode,
                            scriptPath = scriptFilePath,
                            renderPath = renderPath,
                            ffmpegLogs = errorLines
                        )
                        com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, result.error, returnCode, scriptFilePath)
                        showCompletionNotification("Export Failed", "Error during rendering", null)
                        broadcastFailure(result.error, ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        Log.e(TAG, "Export failed: ${result.error}")
                    }
                    is FFmpegRenderEngine.RenderResult.Cancelled -> {
                        Log.d(TAG, "Export was cancelled by user")
                        com.boostlyte.vydra.utils.AppLogger.logExportCancelled(this@ExportService)
                        val manager = getSystemService(NotificationManager::class.java)
                        manager?.cancel(NOTIFICATION_ID)
                        broadcastCancelled()
                    }
                }

                // Cleanup temp files
                try {
                    if (tempFile.exists()) tempFile.delete()
                    if (concatFile?.exists() == true) concatFile.delete()
                    cacheDir.listFiles { f -> f.name.startsWith("filter_script_") && f.name.endsWith(".txt") }?.forEach { it.delete() }
                } catch (_: Exception) {}

            } catch (e: Exception) {
                Log.e(TAG, "Export exception", e)
                com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, e.message ?: "Unknown error", -1, scriptFilePath)
                showCompletionNotification("Export Failed", e.message ?: "Unknown error", null)
                broadcastFailure(e.message ?: "Unknown error", ErrorCode.UNEXPECTED_CRASH.code)
            } finally {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping service: ${e.message}")
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Export",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Int, title: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (progress > 0) "$progress%" else "Starting...")
            .setSmallIcon(R.drawable.ic_save_24)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Int, title: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(progress, title))
    }

    private fun showCompletionNotification(title: String, message: String, videoUri: Uri?) {
        val safeUri: Uri? = if (videoUri != null) {
            if (videoUri.scheme == "file" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        File(videoUri.path ?: "")
                    )
                } catch (e: Exception) {
                    videoUri
                }
            } else {
                videoUri
            }
        } else null

        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (safeUri != null) {
                setDataAndType(safeUri, if (message.contains("Audio", true)) "audio/*" else "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                setClass(this@ExportService, MainActivity::class.java)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_check_24)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun broadcastProgress(progress: Int) {
        val intent = Intent(ACTION_EXPORT_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSuccess(savedUri: String) {
        val intent = Intent(ACTION_EXPORT_SUCCESS).apply {
            putExtra(EXTRA_SAVED_URI, savedUri)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastCancelled() {
        val intent = Intent(ACTION_EXPORT_CANCELLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastFailure(error: String, errorCode: String = ErrorCode.FFMPEG_EXECUTION_FAILED.code) {
        val intent = Intent(ACTION_EXPORT_FAILURE).apply {
            putExtra(EXTRA_ERROR, error)
            putExtra(EXTRA_ERROR_CODE, errorCode)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun saveVideoToGallery(videoFile: File, isAudioOnly: Boolean): Uri? {
        val mimeType = if (isAudioOnly) "audio/mpeg" else "video/mp4"
        val ext = if (isAudioOnly) ".mp3" else ".mp4"
        val prefix = if (isAudioOnly) "Vydra_Audio_" else "Vydra_"
        val fileName = "${prefix}${System.currentTimeMillis()}$ext"
        
        val sharedPreferences = getSharedPreferences("vydra_prefs", Context.MODE_PRIVATE)
        val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
        val customUriString = sharedPreferences.getString(prefKey, null)
        
        if (customUriString != null) {
            try {
                val treeUri = Uri.parse(customUriString)
                val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                )
                val newFileUri = android.provider.DocumentsContract.createDocument(
                    contentResolver,
                    parentUri,
                    mimeType,
                    fileName
                )
                if (newFileUri != null) {
                    try {
                        contentResolver.openOutputStream(newFileUri)?.use { output ->
                            videoFile.inputStream().use { input -> input.copyTo(output) }
                        } ?: throw java.io.IOException("Unable to open output stream for custom directory")
                        return newFileUri
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed writing to custom directory file, deleting partial file: ${e.message}", e)
                        try {
                            android.provider.DocumentsContract.deleteDocument(contentResolver, newFileUri)
                        } catch (delEx: Exception) {
                            Log.w(TAG, "Failed to delete partial document: ${delEx.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving to custom directory: ${e.message}, falling back to default", e)
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStoreQPlus(videoFile, mimeType, fileName, isAudioOnly)
        } else {
            saveToMediaStoreLegacy(videoFile, mimeType, fileName, isAudioOnly)
        }
    }

    private fun saveToMediaStoreQPlus(videoFile: File, mimeType: String, fileName: String, isAudioOnly: Boolean): Uri? {
        return try {
            val relativeDir = if (isAudioOnly) Environment.DIRECTORY_MUSIC + "/Vydra/" else Environment.DIRECTORY_MOVIES + "/Vydra/"
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collectionUri = if (isAudioOnly) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = contentResolver.insert(collectionUri, contentValues) ?: return null
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    videoFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw java.io.IOException("Failed to open output stream for MediaStore URI")

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, contentValues, null, null)
                uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write data to MediaStore URI, deleting incomplete entry: ${e.message}", e)
                try {
                    contentResolver.delete(uri, null, null)
                } catch (delEx: Exception) {
                    Log.w(TAG, "Failed to delete corrupt MediaStore URI: ${delEx.message}")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting into MediaStore (API 29+): ${e.message}", e)
            null
        }
    }

    private fun saveToMediaStoreLegacy(videoFile: File, mimeType: String, fileName: String, isAudioOnly: Boolean): Uri? {
        return try {
            val targetDirType = if (isAudioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            val publicDir = Environment.getExternalStoragePublicDirectory(targetDirType)
            val vydraDir = File(publicDir, "Vydra")
            if (!vydraDir.exists() && !vydraDir.mkdirs()) {
                Log.w(TAG, "Directory ${vydraDir.absolutePath} could not be created or already exists")
            }
            val destFile = File(vydraDir, fileName)
            try {
                videoFile.copyTo(destFile, overwrite = true)
            } catch (e: Exception) {
                if (destFile.exists()) destFile.delete()
                Log.e(TAG, "Failed copying video to legacy destination: ${e.message}", e)
                return null
            }

            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            val collectionUri = if (isAudioOnly) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val insertedUri = try {
                contentResolver.insert(collectionUri, contentValues)
            } catch (e: Exception) {
                Log.w(TAG, "Could not insert legacy MediaStore record directly: ${e.message}")
                null
            }

            // Always scan the file so system MediaScanner indexes it immediately in Gallery
            val latch = java.util.concurrent.CountDownLatch(1)
            var scannedContentUri: Uri? = null
            android.media.MediaScannerConnection.scanFile(
                applicationContext,
                arrayOf(destFile.absolutePath),
                arrayOf(mimeType)
            ) { path, scannedUri ->
                Log.d(TAG, "Legacy MediaScanner finished for $path -> $scannedUri")
                scannedContentUri = scannedUri
                latch.countDown()
            }
            try {
                latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                // proceed
            }

            scannedContentUri ?: insertedUri ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", destFile)
                } catch (e: Exception) {
                    Uri.fromFile(destFile)
                }
            } else {
                Uri.fromFile(destFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video in legacy mode (API <= 28): ${e.message}", e)
            null
        }
    }

    private fun handleChunkedExport(
        plan: ChunkExportPlan,
        tempOutputPath: String,
        isAudioOnly: Boolean,
        planFile: File?
    ) {
        val totalChunkDurations = plan.chunkDurationsSecs.sum()
        val finalPassDuration = plan.totalDurationSecs
        val totalWeight = totalChunkDurations + finalPassDuration
        val numChunks = plan.chunkCommands.size

        val projectStats = "mode=chunked numChunks=$numChunks totalDurationSecs=${plan.totalDurationSecs} isAudioOnly=$isAudioOnly tempOutput=$tempOutputPath"
        com.boostlyte.vydra.utils.AppLogger.logExportStarted(this@ExportService, projectStats)

        startForeground(NOTIFICATION_ID, buildNotification(0, "Exporting part 1/$numChunks..."))

        serviceScope.launch {
            val tempFile = File(tempOutputPath)
            var accumulatedWeight = 0.0

            try {
                // Execute each chunk pass sequentially
                for ((idx, chunkCmd) in plan.chunkCommands.withIndex()) {
                    val chunkIdx = idx + 1
                    val chunkDur = plan.chunkDurationsSecs.getOrNull(idx) ?: (totalChunkDurations / numChunks)
                    val spanStart = if (totalWeight > 0.0) (accumulatedWeight / totalWeight) * 100.0 else (idx.toDouble() / (numChunks + 1)) * 100.0
                    val spanEnd = if (totalWeight > 0.0) ((accumulatedWeight + chunkDur) / totalWeight) * 100.0 else ((idx + 1).toDouble() / (numChunks + 1)) * 100.0

                    var lastReportedProgress = -1
                    var lastNotificationTime = 0L

                    Log.d(TAG, "Starting Chunk $chunkIdx/$numChunks (duration: ${chunkCmd.durationSecs}s)")
                    updateNotification(spanStart.toInt(), "Exporting part $chunkIdx/$numChunks...")

                    val chunkRenderPath = chunkCmd.renderPath.ifEmpty { plan.renderPath }
                    val passStartIso = com.boostlyte.vydra.utils.AppLogger.getIsoTimestamp()
                    val passStartTimeMs = System.currentTimeMillis()

                    val result = ffmpegEngine.exportFinal(
                        ffmpegCommand = chunkCmd.command,
                        totalDurationSecs = chunkCmd.durationSecs,
                        onProgress = { p ->
                            try {
                                val overallProgress = (spanStart + (p / 100.0) * (spanEnd - spanStart)).toInt().coerceIn(0, 99)
                                val now = System.currentTimeMillis()
                                if (overallProgress != lastReportedProgress && (now - lastNotificationTime >= 250L || p >= 100)) {
                                    lastReportedProgress = overallProgress
                                    lastNotificationTime = now
                                    updateNotification(overallProgress, "Exporting part $chunkIdx/$numChunks...")
                                    broadcastProgress(overallProgress)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error updating chunk progress: ${e.message}")
                            }
                        }
                    )

                    val passEndTimeMs = System.currentTimeMillis()
                    val passEndIso = com.boostlyte.vydra.utils.AppLogger.getIsoTimestamp()
                    val passDurationSec = (passEndTimeMs - passStartTimeMs) / 1000.0

                    // Release FFmpegKit sessions fully before starting next chunk
                    try {
                        com.antonkarpenko.ffmpegkit.FFmpegKitConfig.clearSessions()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error clearing sessions: ${e.message}")
                    }

                    when (result) {
                        is FFmpegRenderEngine.RenderResult.Success -> {
                            com.boostlyte.vydra.utils.AppLogger.logPassResult(
                                context = this@ExportService,
                                passName = "chunk_$chunkIdx",
                                startTimeIso = passStartIso,
                                endTimeIso = passEndIso,
                                durationSecs = passDurationSec,
                                returnCode = 0,
                                renderPath = chunkRenderPath
                            )
                            val chunkOutputFile = File(chunkCmd.outputPath)
                            if (!chunkOutputFile.exists() || chunkOutputFile.length() == 0L) {
                                val errorMsg = "Chunk $chunkIdx/$numChunks output file missing or empty"
                                Log.e(TAG, errorMsg)
                                cleanupChunkTempFiles(plan.chunkTempPaths, planFile)
                                com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, errorMsg, -1, chunkCmd.scriptFilePath)
                                showCompletionNotification("Export Failed", errorMsg, null)
                                broadcastFailure(errorMsg, ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                                return@launch
                            }
                            Log.d(TAG, "Chunk $chunkIdx/$numChunks completed successfully (${chunkOutputFile.length()} bytes)")
                        }
                        is FFmpegRenderEngine.RenderResult.Failure -> {
                            val stageName = "Chunk $chunkIdx/$numChunks"
                            val returnCode = result.session?.returnCode?.value
                            val errorLines = result.error.split("\n")
                            com.boostlyte.vydra.utils.AppLogger.logPassFailed(
                                context = this@ExportService,
                                passName = "chunk_$chunkIdx",
                                returnCode = returnCode,
                                scriptPath = chunkCmd.scriptFilePath,
                                renderPath = chunkRenderPath,
                                ffmpegLogs = errorLines
                            )
                            Log.e(TAG, "$stageName failed: ${result.error}")
                            cleanupChunkTempFiles(plan.chunkTempPaths, planFile)
                            com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, "$stageName failed: ${result.error}", returnCode, chunkCmd.scriptFilePath)
                            showCompletionNotification("Export Failed", "Error during $stageName", null)
                            broadcastFailure("$stageName failed: ${result.error}", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                            return@launch
                        }
                        is FFmpegRenderEngine.RenderResult.Cancelled -> {
                            Log.d(TAG, "Export was cancelled during chunk $chunkIdx/$numChunks")
                            cleanupChunkTempFiles(plan.chunkTempPaths, planFile)
                            com.boostlyte.vydra.utils.AppLogger.logExportCancelled(this@ExportService)
                            val manager = getSystemService(NotificationManager::class.java)
                            manager?.cancel(NOTIFICATION_ID)
                            broadcastCancelled()
                            return@launch
                        }
                    }

                    accumulatedWeight += chunkDur
                }

                // All chunks succeeded! Now execute final pass.
                val finalPassCmd = plan.finalPassCommand
                if (finalPassCmd == null) {
                    val errorMsg = "Missing final pass command in chunk plan"
                    cleanupChunkTempFiles(plan.chunkTempPaths, planFile)
                    broadcastFailure(errorMsg, ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                    return@launch
                }

                val finalSpanStart = if (totalWeight > 0.0) (totalChunkDurations / totalWeight) * 100.0 else (numChunks.toDouble() / (numChunks + 1)) * 100.0
                val finalSpanEnd = 100.0
                var lastReportedProgress = -1
                var lastNotificationTime = 0L

                val finalPassStartIso = com.boostlyte.vydra.utils.AppLogger.getIsoTimestamp()
                val finalPassStartTimeMs = System.currentTimeMillis()
                val finalRenderPath = plan.renderPath

                Log.d(TAG, "Starting final pass assembly (total duration: ${plan.totalDurationSecs}s)")
                updateNotification(finalSpanStart.toInt(), "Assembling final video...")

                val finalResult = ffmpegEngine.exportFinal(
                    ffmpegCommand = finalPassCmd,
                    totalDurationSecs = plan.totalDurationSecs,
                    onProgress = { p ->
                        try {
                            val overallProgress = (finalSpanStart + (p / 100.0) * (finalSpanEnd - finalSpanStart)).toInt().coerceIn(0, 99)
                            val now = System.currentTimeMillis()
                            if (overallProgress != lastReportedProgress && (now - lastNotificationTime >= 250L || p >= 100)) {
                                lastReportedProgress = overallProgress
                                lastNotificationTime = now
                                updateNotification(overallProgress, "Assembling final video...")
                                broadcastProgress(overallProgress)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error updating final pass progress: ${e.message}")
                        }
                    }
                )

                val finalPassEndTimeMs = System.currentTimeMillis()
                val finalPassEndIso = com.boostlyte.vydra.utils.AppLogger.getIsoTimestamp()
                val finalPassDurationSec = (finalPassEndTimeMs - finalPassStartTimeMs) / 1000.0

                try {
                    com.antonkarpenko.ffmpegkit.FFmpegKitConfig.clearSessions()
                } catch (e: Exception) {
                    Log.w(TAG, "Error clearing sessions after final pass: ${e.message}")
                }

                when (finalResult) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        com.boostlyte.vydra.utils.AppLogger.logPassResult(
                            context = this@ExportService,
                            passName = "final_pass",
                            startTimeIso = finalPassStartIso,
                            endTimeIso = finalPassEndIso,
                            durationSecs = finalPassDurationSec,
                            returnCode = 0,
                            renderPath = finalRenderPath
                        )
                        updateNotification(100, "Saving video...")
                        broadcastProgress(100)

                        if (!tempFile.exists() || tempFile.length() == 0L) {
                            Log.e(TAG, "Rendered output file is missing or 0 bytes: $tempOutputPath")
                            com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, "Rendered file is missing or empty", -1, null)
                            showCompletionNotification("Export Failed", "Rendered file is missing or empty", null)
                            broadcastFailure("Rendered output file is empty or missing", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        } else {
                            val savedUri = try {
                                saveVideoToGallery(tempFile, isAudioOnly)
                            } catch (e: Exception) {
                                Log.e(TAG, "Exception saving to gallery", e)
                                null
                            }
                            if (savedUri != null) {
                                com.boostlyte.vydra.utils.AppLogger.logExportEnded(this@ExportService, 0, tempOutputPath)
                                showCompletionNotification("Export Complete", "Video saved to gallery", savedUri)
                                broadcastSuccess(savedUri.toString())
                                Log.d(TAG, "Chunked export successful: $savedUri")
                            } else {
                                com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, "Failed to save video to gallery", -1, null)
                                showCompletionNotification("Export Failed", "Failed to save video to gallery", null)
                                broadcastFailure("Failed to save video to gallery", ErrorCode.GALLERY_SAVE_FAILED.code)
                            }
                        }
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        val returnCode = finalResult.session?.returnCode?.value
                        val errorLines = finalResult.error.split("\n")
                        com.boostlyte.vydra.utils.AppLogger.logPassFailed(
                            context = this@ExportService,
                            passName = "final_pass",
                            returnCode = returnCode,
                            scriptPath = null,
                            renderPath = finalRenderPath,
                            ffmpegLogs = errorLines
                        )
                        com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, "Final pass failed: ${finalResult.error}", returnCode, null)
                        showCompletionNotification("Export Failed", "Error during final assembly", null)
                        broadcastFailure("Final pass failed: ${finalResult.error}", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        Log.e(TAG, "Final pass failed: ${finalResult.error}")
                    }
                    is FFmpegRenderEngine.RenderResult.Cancelled -> {
                        Log.d(TAG, "Export was cancelled during final pass")
                        com.boostlyte.vydra.utils.AppLogger.logExportCancelled(this@ExportService)
                        val manager = getSystemService(NotificationManager::class.java)
                        manager?.cancel(NOTIFICATION_ID)
                        broadcastCancelled()
                    }
                }

                // Guaranteed cleanup of chunk temp files and plan file
                cleanupChunkTempFiles(plan.chunkTempPaths, planFile)
                try {
                    if (tempFile.exists()) tempFile.delete()
                    cacheDir.listFiles { f -> f.name.startsWith("filter_script_") && f.name.endsWith(".txt") }?.forEach { it.delete() }
                } catch (_: Exception) {}

            } catch (e: Exception) {
                Log.e(TAG, "Chunked export exception", e)
                cleanupChunkTempFiles(plan.chunkTempPaths, planFile)
                com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, e.message ?: "Unknown error", -1, null)
                showCompletionNotification("Export Failed", e.message ?: "Unknown error", null)
                broadcastFailure(e.message ?: "Unknown error", ErrorCode.UNEXPECTED_CRASH.code)
            } finally {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping service: ${e.message}")
                }
            }
        }
    }

    private fun cleanupChunkTempFiles(chunkPaths: List<String>, planFile: File?) {
        try {
            for (path in chunkPaths) {
                val f = File(path)
                if (f.exists()) {
                    f.delete()
                    Log.d(TAG, "Deleted temp chunk file: $path")
                }
            }
            if (planFile?.exists() == true) {
                planFile.delete()
                Log.d(TAG, "Deleted chunk plan file: ${planFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up chunk files: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

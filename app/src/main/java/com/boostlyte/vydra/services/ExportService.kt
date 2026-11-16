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
import com.boostlyte.vydra.models.EditOperation
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
        const val FORCE_LEGACY_EXPORT = true
        const val ACTION_EXPORT_PROGRESS = "com.boostlyte.vydra.ACTION_EXPORT_PROGRESS"
        const val ACTION_EXPORT_SUCCESS = "com.boostlyte.vydra.ACTION_EXPORT_SUCCESS"
        const val ACTION_EXPORT_FAILURE = "com.boostlyte.vydra.ACTION_EXPORT_FAILURE"
        const val ACTION_EXPORT_CANCELLED = "com.boostlyte.vydra.ACTION_EXPORT_CANCELLED"
        const val ACTION_CANCEL_EXPORT = "com.boostlyte.vydra.ACTION_CANCEL_EXPORT"
        const val ACTION_PREPARE_EXPORT = "com.boostlyte.vydra.ACTION_PREPARE_EXPORT"
        const val ACTION_UPDATE_PREP_PROGRESS = "com.boostlyte.vydra.ACTION_UPDATE_PREP_PROGRESS"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
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
        const val EXTRA_PREP_DURATION_MS = "extra_prep_duration_ms"
        const val EXTRA_EXPORT_METADATA = "extra_export_metadata"
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

        // Immediately attach foreground notification as required by Android 8.0+ (API 26+)
        // within 5 seconds of startForegroundService().
        val initProgress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 99)
        val initStatus = intent.getStringExtra(EXTRA_STATUS_MESSAGE) ?: "Preparing export..."
        startForeground(NOTIFICATION_ID, buildNotification(initProgress, initStatus))

        if (intent.action == ACTION_PREPARE_EXPORT) {
            Log.d(TAG, "ExportService started in foreground for preparation phase")
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_UPDATE_PREP_PROGRESS) {
            updateNotification(initProgress, initStatus)
            return START_NOT_STICKY
        }

        val tempOutputPath = intent.getStringExtra(EXTRA_TEMP_OUTPUT_PATH)
        if (tempOutputPath == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val isAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)
        val prepDurationMs = intent.getLongExtra(EXTRA_PREP_DURATION_MS, 0L)
        val chunkPlanFilePath = intent.getStringExtra(EXTRA_CHUNK_PLAN_FILE)
        if (chunkPlanFilePath != null) {
            val planFile = File(chunkPlanFilePath)
            if (planFile.exists()) {
                val chunkPlan = try {
                    if (planFile.name.endsWith(".json")) {
                        com.boostlyte.vydra.utils.ProjectSerializer.gson.fromJson(
                            planFile.readText(),
                            ChunkExportPlan::class.java
                        )
                    } else {
                        try {
                            java.io.ObjectInputStream(java.io.FileInputStream(planFile)).use {
                                it.readObject() as? ChunkExportPlan
                            }
                        } catch (eBin: Exception) {
                            com.boostlyte.vydra.utils.ProjectSerializer.gson.fromJson(
                                planFile.readText(),
                                ChunkExportPlan::class.java
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing chunk plan from ${planFile.absolutePath}: ${e.message}", e)
                    null
                }

                if (chunkPlan != null && chunkPlan.isChunked) {
                    handleChunkedExport(chunkPlan, tempOutputPath, isAudioOnly, planFile, prepDurationMs)
                    return START_NOT_STICKY
                }
            }
        }

        val command = intent.getStringExtra(EXTRA_COMMAND)
        if (command == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val concatFilePath = intent.getStringExtra(EXTRA_CONCAT_FILE_PATH)
        val durationSecs = intent.getDoubleExtra(EXTRA_TOTAL_DURATION_SECS, 0.0)

        // Storage verification for single-pass export
        val storageResult = com.boostlyte.vydra.export.StorageEstimator.checkStorageAvailable(
            directory = cacheDir,
            durationSecs = durationSecs,
            resolution = 1080,
            isChunked = false
        )
        if (!storageResult.isSufficient) {
            val msg = storageResult.message ?: "Insufficient storage for export"
            Log.e(TAG, msg)
            com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, msg, -1, null)
            showCompletionNotification("Export Failed", msg, null)
            broadcastFailure(msg, ErrorCode.STORAGE_FULL.code)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val scriptRegex = Regex("""-filter_complex_script\s+["']?([^"'\s]+)["']?""")
        val scriptFilePath = scriptRegex.find(command)?.groupValues?.getOrNull(1)

        val renderPath = intent.getStringExtra(EXTRA_RENDER_PATH) ?: "legacy"
        val extraMetadata = intent.getStringExtra(EXTRA_EXPORT_METADATA) ?: ""
        val sharedPrefs = getSharedPreferences("vydra_prefs", Context.MODE_PRIVATE)
        val prefEncoder = sharedPrefs.getString("default_encoder", "software") ?: "software"
        val parsedRes = Regex("""res=(\d+)p""").find(extraMetadata)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""scale=(\d+):(\d+)""").find(command)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val parsedFps = Regex("""fps=(\d+)""").find(command)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""@(\d+)""").find(extraMetadata)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 30
        val spTargetBitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetVideoBitrate(parsedRes.coerceAtLeast(360), parsedFps)
        val spTargetCrf = com.boostlyte.vydra.export.EncoderSelector.getTargetSoftwareCrf(parsedRes.coerceAtLeast(360))
        val spPlannedEncoder = if (command.contains("h264_mediacodec")) "h264_mediacodec" else "libx264"

        val projectStats = "durationSecs=$durationSecs isAudioOnly=$isAudioOnly tempOutput=$tempOutputPath script=$scriptFilePath RENDER_PATH=$renderPath"
        com.boostlyte.vydra.utils.AppLogger.logExportStarted(this@ExportService, projectStats)
        com.boostlyte.vydra.utils.ImagePreviewCache.clear()
        val spMemSnap = com.boostlyte.vydra.utils.AppLogger.getDeviceMemorySnapshot(this@ExportService)
        com.boostlyte.vydra.utils.AppLogger.logExportConfig(
            this@ExportService,
            "mode=single_pass isChunked=false encoderPref=$prefEncoder plannedEncoder=$spPlannedEncoder targetBitrate=$spTargetBitrate targetCrf=$spTargetCrf res=${parsedRes}p@$parsedFps $extraMetadata $spMemSnap"
        )

        val concatPreview = concatFilePath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) f.readText() else null
            } catch (_: Exception) { null }
        }
        com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
            context = this@ExportService,
            passName = "single_pass_executed",
            command = command,
            filterScriptContent = concatPreview?.let { "CONCAT_FILE:\n$it" }
        )

        updateNotification(0, "Exporting...")

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
                        val st = result.stats
                        if (st.hardwareFallbackTriggered) {
                            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                this@ExportService,
                                "hw_failure_single_pass_retry",
                                st.hardwareAttemptDurationSecs,
                                "reason=${st.hardwareFailureReason ?: "unknown"} fallbackTo=${st.actualEncoder}"
                            )
                        }
                        com.boostlyte.vydra.utils.AppLogger.logPassResult(
                            context = this@ExportService,
                            passName = "single_pass",
                            startTimeIso = passStartIso,
                            endTimeIso = passEndIso,
                            durationSecs = passDurationSec,
                            returnCode = 0,
                            renderPath = renderPath
                        )
                        val spEncoder = if (st.actualEncoder != "unknown") st.actualEncoder else spPlannedEncoder
                        com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                            this@ExportService,
                            "single_pass_ffmpeg",
                            passDurationSec,
                            "sizeBytes=${tempFile.length()} actualEncoder=$spEncoder hwFallback=${st.hardwareFallbackTriggered} " +
                                "hwAttemptSecs=${String.format(java.util.Locale.US, "%.2f", st.hardwareAttemptDurationSecs)} " +
                                "firstFrameMs=${st.firstFrameLatencyMs} avgFps=${String.format(java.util.Locale.US, "%.1f", st.avgFps)} " +
                                "avgSpeed=${String.format(java.util.Locale.US, "%.2f", st.avgSpeed)} " +
                                "ffmpegBitrateKbps=${String.format(java.util.Locale.US, "%.1f", st.lastReportedBitrateKbps)} frames=${st.finalFrameCount}"
                        )
                        updateNotification(100, "Saving video...")
                        broadcastProgress(100)

                        if (!tempFile.exists() || tempFile.length() == 0L) {
                            Log.e(TAG, "Rendered output file is missing or 0 bytes: $tempOutputPath")
                            com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, "Rendered file is missing or empty", -1, scriptFilePath)
                            showCompletionNotification("Export Failed", "Rendered file is missing or empty", null)
                            broadcastFailure("Rendered output file is empty or missing", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        } else {
                            val outputSizeBytes = tempFile.length()
                            val galleryStartMs = System.currentTimeMillis()
                            val (savedUri, saveMethod) = try {
                                saveVideoToGalleryWithMethod(tempFile, isAudioOnly)
                            } catch (e: Exception) {
                                Log.e(TAG, "Exception saving to gallery", e)
                                Pair(null, "exception")
                            }
                            val gallerySaveSecs = (System.currentTimeMillis() - galleryStartMs) / 1000.0
                            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                this@ExportService,
                                "gallery_save",
                                gallerySaveSecs,
                                "saved=${savedUri != null} method=$saveMethod sizeBytes=$outputSizeBytes"
                            )
                            if (savedUri != null) {
                                val prepSecs = prepDurationMs / 1000.0
                                val totalSecs = prepSecs + passDurationSec + gallerySaveSecs
                                com.boostlyte.vydra.utils.AppLogger.logExportProfileSummary(
                                    context = this@ExportService,
                                    mode = "single_pass",
                                    prepSecs = prepSecs,
                                    chunksSecs = 0.0,
                                    audioSecs = 0.0,
                                    finalAssemblySecs = passDurationSec,
                                    gallerySaveSecs = gallerySaveSecs,
                                    totalSecs = totalSecs,
                                    videoDurationSecs = durationSecs,
                                    outputSizeBytes = outputSizeBytes,
                                    resolution = parsedRes,
                                    fps = parsedFps,
                                    encoder = spEncoder,
                                    numChunks = 1,
                                    requestedEncoderPref = prefEncoder,
                                    hwRetryTriggered = st.hardwareFallbackTriggered,
                                    usedStreamCopy = false,
                                    reencodedFullVideo = false,
                                    targetBitrate = if (spEncoder == "h264_mediacodec") spTargetBitrate else "crf=$spTargetCrf",
                                    extraDetails = extraMetadata
                                )
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
        return saveVideoToGalleryWithMethod(videoFile, isAudioOnly).first
    }

    private fun saveVideoToGalleryWithMethod(videoFile: File, isAudioOnly: Boolean): Pair<Uri?, String> {
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
                        return Pair(newFileUri, "custom_saf")
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
            Pair(saveToMediaStoreQPlus(videoFile, mimeType, fileName, isAudioOnly), "mediastore_q")
        } else {
            Pair(saveToMediaStoreLegacy(videoFile, mimeType, fileName, isAudioOnly), "mediastore_legacy")
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
        planFile: File?,
        prepDurationMs: Long = 0L
    ) {
        val totalChunkDurations = plan.chunkDurationsSecs.sum()
        val finalPassDuration = plan.totalDurationSecs
        val totalWeight = totalChunkDurations + finalPassDuration
        val numChunks = plan.chunkCommands.size

        val projectStats = "mode=chunked numChunks=$numChunks totalDurationSecs=${plan.totalDurationSecs} isAudioOnly=$isAudioOnly tempOutput=$tempOutputPath canStreamCopy=${plan.canStreamCopyFinalPass} canStreamCopyWithAudio=${plan.canStreamCopyVideoWithAudioMux}"
        com.boostlyte.vydra.utils.AppLogger.logExportStarted(this@ExportService, projectStats)

        // Storage verification
        val storageResult = com.boostlyte.vydra.export.StorageEstimator.checkStorageAvailable(
            directory = cacheDir,
            durationSecs = plan.totalDurationSecs,
            resolution = plan.resolution,
            isChunked = true
        )
        if (!storageResult.isSufficient) {
            val msg = storageResult.message ?: "Insufficient storage for export"
            Log.e(TAG, msg)
            cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
            com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, msg, -1, null)
            showCompletionNotification("Export Failed", msg, null)
            broadcastFailure(msg, ErrorCode.STORAGE_FULL.code)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Free preview bitmaps on Java heap before heavy multi-chunk FFmpeg execution
        com.boostlyte.vydra.utils.ImagePreviewCache.clear()

        val memProfile = com.boostlyte.vydra.export.ExportMemoryProfile.fromContext(
            context = this@ExportService,
            targetWidth = plan.targetWidth,
            targetHeight = plan.targetHeight
        )
        val memSnapshot = com.boostlyte.vydra.utils.AppLogger.getDeviceMemorySnapshot(this@ExportService)

        // Encoder capability selection
        val sharedPrefs = getSharedPreferences("vydra_prefs", Context.MODE_PRIVATE)
        val prefEncoder = sharedPrefs.getString("default_encoder", "software") ?: "software"
        val encoderPref = when {
            prefEncoder.equals("hardware", ignoreCase = true) -> com.boostlyte.vydra.export.EncoderSelector.EncoderPreference.HARDWARE
            prefEncoder.equals("auto", ignoreCase = true) -> com.boostlyte.vydra.export.EncoderSelector.EncoderPreference.AUTO
            else -> com.boostlyte.vydra.export.EncoderSelector.EncoderPreference.SOFTWARE
        }
        val selectedEncoder = com.boostlyte.vydra.export.EncoderSelector.selectEncoder(
            preference = encoderPref,
            targetWidth = plan.targetWidth,
            targetHeight = plan.targetHeight,
            fps = plan.fps,
            softwareThreads = memProfile.softwareEncoderThreads
        )
        Log.d(TAG, "ExportService active encoder: ${selectedEncoder.backend} (${selectedEncoder.codecName}), swThreads=${memProfile.softwareEncoderThreads}")

        val targetBitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetVideoBitrate(plan.resolution, plan.fps)
        val targetCrf = com.boostlyte.vydra.export.EncoderSelector.getTargetSoftwareCrf(plan.resolution)
        val distinctClips = plan.chunkSpecs.flatMap { it.clips }.distinctBy { it.globalIndex }
        val totalImages = distinctClips.count { it.item.isImage }
        val totalVideos = distinctClips.count { !it.item.isImage }
        val activeTrans = plan.operations.filterIsInstance<EditOperation.Transition>()
            .filter { !it.type.equals("none", ignoreCase = true) && it.durationMs > 0L }
        val transTotalSec = activeTrans.sumOf { it.durationMs } / 1000.0
        val motionByIdx = mutableMapOf<Int, String>()
        for (c in distinctClips) {
            val mt = c.item.motionEffect
            if (!mt.isNullOrBlank() && !mt.equals("none", ignoreCase = true)) {
                motionByIdx[c.globalIndex] = mt
            }
        }
        for (mop in plan.operations.filterIsInstance<EditOperation.MotionEffect>()) {
            if (!mop.motionType.equals("none", ignoreCase = true)) {
                motionByIdx[mop.index] = mop.motionType
            } else {
                motionByIdx.remove(mop.index)
            }
        }
        val zoomInCount = motionByIdx.values.count { it.contains("zoom_in", true) || it.contains("in", true) }
        val zoomOutCount = motionByIdx.values.count { it.contains("zoom_out", true) || it.contains("out", true) }
        val panCount = motionByIdx.values.count { it.contains("pan", true) }
        val captionCuesCount = plan.operations.filterIsInstance<EditOperation.AddSubtitles>().sumOf { it.cues.size }
        val textOverlaysCount = plan.operations.filterIsInstance<EditOperation.AddText>().size
        val imageOverlaysCount = plan.operations.filterIsInstance<EditOperation.AddImageOverlay>().size
        val audioTracksCount = if (plan.operations.any { it is EditOperation.MuteAudio }) 0 else plan.operations.filterIsInstance<EditOperation.AddBackgroundAudio>().size
        val projectFeatureSummary = "images=$totalImages videos=$totalVideos transitions=${activeTrans.size} transTotalSec=${String.format(java.util.Locale.US, "%.2f", transTotalSec)} motionClips=${motionByIdx.size} (zoomIn=$zoomInCount zoomOut=$zoomOutCount pan=$panCount) captionCues=$captionCuesCount textOverlays=$textOverlaysCount imageOverlays=$imageOverlaysCount audioTracks=$audioTracksCount"

        com.boostlyte.vydra.utils.AppLogger.logExportConfig(
            this@ExportService,
            "mode=chunked isChunked=true numChunks=$numChunks encoderPref=$prefEncoder selectedBackend=${selectedEncoder.backend} selectedCodec=${selectedEncoder.codecName ?: "libx264"} " +
                "swThreads=${memProfile.softwareEncoderThreads} maxChunk=${memProfile.maxChunkSize} motionShortEdge=${memProfile.motionSpaceShortEdge} " +
                "targetBitrate=$targetBitrate targetCrf=$targetCrf audioBitrate=${plan.audioBitrate} dim=${plan.targetWidth}x${plan.targetHeight}@${plan.fps} res=${plan.resolution}p " +
                "canStreamCopyFinal=${plan.canStreamCopyFinalPass} canStreamCopyAudioMux=${plan.canStreamCopyVideoWithAudioMux} needsPadding=${plan.needsVideoPadding} padSecs=${String.format(java.util.Locale.US, "%.2f", plan.paddingDurationSecs)} " +
                "$projectFeatureSummary $memSnapshot"
        )

        updateNotification(8, "Exporting part 1/$numChunks...")
        broadcastProgress(8)

        serviceScope.launch {
            val serviceExecStartMs = System.currentTimeMillis()
            val prepSecs = prepDurationMs / 1000.0
            val tempFile = File(tempOutputPath)
            var accumulatedWeight = 0.0
            val jobDir = File(cacheDir, "export_job_${System.currentTimeMillis()}").apply { mkdirs() }

            try {
                val vm = com.boostlyte.vydra.viewmodels.VideoEditingViewModel()
                vm.setExportSettings(resolution = plan.resolution, fps = plan.fps, audioOnly = isAudioOnly)

                var currentChunkCommands = plan.chunkCommands
                var currentFinalPassCommand = plan.finalPassCommand
                var currentEncoderIsHardware = selectedEncoder.isHardware
                var activeCodecArgs = selectedEncoder.ffmpegVideoCodecArgs
                var activeEncoderLabel = selectedEncoder.codecName ?: if (currentEncoderIsHardware) "h264_mediacodec" else "libx264"
                var hasRetriedWithSoftware = false
                var chunkLoopSuccess = false
                var chunksStartMs = System.currentTimeMillis()
                val chunkActualEncoders = mutableListOf<String>()
                var perCmdHwFallbackCount = 0
                var fullJobHwRetryTriggered = false
                var totalFirstFrameMs = 0L
                var totalChunksEncodeSecs = 0.0

                while (!chunkLoopSuccess) {
                    var retryWithSoftwareRequested = false
                    accumulatedWeight = 0.0
                    chunksStartMs = System.currentTimeMillis()
                    chunkActualEncoders.clear()
                    totalFirstFrameMs = 0L
                    totalChunksEncodeSecs = 0.0

                    // Execute each chunk pass sequentially
                    val chunkBaseProgress = 8.0
                    val chunkSpanTotal = 86.0
                    for ((idx, chunkCmd) in currentChunkCommands.withIndex()) {
                        val chunkIdx = idx + 1
                        val chunkDur = plan.chunkDurationsSecs.getOrNull(idx) ?: (totalChunkDurations / numChunks)
                        val spanStart = if (totalChunkDurations > 0.0) {
                            chunkBaseProgress + (accumulatedWeight / totalChunkDurations) * chunkSpanTotal
                        } else {
                            chunkBaseProgress + (idx.toDouble() / numChunks) * chunkSpanTotal
                        }
                        val spanEnd = if (totalChunkDurations > 0.0) {
                            chunkBaseProgress + ((accumulatedWeight + chunkDur) / totalChunkDurations) * chunkSpanTotal
                        } else {
                            chunkBaseProgress + ((idx + 1).toDouble() / numChunks) * chunkSpanTotal
                        }

                        var lastReportedProgress = -1
                        var lastNotificationTime = 0L

                        Log.d(TAG, "Starting Chunk $chunkIdx/$numChunks (duration: ${chunkCmd.durationSecs}s, encoder: ${if (currentEncoderIsHardware) "hardware" else "software"})")
                        updateNotification(spanStart.toInt(), "Exporting part $chunkIdx/$numChunks...")
                        broadcastProgress(spanStart.toInt())

                        // Internal logging for every clip entering this chunk
                        val chunkSpec = plan.chunkSpecs.getOrNull(idx)
                        if (chunkSpec != null) {
                            for (clip in chunkSpec.clips) {
                                val origUriStr = clip.originalUriString ?: clip.item.uri.toString()
                                val scheme = try { clip.item.uri.scheme } catch (e: Exception) { null }
                                val resolvedPath = clip.resolvedLocalPath ?: clip.item.uri.path
                                val file = resolvedPath?.let { File(it) }
                                val exists = file?.exists() == true
                                val length = if (exists) file?.length() ?: 0L else 0L
                                Log.i(
                                    TAG,
                                    "Chunk media resolution: chunk=$chunkIdx, clipIndex=${clip.localIndex} (globalIndex=${clip.globalIndex}), " +
                                    "origUri='$origUriStr', scheme='$scheme', resolvedPath='$resolvedPath', exists=$exists, size=$length bytes"
                                )
                                if (!exists || length == 0L) {
                                    Log.e(
                                        TAG,
                                        "Chunk media resolution failure at chunk=$chunkIdx, clipIndex=${clip.localIndex}: " +
                                        "origUri='$origUriStr', scheme='$scheme', resolvedPath='$resolvedPath', stage='handleChunkedExport'"
                                    )
                                }
                            }
                        }

                        val chunkImages = chunkSpec?.clips?.count { it.item.isImage } ?: 0
                        val chunkVideos = chunkSpec?.clips?.count { !it.item.isImage } ?: 0
                        val chunkActiveTrans = chunkSpec?.internalTransitions?.filter { !it.type.equals("none", true) && it.durationMs > 0L } ?: emptyList()
                        val chunkTransSec = chunkActiveTrans.sumOf { it.durationMs } / 1000.0
                        val chunkMotionCount = chunkSpec?.clips?.count { c -> motionByIdx.containsKey(c.globalIndex) } ?: 0
                        val cStartMs = chunkSpec?.globalStartMs ?: 0L
                        val cDurMs = chunkSpec?.durationMs ?: 0L
                        val chunkCaptionCues = if (chunkSpec != null) {
                            plan.operations.filterIsInstance<EditOperation.AddSubtitles>().sumOf { sub ->
                                sub.cues.count { cue -> ChunkPlanner.clampToChunkWindow(cue.startTimeMs, cue.endTimeMs, cStartMs, cDurMs).isVisibleInChunk }
                            }
                        } else 0
                        val chunkTextCount = if (chunkSpec != null) {
                            plan.operations.filterIsInstance<EditOperation.AddText>().count { t ->
                                ChunkPlanner.clampToChunkWindow(t.startTimeMs ?: 0L, t.endTimeMs ?: (plan.totalDurationSecs * 1000).toLong(), cStartMs, cDurMs).isVisibleInChunk
                            }
                        } else 0
                        val chunkImgOverlayCount = if (chunkSpec != null) {
                            plan.operations.filterIsInstance<EditOperation.AddImageOverlay>().count { io ->
                                ChunkPlanner.clampToChunkWindow(io.startTimeMs ?: 0L, io.endTimeMs ?: (plan.totalDurationSecs * 1000).toLong(), cStartMs, cDurMs).isVisibleInChunk
                            }
                        } else 0

                        val activeCommand = chunkCmd.command
                        val activeScriptFile = chunkCmd.scriptFilePath
                        val chunkRenderPath = "legacy"

                        val passStartIso = com.boostlyte.vydra.utils.AppLogger.getIsoTimestamp()
                        val passStartTimeMs = System.currentTimeMillis()

                        val result = ffmpegEngine.exportFinal(
                            ffmpegCommand = activeCommand,
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
                                val st = result.stats
                                val executedEncoder = if (st.actualEncoder != "unknown") st.actualEncoder else activeEncoderLabel
                                chunkActualEncoders.add(executedEncoder)
                                if (st.hardwareFallbackTriggered) {
                                    perCmdHwFallbackCount++
                                    com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                        this@ExportService,
                                        "hw_failure_chunk_retry",
                                        st.hardwareAttemptDurationSecs,
                                        "chunk=$chunkIdx reason=${st.hardwareFailureReason ?: "unknown"} fallbackTo=$executedEncoder"
                                    )
                                }
                                if (st.firstFrameLatencyMs > 0L) {
                                    totalFirstFrameMs += st.firstFrameLatencyMs
                                }
                                totalChunksEncodeSecs += passDurationSec

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
                                    cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
                                    jobDir.deleteRecursively()
                                    com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, errorMsg, -1, activeScriptFile)
                                    showCompletionNotification("Export Failed", errorMsg, null)
                                    broadcastFailure(errorMsg, ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                                    return@launch
                                }
                                com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                    this@ExportService,
                                    "chunk_$chunkIdx",
                                    passDurationSec,
                                    "sizeBytes=${chunkOutputFile.length()} chunkDur=${String.format(java.util.Locale.US, "%.2f", chunkCmd.durationSecs)}s " +
                                        "actualEncoder=$executedEncoder hwFallback=${st.hardwareFallbackTriggered} " +
                                        "hwAttemptSecs=${String.format(java.util.Locale.US, "%.2f", st.hardwareAttemptDurationSecs)} " +
                                        "firstFrameMs=${st.firstFrameLatencyMs} avgFps=${String.format(java.util.Locale.US, "%.1f", st.avgFps)} " +
                                        "avgSpeed=${String.format(java.util.Locale.US, "%.2f", st.avgSpeed)} " +
                                        "ffmpegBitrateKbps=${String.format(java.util.Locale.US, "%.1f", st.lastReportedBitrateKbps)} frames=${st.finalFrameCount} " +
                                        "clips=${chunkSpec?.clips?.size ?: 0} images=$chunkImages videos=$chunkVideos " +
                                        "internalTrans=${chunkActiveTrans.size} transSec=${String.format(java.util.Locale.US, "%.2f", chunkTransSec)} " +
                                        "motionClips=$chunkMotionCount captionCues=$chunkCaptionCues textOverlays=$chunkTextCount imageOverlays=$chunkImgOverlayCount"
                                )
                                Log.d(TAG, "Chunk $chunkIdx/$numChunks completed successfully (${chunkOutputFile.length()} bytes)")
                            }
                            is FFmpegRenderEngine.RenderResult.Failure -> {
                                val stageName = "Chunk $chunkIdx/$numChunks"
                                val returnCode = result.session?.returnCode?.value
                                val errorLines = result.error.split("\n")

                                // Hardware encoder failure fallback to software libx264 for the ENTIRE job
                                if (currentEncoderIsHardware && !hasRetriedWithSoftware && !com.boostlyte.vydra.export.EncoderSelector.isPermanentFailure(result.error, false)) {
                                    val wastedSecs = (System.currentTimeMillis() - chunksStartMs) / 1000.0
                                    val failReasonLine = errorLines.firstOrNull {
                                        val low = it.lowercase()
                                        low.contains("error") || low.contains("failed") || low.contains("mediacodec")
                                    }?.trim()?.take(180) ?: "rc=${returnCode ?: -1}"
                                    fullJobHwRetryTriggered = true
                                    com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                        this@ExportService,
                                        "hw_failure_full_job_retry",
                                        wastedSecs,
                                        "failedChunk=$chunkIdx reason=$failReasonLine"
                                    )
                                    Log.w(TAG, "Hardware encoder failed on $stageName (${result.error}). Cleaning up partial chunks and restarting entire export with software libx264.")
                                    cleanupChunkTempFiles(plan.chunkTempPaths, null, plan.mixedAudioTempPath)
                                    hasRetriedWithSoftware = true
                                    currentEncoderIsHardware = false
                                    val swEncoder = com.boostlyte.vydra.export.EncoderSelector.selectEncoder(
                                        preference = com.boostlyte.vydra.export.EncoderSelector.EncoderPreference.SOFTWARE,
                                        targetWidth = plan.targetWidth,
                                        targetHeight = plan.targetHeight,
                                        fps = plan.fps,
                                        softwareThreads = memProfile.softwareEncoderThreads
                                    )
                                    activeCodecArgs = swEncoder.ffmpegVideoCodecArgs
                                    activeEncoderLabel = "libx264"

                                    val swChunkCommands = mutableListOf<ChunkCommand>()
                                    for ((cIdx, chunk) in plan.chunkSpecs.withIndex()) {
                                        val chunkOutputPath = plan.chunkTempPaths[cIdx]
                                        val swCmd = vm.buildChunkPassCommand(
                                            chunkSpec = chunk,
                                            sourceFilePath = chunk.clips.firstOrNull()?.item?.uri?.path ?: "",
                                            chunkOutputPath = chunkOutputPath,
                                            fontFilePath = plan.fontFilePath,
                                            context = this@ExportService,
                                            totalDurationMs = (plan.totalDurationSecs * 1000L).toLong(),
                                            density = plan.density,
                                            targetWidth = plan.targetWidth,
                                            targetHeight = plan.targetHeight,
                                            operations = plan.operations,
                                            encoderOverride = activeCodecArgs
                                        )
                                        swChunkCommands.add(swCmd.copy(renderPath = "legacy"))
                                    }
                                    currentChunkCommands = swChunkCommands

                                    val swCrf = com.boostlyte.vydra.export.EncoderSelector.getTargetSoftwareCrf(plan.resolution)
                                    currentFinalPassCommand = currentFinalPassCommand?.let {
                                        com.boostlyte.vydra.export.EncoderSelector.buildSoftwareFallbackCommand(it, swCrf, memProfile.softwareEncoderThreads)
                                    }
                                    retryWithSoftwareRequested = true
                                    break
                                }

                                com.boostlyte.vydra.utils.AppLogger.logPassFailed(
                                    context = this@ExportService,
                                    passName = "chunk_$chunkIdx",
                                    returnCode = returnCode,
                                    scriptPath = activeScriptFile,
                                    renderPath = chunkRenderPath,
                                    ffmpegLogs = errorLines
                                )
                                Log.e(TAG, "$stageName failed: ${result.error}")
                                cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
                                jobDir.deleteRecursively()
                                com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, "$stageName failed: ${result.error}", returnCode, activeScriptFile)
                                showCompletionNotification("Export Failed", "Error during $stageName", null)
                                broadcastFailure("$stageName failed: ${result.error}", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                                return@launch
                            }
                            is FFmpegRenderEngine.RenderResult.Cancelled -> {
                                Log.d(TAG, "Export was cancelled during chunk $chunkIdx/$numChunks")
                                cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
                                jobDir.deleteRecursively()
                                com.boostlyte.vydra.utils.AppLogger.logExportCancelled(this@ExportService)
                                val manager = getSystemService(NotificationManager::class.java)
                                manager?.cancel(NOTIFICATION_ID)
                                broadcastCancelled()
                                return@launch
                            }
                        }

                        accumulatedWeight += chunkDur
                    }

                    if (!retryWithSoftwareRequested) {
                        chunkLoopSuccess = true
                    }
                }

                val chunksTotalSecs = (System.currentTimeMillis() - chunksStartMs) / 1000.0
                val distinctChunkEncoders = chunkActualEncoders.distinct().joinToString("+").ifBlank { activeEncoderLabel }
                com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                    this@ExportService,
                    "chunks_total",
                    chunksTotalSecs,
                    "numChunks=$numChunks actualEncoders=$distinctChunkEncoders perCmdHwFallbacks=$perCmdHwFallbackCount fullJobHwRetry=$fullJobHwRetryTriggered"
                )

                // All chunks succeeded! Verify chunk files before audio/final assembly.
                val finalSpanStart = 94.0
                val finalSpanEnd = 99.0
                var lastReportedProgress = -1
                var lastNotificationTime = 0L

                val finalRenderPath = plan.renderPath

                // Pre-assembly chunk verification
                Log.d(TAG, "Starting final pass assembly verification for ${currentChunkCommands.size} chunks (total duration: ${plan.totalDurationSecs}s)")
                for ((idx, chunkCmd) in currentChunkCommands.withIndex()) {
                    val f = File(chunkCmd.outputPath)
                    val exists = f.exists()
                    val length = if (exists) f.length() else 0L
                    Log.d(TAG, "Chunk verification [$idx/${currentChunkCommands.size}]: path=${f.absolutePath}, exists=$exists, sizeBytes=$length, plannedDur=${chunkCmd.durationSecs}s")
                    if (!exists || length == 0L) {
                        val errorMsg = "Chunk $idx intermediate file is missing or empty (${f.absolutePath})"
                        Log.e(TAG, errorMsg)
                        cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
                        jobDir.deleteRecursively()
                        broadcastFailure(errorMsg, ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        return@launch
                    }
                }

                // Stage 4: Pre-mix background audio / voiceover into a single AAC .m4a file
                // so final assembly can concatenate chunk videos via `-c copy` without video re-encoding.
                var audioSecs = 0.0
                var mixedAudioReady = false
                if (!plan.audioMixCommand.isNullOrBlank() && !plan.mixedAudioTempPath.isNullOrBlank()) {
                    val audioStartMs = System.currentTimeMillis()
                    updateNotification(finalSpanStart.toInt(), "Processing audio...")
                    com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                        context = this@ExportService,
                        passName = "audio_mix_executed",
                        command = plan.audioMixCommand,
                        filterScriptContent = null
                    )
                    val audioResult = ffmpegEngine.exportFinal(
                        ffmpegCommand = plan.audioMixCommand,
                        totalDurationSecs = plan.totalDurationSecs,
                        onProgress = { }
                    )
                    audioSecs = (System.currentTimeMillis() - audioStartMs) / 1000.0
                    try {
                        com.antonkarpenko.ffmpegkit.FFmpegKitConfig.clearSessions()
                    } catch (_: Exception) {}

                    when (audioResult) {
                        is FFmpegRenderEngine.RenderResult.Success -> {
                            val mixedFile = File(plan.mixedAudioTempPath)
                            mixedAudioReady = mixedFile.exists() && mixedFile.length() > 0L
                            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                this@ExportService,
                                "audio_processing",
                                audioSecs,
                                "ready=$mixedAudioReady sizeBytes=${if (mixedAudioReady) mixedFile.length() else 0L} actualEncoder=${audioResult.stats.actualEncoder} avgSpeed=${String.format(java.util.Locale.US, "%.2f", audioResult.stats.avgSpeed)}"
                            )
                        }
                        is FFmpegRenderEngine.RenderResult.Cancelled -> {
                            Log.d(TAG, "Export was cancelled during audio mix pass")
                            cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
                            jobDir.deleteRecursively()
                            com.boostlyte.vydra.utils.AppLogger.logExportCancelled(this@ExportService)
                            val manager = getSystemService(NotificationManager::class.java)
                            manager?.cancel(NOTIFICATION_ID)
                            broadcastCancelled()
                            return@launch
                        }
                        is FFmpegRenderEngine.RenderResult.Failure -> {
                            Log.w(TAG, "Audio mix pre-pass failed (${audioResult.error}), falling back to full final pass.")
                            try {
                                File(plan.mixedAudioTempPath).delete()
                            } catch (_: Exception) {}
                            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                this@ExportService,
                                "audio_processing",
                                audioSecs,
                                "ready=false fallback=true"
                            )
                        }
                    }
                } else {
                    val hasAnyAudioOp = plan.operations.any { it is EditOperation.AddBackgroundAudio } &&
                        plan.operations.none { it is EditOperation.MuteAudio }
                    val skipReason = if (hasAnyAudioOp) "boundary_transitions_deferred_to_final" else "no_audio_ops"
                    com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                        this@ExportService,
                        "audio_processing",
                        0.0,
                        "skipped=true reason=$skipReason"
                    )
                }

                // Stage 5: Final Assembly
                updateNotification(finalSpanStart.toInt(), "Assembling final video...")
                val finalPassStartIso = com.boostlyte.vydra.utils.AppLogger.getIsoTimestamp()
                val finalPassStartTimeMs = System.currentTimeMillis()

                var usedStreamCopy = false
                var paddedLastChunkSuccess = true
                var padLastChunkSecs = 0.0
                var streamCopyAttemptSecs = 0.0
                var streamCopyFallbackTriggered = false
                var reencodeFinalSecs = 0.0
                var finalAssemblyMode = "full_reencode_xfade"
                var concatListContentForLog: String? = null
                val formattedTotalDur = String.format(java.util.Locale.US, "%.6f", plan.totalDurationSecs)
                val safeFps = plan.fps.coerceAtLeast(1)
                fun chunkConcatDurationSecs(index: Int, cmd: ChunkCommand): Double {
                    val spec = plan.chunkSpecs.getOrNull(index)
                    return if (spec != null && spec.totalFrames > 0) {
                        spec.totalFrames.toDouble() / safeFps
                    } else {
                        cmd.durationSecs
                    }
                }

                var finalPassCmdStr: String? = null
                if (plan.canStreamCopyFinalPass) {
                    Log.d(TAG, "Using fast stream copy concat demuxer for final chunk assembly (video only)")
                    val concatContent = StringBuilder()
                    for ((idx, chunkCmd) in currentChunkCommands.withIndex()) {
                        concatContent.append("file '${File(chunkCmd.outputPath).absolutePath}'\n")
                        concatContent.append(String.format(java.util.Locale.US, "duration %.6f\n", chunkConcatDurationSecs(idx, chunkCmd)))
                    }
                    val concatListFile = File(jobDir, "final_concat_list.txt")
                    concatListContentForLog = concatContent.toString()
                    concatListFile.writeText(concatListContentForLog)
                    usedStreamCopy = true
                    finalAssemblyMode = "stream_copy_video_only"
                    finalPassCmdStr = "-y -f concat -safe 0 -i \"${concatListFile.absolutePath}\" -c copy -movflags +faststart \"$tempOutputPath\""
                } else if (plan.canStreamCopyVideoWithAudioMux && mixedAudioReady && !plan.mixedAudioTempPath.isNullOrBlank()) {
                    Log.d(TAG, "Using fast stream copy concat + mixed AAC audio mux for final chunk assembly")
                    val concatContent = StringBuilder()
                    for ((idx, chunkCmd) in currentChunkCommands.withIndex()) {
                        concatContent.append("file '${File(chunkCmd.outputPath).absolutePath}'\n")
                        concatContent.append(String.format(java.util.Locale.US, "duration %.6f\n", chunkConcatDurationSecs(idx, chunkCmd)))
                    }
                    val concatListFile = File(jobDir, "final_concat_list.txt")
                    concatListContentForLog = concatContent.toString()
                    concatListFile.writeText(concatListContentForLog)
                    usedStreamCopy = true
                    finalAssemblyMode = "stream_copy_audio_mux"
                    finalPassCmdStr = "-y -f concat -safe 0 -i \"${concatListFile.absolutePath}\" -i \"${plan.mixedAudioTempPath}\" -map 0:v -map 1:a -c copy -movflags +faststart -t $formattedTotalDur \"$tempOutputPath\""
                } else if (mixedAudioReady && plan.needsVideoPadding && !plan.mixedAudioTempPath.isNullOrBlank() && currentChunkCommands.isNotEmpty()) {
                    // Pad ONLY the last chunk so preceding chunks stay untouched for -c copy mux
                    val lastChunk = currentChunkCommands.last()
                    val paddedLastChunkFile = File(jobDir, "padded_last_chunk.mp4")
                    val padFrames = kotlin.math.round(plan.paddingDurationSecs * safeFps).toInt().coerceAtLeast(1)
                    val quantizedPadSecs = padFrames.toDouble() / safeFps
                    val formattedPad = String.format(java.util.Locale.US, "%.6f", quantizedPadSecs)
                    val padCmd = "-y -i \"${lastChunk.outputPath}\" -vf \"tpad=stop_mode=clone:stop_duration=$formattedPad,settb=1/$safeFps,fps=$safeFps,format=yuv420p\" -an $activeCodecArgs -video_track_timescale ${safeFps * 1000} \"${paddedLastChunkFile.absolutePath}\""
                    Log.d(TAG, "Audio extends ${formattedPad}s past video; padding only last chunk for stream-copy assembly")
                    com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                        context = this@ExportService,
                        passName = "final_assembly_pad_last_chunk",
                        command = padCmd,
                        filterScriptContent = null
                    )
                    val padStartMs = System.currentTimeMillis()
                    val padResult = ffmpegEngine.exportFinal(
                        ffmpegCommand = padCmd,
                        totalDurationSecs = lastChunk.durationSecs + quantizedPadSecs,
                        onProgress = { }
                    )
                    padLastChunkSecs = (System.currentTimeMillis() - padStartMs) / 1000.0
                    try {
                        com.antonkarpenko.ffmpegkit.FFmpegKitConfig.clearSessions()
                    } catch (_: Exception) {}

                    when (padResult) {
                        is FFmpegRenderEngine.RenderResult.Success -> {
                            val padExists = paddedLastChunkFile.exists() && paddedLastChunkFile.length() > 0L
                            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                this@ExportService,
                                "final_assembly_pad_last_chunk",
                                padLastChunkSecs,
                                "success=$padExists sizeBytes=${if (padExists) paddedLastChunkFile.length() else 0L} actualEncoder=${padResult.stats.actualEncoder} hwFallback=${padResult.stats.hardwareFallbackTriggered}"
                            )
                            if (padExists) {
                                val concatContent = StringBuilder()
                                for (cIdx in 0 until currentChunkCommands.size - 1) {
                                    val cCmd = currentChunkCommands[cIdx]
                                    concatContent.append("file '${File(cCmd.outputPath).absolutePath}'\n")
                                    concatContent.append(String.format(java.util.Locale.US, "duration %.6f\n", chunkConcatDurationSecs(cIdx, cCmd)))
                                }
                                val lastBaseDur = chunkConcatDurationSecs(currentChunkCommands.size - 1, lastChunk)
                                concatContent.append("file '${paddedLastChunkFile.absolutePath}'\n")
                                concatContent.append(String.format(java.util.Locale.US, "duration %.6f\n", lastBaseDur + quantizedPadSecs))
                                val concatListFile = File(jobDir, "final_concat_list.txt")
                                concatListContentForLog = concatContent.toString()
                                concatListFile.writeText(concatListContentForLog)
                                usedStreamCopy = true
                                finalAssemblyMode = "padded_last_chunk_stream_copy"
                                finalPassCmdStr = "-y -f concat -safe 0 -i \"${concatListFile.absolutePath}\" -i \"${plan.mixedAudioTempPath}\" -map 0:v -map 1:a -c copy -movflags +faststart -t $formattedTotalDur \"$tempOutputPath\""
                            } else {
                                paddedLastChunkSuccess = false
                            }
                        }
                        is FFmpegRenderEngine.RenderResult.Cancelled -> {
                            Log.d(TAG, "Export was cancelled during last-chunk padding")
                            cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
                            jobDir.deleteRecursively()
                            com.boostlyte.vydra.utils.AppLogger.logExportCancelled(this@ExportService)
                            val manager = getSystemService(NotificationManager::class.java)
                            manager?.cancel(NOTIFICATION_ID)
                            broadcastCancelled()
                            return@launch
                        }
                        is FFmpegRenderEngine.RenderResult.Failure -> {
                            Log.w(TAG, "Last-chunk padding failed (${padResult.error}), falling back to full final pass.")
                            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                this@ExportService,
                                "final_assembly_pad_last_chunk",
                                padLastChunkSecs,
                                "success=false reason=${padResult.error.replace("\n", " ").take(180)}"
                            )
                            paddedLastChunkSuccess = false
                        }
                    }
                }

                if (finalPassCmdStr == null || !paddedLastChunkSuccess) {
                    usedStreamCopy = false
                    finalAssemblyMode = "full_reencode_xfade"
                    finalPassCmdStr = currentFinalPassCommand ?: run {
                        val errorMsg = "Missing final pass command in chunk plan"
                        cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
                        jobDir.deleteRecursively()
                        broadcastFailure(errorMsg, ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        return@launch
                    }
                }

                com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                    context = this@ExportService,
                    passName = "final_assembly_attempt_$finalAssemblyMode",
                    command = finalPassCmdStr,
                    filterScriptContent = concatListContentForLog?.let { "CONCAT_LIST:\n$it" }
                )

                val firstAttemptStartMs = System.currentTimeMillis()
                var finalResult = ffmpegEngine.exportFinal(
                    ffmpegCommand = finalPassCmdStr!!,
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
                val firstAttemptSecs = (System.currentTimeMillis() - firstAttemptStartMs) / 1000.0
                if (usedStreamCopy) {
                    streamCopyAttemptSecs = firstAttemptSecs
                    com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                        this@ExportService,
                        "final_assembly_stream_copy_attempt",
                        streamCopyAttemptSecs,
                        "success=${finalResult is FFmpegRenderEngine.RenderResult.Success} mode=$finalAssemblyMode"
                    )
                } else {
                    reencodeFinalSecs = firstAttemptSecs
                }

                // Fallback: If fast stream copy failed, seamlessly retry with safe flat re-encoding final pass
                if (finalResult is FFmpegRenderEngine.RenderResult.Failure && usedStreamCopy && !currentFinalPassCommand.isNullOrBlank()) {
                    streamCopyFallbackTriggered = true
                    finalAssemblyMode = "full_reencode_fallback"
                    val scErrSnippet = finalResult.error.replace("\n", " ").take(200)
                    com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                        this@ExportService,
                        "final_assembly_stream_copy_fallback",
                        streamCopyAttemptSecs,
                        "reason=$scErrSnippet"
                    )
                    Log.w(TAG, "Stream copy concat failed (${finalResult.error}). Retrying with flat re-encoding fallback pass.")
                    try {
                        tempFile.delete()
                    } catch (_: Exception) {}
                    usedStreamCopy = false
                    finalPassCmdStr = currentFinalPassCommand
                    com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                        context = this@ExportService,
                        passName = "final_assembly_reencode_fallback_executed",
                        command = finalPassCmdStr,
                        filterScriptContent = null
                    )
                    val fallbackStartMs = System.currentTimeMillis()
                    finalResult = ffmpegEngine.exportFinal(
                        ffmpegCommand = finalPassCmdStr!!,
                        totalDurationSecs = plan.totalDurationSecs,
                        onProgress = { p ->
                            try {
                                val overallProgress = (finalSpanStart + (p / 100.0) * (finalSpanEnd - finalSpanStart)).toInt().coerceIn(0, 99)
                                val now = System.currentTimeMillis()
                                if (overallProgress != lastReportedProgress && (now - lastNotificationTime >= 250L || p >= 100)) {
                                    lastReportedProgress = overallProgress
                                    lastNotificationTime = now
                                    updateNotification(overallProgress, "Assembling final video (fallback)...")
                                    broadcastProgress(overallProgress)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error updating fallback final pass progress: ${e.message}")
                            }
                        }
                    )
                    reencodeFinalSecs = (System.currentTimeMillis() - fallbackStartMs) / 1000.0
                }

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
                        val fst = finalResult.stats
                        if (fst.hardwareFallbackTriggered) {
                            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                this@ExportService,
                                "hw_failure_final_pass_retry",
                                fst.hardwareAttemptDurationSecs,
                                "reason=${fst.hardwareFailureReason ?: "unknown"} fallbackTo=${fst.actualEncoder}"
                            )
                        }
                        com.boostlyte.vydra.utils.AppLogger.logPassResult(
                            context = this@ExportService,
                            passName = "final_pass",
                            startTimeIso = finalPassStartIso,
                            endTimeIso = finalPassEndIso,
                            durationSecs = finalPassDurationSec,
                            returnCode = 0,
                            renderPath = finalRenderPath
                        )
                        val outputSizeBytes = tempFile.length()
                        com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                            this@ExportService,
                            "final_assembly",
                            finalPassDurationSec,
                            "mode=$finalAssemblyMode usedStreamCopy=$usedStreamCopy streamCopyFallback=$streamCopyFallbackTriggered " +
                                "actualEncoder=${fst.actualEncoder} hwFallback=${fst.hardwareFallbackTriggered} " +
                                "padLastChunkSecs=${String.format(java.util.Locale.US, "%.2f", padLastChunkSecs)} " +
                                "streamCopySecs=${String.format(java.util.Locale.US, "%.2f", streamCopyAttemptSecs)} " +
                                "reencodeSecs=${String.format(java.util.Locale.US, "%.2f", reencodeFinalSecs)} " +
                                "firstFrameMs=${fst.firstFrameLatencyMs} avgFps=${String.format(java.util.Locale.US, "%.1f", fst.avgFps)} " +
                                "avgSpeed=${String.format(java.util.Locale.US, "%.2f", fst.avgSpeed)} " +
                                "ffmpegBitrateKbps=${String.format(java.util.Locale.US, "%.1f", fst.lastReportedBitrateKbps)} frames=${fst.finalFrameCount} outputSizeBytes=$outputSizeBytes"
                        )
                        val totalFirstFrameWaitSecs = (totalFirstFrameMs + maxOf(0L, fst.firstFrameLatencyMs)) / 1000.0
                        val totalEncodingStageSecs = totalChunksEncodeSecs + padLastChunkSecs + reencodeFinalSecs
                        com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                            this@ExportService,
                            "final_encoding_summary",
                            totalEncodingStageSecs,
                            "chunksEncodeSecs=${String.format(java.util.Locale.US, "%.2f", totalChunksEncodeSecs)} " +
                                "padLastChunkSecs=${String.format(java.util.Locale.US, "%.2f", padLastChunkSecs)} " +
                                "finalReencodeSecs=${String.format(java.util.Locale.US, "%.2f", reencodeFinalSecs)} " +
                                "firstFrameWaitSecs=${String.format(java.util.Locale.US, "%.2f", totalFirstFrameWaitSecs)} " +
                                "chunkActualEncoders=$distinctChunkEncoders finalActualEncoder=${fst.actualEncoder} " +
                                "hwToSwPerCmdRetries=$perCmdHwFallbackCount hwToSwFullJobRetry=$fullJobHwRetryTriggered finalHwFallback=${fst.hardwareFallbackTriggered} " +
                                "targetBitrate=$targetBitrate measuredFinalBitrateKbps=${String.format(java.util.Locale.US, "%.1f", fst.lastReportedBitrateKbps)}"
                        )
                        updateNotification(100, "Saving video...")
                        broadcastProgress(100)

                        if (!tempFile.exists() || outputSizeBytes == 0L) {
                            Log.e(TAG, "Rendered output file is missing or 0 bytes: $tempOutputPath")
                            com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, "Rendered file is missing or empty", -1, null)
                            showCompletionNotification("Export Failed", "Rendered file is missing or empty", null)
                            broadcastFailure("Rendered output file is empty or missing", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        } else {
                            val galleryStartMs = System.currentTimeMillis()
                            val (savedUri, saveMethod) = try {
                                saveVideoToGalleryWithMethod(tempFile, isAudioOnly)
                            } catch (e: Exception) {
                                Log.e(TAG, "Exception saving to gallery", e)
                                Pair(null, "exception")
                            }
                            val gallerySaveSecs = (System.currentTimeMillis() - galleryStartMs) / 1000.0
                            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                                this@ExportService,
                                "gallery_save",
                                gallerySaveSecs,
                                "saved=${savedUri != null} method=$saveMethod sizeBytes=$outputSizeBytes"
                            )
                            if (savedUri != null) {
                                val totalSecs = prepSecs + ((System.currentTimeMillis() - serviceExecStartMs) / 1000.0)
                                val anyHwRetry = fullJobHwRetryTriggered || perCmdHwFallbackCount > 0 || fst.hardwareFallbackTriggered
                                com.boostlyte.vydra.utils.AppLogger.logExportProfileSummary(
                                    context = this@ExportService,
                                    mode = "chunked",
                                    prepSecs = prepSecs,
                                    chunksSecs = chunksTotalSecs,
                                    audioSecs = audioSecs,
                                    finalAssemblySecs = finalPassDurationSec,
                                    gallerySaveSecs = gallerySaveSecs,
                                    totalSecs = totalSecs,
                                    videoDurationSecs = plan.totalDurationSecs,
                                    outputSizeBytes = outputSizeBytes,
                                    resolution = plan.resolution,
                                    fps = plan.fps,
                                    encoder = distinctChunkEncoders,
                                    numChunks = numChunks,
                                    requestedEncoderPref = prefEncoder,
                                    hwRetryTriggered = anyHwRetry,
                                    usedStreamCopy = usedStreamCopy,
                                    reencodedFullVideo = !usedStreamCopy,
                                    targetBitrate = if (distinctChunkEncoders.contains("h264_mediacodec")) targetBitrate else "crf=$targetCrf",
                                    extraDetails = "finalMode=$finalAssemblyMode $projectFeatureSummary"
                                )
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
                cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
                jobDir.deleteRecursively()
                try {
                    if (tempFile.exists()) tempFile.delete()
                    cacheDir.listFiles { f -> f.name.startsWith("filter_script_") && f.name.endsWith(".txt") }?.forEach { it.delete() }
                } catch (_: Exception) {}

            } catch (e: Exception) {
                Log.e(TAG, "Chunked export exception", e)
                cleanupChunkTempFiles(plan.chunkTempPaths, planFile, plan.mixedAudioTempPath)
                jobDir.deleteRecursively()
                com.boostlyte.vydra.utils.AppLogger.logExportFailed(this@ExportService, e.message ?: "Unknown error", -1, null)
                showCompletionNotification("Export Failed", e.message ?: "Unknown error", null)
                broadcastFailure(e.message ?: "Unknown error", ErrorCode.UNEXPECTED_CRASH.code)
            } finally {
                try {
                    jobDir.deleteRecursively()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping service: ${e.message}")
                }
            }
        }
    }

    private fun cleanupChunkTempFiles(chunkPaths: List<String>, planFile: File?, mixedAudioTempPath: String? = null) {
        try {
            for (path in chunkPaths) {
                val f = File(path)
                if (f.exists()) {
                    f.delete()
                    Log.d(TAG, "Deleted temp chunk file: $path")
                }
            }
            if (!mixedAudioTempPath.isNullOrBlank()) {
                val audioFile = File(mixedAudioTempPath)
                if (audioFile.exists()) {
                    audioFile.delete()
                    Log.d(TAG, "Deleted temp mixed audio file: $mixedAudioTempPath")
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

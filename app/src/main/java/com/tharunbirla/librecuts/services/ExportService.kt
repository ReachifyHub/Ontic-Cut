package com.tharunbirla.librecuts.services

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
import com.tharunbirla.librecuts.MainActivity
import com.tharunbirla.librecuts.R
import com.tharunbirla.librecuts.utils.ErrorCode
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
        const val ACTION_EXPORT_PROGRESS = "com.tharunbirla.librecuts.ACTION_EXPORT_PROGRESS"
        const val ACTION_EXPORT_SUCCESS = "com.tharunbirla.librecuts.ACTION_EXPORT_SUCCESS"
        const val ACTION_EXPORT_FAILURE = "com.tharunbirla.librecuts.ACTION_EXPORT_FAILURE"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SAVED_URI = "extra_saved_uri"
        const val EXTRA_ERROR = "extra_error"
        const val EXTRA_ERROR_CODE = "extra_error_code"

        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TEMP_OUTPUT_PATH = "extra_temp_output_path"
        const val EXTRA_CONCAT_FILE_PATH = "extra_concat_file_path"
        const val EXTRA_TOTAL_DURATION_SECS = "extra_total_duration_secs"
        const val EXTRA_IS_AUDIO_ONLY = "extra_is_audio_only"
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

        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
        val tempOutputPath = intent.getStringExtra(EXTRA_TEMP_OUTPUT_PATH) ?: return START_NOT_STICKY
        val concatFilePath = intent.getStringExtra(EXTRA_CONCAT_FILE_PATH)
        val durationSecs = intent.getDoubleExtra(EXTRA_TOTAL_DURATION_SECS, 0.0)
        val isAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)

        startForeground(NOTIFICATION_ID, buildNotification(0, "Exporting..."))

        serviceScope.launch {
            try {
                val result = ffmpegEngine.exportFinal(
                    ffmpegCommand = command,
                    totalDurationSecs = durationSecs,
                    onProgress = { progress ->
                        updateNotification(progress, "Exporting...")
                        broadcastProgress(progress)
                    }
                )

                val tempFile = File(tempOutputPath)
                val concatFile = concatFilePath?.let { File(it) }

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        if (!tempFile.exists() || tempFile.length() == 0L) {
                            Log.e(TAG, "Rendered output file is missing or 0 bytes: $tempOutputPath")
                            showCompletionNotification("Export Failed", "Rendered file is missing or empty", null)
                            broadcastFailure("Rendered output file is empty or missing", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        } else {
                            val savedUri = saveVideoToGallery(tempFile, isAudioOnly)
                            if (savedUri != null) {
                                showCompletionNotification("Export Complete", "Video saved to gallery", savedUri)
                                broadcastSuccess(savedUri.toString())
                                Log.d(TAG, "Export successful: $savedUri")
                            } else {
                                showCompletionNotification("Export Failed", "Failed to save video to gallery", null)
                                broadcastFailure("Failed to save video to gallery", ErrorCode.GALLERY_SAVE_FAILED.code)
                            }
                        }
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        showCompletionNotification("Export Failed", "Error during rendering", null)
                        broadcastFailure(result.error, ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                        Log.e(TAG, "Export failed: ${result.error}")
                    }
                    is FFmpegRenderEngine.RenderResult.Cancelled -> {
                        showCompletionNotification("Export Cancelled", "The export was cancelled", null)
                        broadcastFailure("Export cancelled", ErrorCode.FFMPEG_EXECUTION_FAILED.code)
                    }
                }

                // Cleanup temp files
                if (tempFile.exists()) tempFile.delete()
                if (concatFile?.exists() == true) concatFile.delete()

            } catch (e: Exception) {
                Log.e(TAG, "Export exception", e)
                showCompletionNotification("Export Failed", e.message ?: "Unknown error", null)
                broadcastFailure(e.message ?: "Unknown error", ErrorCode.UNEXPECTED_CRASH.code)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (videoUri != null) {
                setDataAndType(videoUri, if (message.contains("Audio", true)) "audio/*" else "video/*")
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
        val prefix = if (isAudioOnly) "LibreCuts_Audio_" else "LibreCuts_"
        val fileName = "${prefix}${System.currentTimeMillis()}$ext"
        
        val sharedPreferences = getSharedPreferences("librecuts_prefs", Context.MODE_PRIVATE)
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
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isAudioOnly) Environment.DIRECTORY_MUSIC + "/LibreCuts" else Environment.DIRECTORY_MOVIES + "/LibreCuts"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collectionUri = if (isAudioOnly) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
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
            val libreCutsDir = File(publicDir, "LibreCuts")
            if (!libreCutsDir.exists() && !libreCutsDir.mkdirs()) {
                Log.w(TAG, "Directory ${libreCutsDir.absolutePath} could not be created or already exists")
            }
            val destFile = File(libreCutsDir, fileName)
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
            android.media.MediaScannerConnection.scanFile(
                applicationContext,
                arrayOf(destFile.absolutePath),
                arrayOf(mimeType)
            ) { path, scannedUri ->
                Log.d(TAG, "Legacy MediaScanner finished for $path -> $scannedUri")
            }

            insertedUri ?: Uri.fromFile(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video in legacy mode (API <= 28): ${e.message}", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

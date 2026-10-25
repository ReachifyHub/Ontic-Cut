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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tharunbirla.librecuts.MainActivity
import com.tharunbirla.librecuts.R
import com.tharunbirla.librecuts.transcription.VoiceoverTranscriptionPipeline
import com.tharunbirla.librecuts.transcription.models.TranscriptionPipelineResult
import com.tharunbirla.librecuts.utils.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground Service for running voiceover transcription in the background without freezing the UI.
 * Follows the existing Ontic Cut background patterns (ExportService, ProxyGenerationService).
 */
class VoiceoverTranscriptionService : Service() {

    private val TAG = "VoiceoverTransService"
    private val CHANNEL_ID = "TranscriptionChannel"
    private val NOTIFICATION_ID = 1003

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var pipeline: VoiceoverTranscriptionPipeline

    companion object {
        const val ACTION_TRANSCRIPTION_PROGRESS = "com.tharunbirla.librecuts.ACTION_TRANSCRIPTION_PROGRESS"
        const val ACTION_TRANSCRIPTION_SUCCESS = "com.tharunbirla.librecuts.ACTION_TRANSCRIPTION_SUCCESS"
        const val ACTION_TRANSCRIPTION_FAILURE = "com.tharunbirla.librecuts.ACTION_TRANSCRIPTION_FAILURE"
        const val ACTION_TRANSCRIPTION_BLOCKED_AUTH = "com.tharunbirla.librecuts.ACTION_TRANSCRIPTION_BLOCKED_AUTH"

        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_AUDIO_DURATION_SECONDS = "extra_audio_duration_seconds"
        const val EXTRA_PROJECT_NAME = "extra_project_name"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_STATUS_TEXT = "extra_status_text"
        const val EXTRA_TRANSCRIPTION_FILE_PATH = "extra_transcription_file_path"
        const val EXTRA_TRANSCRIPTION_TEXT = "extra_transcription_text"
        const val EXTRA_ERROR = "extra_error"
        const val EXTRA_ERROR_CODE = "extra_error_code"
        const val EXTRA_MESSAGE = "extra_message"

        /**
         * Starts the background transcription service for the given audio URI and duration.
         */
        fun startTranscription(
            context: Context,
            audioUri: Uri,
            audioDurationSeconds: Int,
            projectName: String? = null
        ) {
            val intent = Intent(context, VoiceoverTranscriptionService::class.java).apply {
                putExtra(EXTRA_AUDIO_URI, audioUri.toString())
                putExtra(EXTRA_AUDIO_DURATION_SECONDS, audioDurationSeconds)
                putExtra(EXTRA_PROJECT_NAME, projectName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        pipeline = VoiceoverTranscriptionPipeline(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val audioUriStr = intent.getStringExtra(EXTRA_AUDIO_URI)
        val durationSeconds = intent.getIntExtra(EXTRA_AUDIO_DURATION_SECONDS, 0)
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME)

        if (audioUriStr.isNullOrEmpty() || durationSeconds <= 0) {
            Log.e(TAG, "Invalid transcription intent arguments: uri=$audioUriStr, duration=$durationSeconds")
            stopSelf()
            return START_NOT_STICKY
        }

        val audioUri = Uri.parse(audioUriStr)
        startForeground(NOTIFICATION_ID, buildNotification(0, "Preparing transcription..."))

        serviceScope.launch {
            try {
                val result = pipeline.execute(
                    audioUri = audioUri,
                    audioDurationSeconds = durationSeconds,
                    projectName = projectName,
                    onProgress = { progress, statusText ->
                        updateNotification(progress, statusText)
                        broadcastProgress(progress, statusText)
                    }
                )

                when (result) {
                    is TranscriptionPipelineResult.Success -> {
                        Log.i(TAG, "Transcription succeeded: ${result.localSavedFile.absolutePath}")
                        showCompletionNotification("Transcription Complete", "Voiceover transcribed successfully")
                        broadcastSuccess(result.localSavedFile.absolutePath, result.text)
                    }
                    is TranscriptionPipelineResult.BlockedAuth -> {
                        Log.w(TAG, result.message)
                        showCompletionNotification("Transcription Blocked", "Authentication required")
                        broadcastBlockedAuth(result.message)
                    }
                    is TranscriptionPipelineResult.Error -> {
                        Log.e(TAG, "Transcription failed: ${result.message}")
                        showCompletionNotification("Transcription Failed", result.message)
                        broadcastFailure(result.message, result.code.code)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception in transcription service: ${e.message}", e)
                showCompletionNotification("Transcription Failed", e.message ?: "Unknown error")
                broadcastFailure(e.message ?: "Unknown error", ErrorCode.UNEXPECTED_CRASH.code)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun broadcastProgress(progress: Int, statusText: String) {
        val intent = Intent(ACTION_TRANSCRIPTION_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_STATUS_TEXT, statusText)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSuccess(filePath: String, text: String) {
        val intent = Intent(ACTION_TRANSCRIPTION_SUCCESS).apply {
            putExtra(EXTRA_TRANSCRIPTION_FILE_PATH, filePath)
            putExtra(EXTRA_TRANSCRIPTION_TEXT, text)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastBlockedAuth(message: String) {
        val intent = Intent(ACTION_TRANSCRIPTION_BLOCKED_AUTH).apply {
            putExtra(EXTRA_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastFailure(error: String, errorCode: String) {
        val intent = Intent(ACTION_TRANSCRIPTION_FAILURE).apply {
            putExtra(EXTRA_ERROR, error)
            putExtra(EXTRA_ERROR_CODE, errorCode)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voiceover Transcription",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of voiceover transcription via Whisper AI"
            }
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
            .setContentTitle("Voiceover Transcription")
            .setContentText(if (progress > 0) "$title ($progress%)" else title)
            .setSmallIcon(R.drawable.ic_mic_24)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Int, title: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(progress, title))
    }

    private fun showCompletionNotification(title: String, message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_mic_24)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }
}

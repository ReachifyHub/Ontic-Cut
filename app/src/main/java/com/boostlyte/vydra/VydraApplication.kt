package com.boostlyte.vydra

import android.app.Application
import android.content.Intent
import android.util.Log
import com.boostlyte.vydra.utils.ErrorCode

class VydraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        setupGlobalCrashHandler()
        com.boostlyte.vydra.utils.AppLogger.checkDiedMidway(this)
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("VydraCrash", "CRITICAL CRASH IN APPLICATION", throwable)

            // 1. Persist crash details to vydra_crash.log
            try {
                com.boostlyte.vydra.utils.AppLogger.logCrash(this, thread, throwable)
            } catch (e: Exception) {
                Log.e("VydraCrash", "Failed to log crash to file: ${e.message}")
            }

            // 2. Safe stack trace capped to 32KB to prevent TransactionTooLargeException
            val rawLog = Log.getStackTraceString(throwable)
            val safeLog = if (rawLog.length > 32768) {
                rawLog.take(32768) + "\n... [TRUNCATED - See vydra_crash.log for full details]"
            } else {
                rawLog
            }

            try {
                val intent = Intent(this, ErrorDisplayActivity::class.java).apply {
                    putExtra("ERROR_CODE", ErrorCode.UNEXPECTED_CRASH.code)
                    putExtra("ERROR_LOG", safeLog)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("VydraCrash", "Failed to start ErrorDisplayActivity: ${e.message}")
            }

            // Give the activity manager a brief window to start the intent, then delegate or exit
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {}

            defaultHandler?.uncaughtException(thread, throwable) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }
        }
    }
}

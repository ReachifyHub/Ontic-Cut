package com.boostlyte.vydra.utils

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.boostlyte.vydra.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Utility for checking and displaying in-app updates against the remote update.json manifest.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "vydra_prefs"
    private const val KEY_LAST_UPDATE_CHECK_TIME = "key_last_update_check_time"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class UpdateInfo(
        val latestVersionCode: Int,
        val latestVersionName: String,
        val changelog: List<String>,
        val apkUrl: String
    )

    /**
     * Checks for updates once daily on app start. Fails silently on network errors or if already up-to-date.
     */
    fun checkOnceDaily(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK_TIME, 0L)
        val now = System.currentTimeMillis()

        if (now - lastCheck < CHECK_INTERVAL_MS) {
            Log.d(TAG, "Skipping daily update check (checked within last 24h)")
            return
        }

        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_TIME, now).apply()
        checkForUpdates(activity, isManual = false)
    }

    /**
     * Performs an update check.
     * @param activity The calling activity to attach dialogs or toasts to.
     * @param isManual True if triggered by user tap ("Check for Updates"), false if background launch check.
     */
    fun checkForUpdates(activity: Activity, isManual: Boolean) {
        if (!AppUrls.isNetworkAvailable(activity)) {
            if (isManual) {
                Toast.makeText(
                    activity,
                    "Unable to check for updates. Please check your internet connection.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        executor.execute {
            try {
                val url = URL(AppUrls.UPDATE_MANIFEST_URL)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Vydra-Android/${BuildConfig.VERSION_NAME}")
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseBody = BufferedReader(InputStreamReader(connection.inputStream)).use {
                        it.readText()
                    }
                    val updateInfo = parseManifest(responseBody)

                    mainHandler.post {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            handleUpdateResult(activity, updateInfo, isManual)
                        }
                    }
                } else {
                    Log.w(TAG, "Update manifest returned HTTP $responseCode")
                    if (isManual) {
                        mainHandler.post {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                Toast.makeText(
                                    activity,
                                    "Unable to check for updates. Please try again later.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates: ${e.message}", e)
                if (isManual) {
                    mainHandler.post {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            Toast.makeText(
                                activity,
                                "Unable to check for updates. Please check your internet connection.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun parseManifest(jsonString: String): UpdateInfo? {
        return try {
            val json = JSONObject(jsonString)
            val latestCode = json.optInt("latestVersionCode", 0)
            val latestName = json.optString("latestVersionName", "")
            val apkUrl = json.optString("apkUrl", "")

            val changelogList = mutableListOf<String>()
            val changelogArray = json.optJSONArray("changelog")
            if (changelogArray != null) {
                for (i in 0 until changelogArray.length()) {
                    changelogList.add(changelogArray.optString(i))
                }
            }

            UpdateInfo(
                latestVersionCode = latestCode,
                latestVersionName = latestName,
                changelog = changelogList,
                apkUrl = apkUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing update manifest JSON", e)
            null
        }
    }

    private fun handleUpdateResult(activity: Activity, info: UpdateInfo?, isManual: Boolean) {
        if (info == null) {
            if (isManual) {
                Toast.makeText(
                    activity,
                    "Unable to check for updates. Please try again later.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        val currentCode = BuildConfig.VERSION_CODE
        Log.d(TAG, "Current versionCode: $currentCode, remote versionCode: ${info.latestVersionCode}")

        if (info.latestVersionCode > currentCode) {
            showUpdateDialog(activity, info)
        } else {
            if (isManual) {
                Toast.makeText(
                    activity,
                    "You are on the latest version of Vydra (${BuildConfig.VERSION_NAME}).",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val changelogFormatted = if (info.changelog.isNotEmpty()) {
            info.changelog.joinToString(separator = "\n") { "• $it" }
        } else {
            "• Performance improvements and bug fixes"
        }

        val message = "Version ${info.latestVersionName} is now available!\n\nWhat's new:\n$changelogFormatted"

        MaterialAlertDialogBuilder(activity)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Download Update") { _, _ ->
                if (info.apkUrl.isNotBlank()) {
                    AppUrls.openSafeUrl(activity, info.apkUrl)
                } else {
                    AppUrls.openSafeUrl(activity, AppUrls.UPDATE_MANIFEST_URL)
                }
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }
}

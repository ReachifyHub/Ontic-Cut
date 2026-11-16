package com.boostlyte.vydra.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.boostlyte.vydra.BuildConfig
import com.boostlyte.vydra.ErrorDisplayActivity
import com.boostlyte.vydra.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Utility for checking and displaying in-app updates against the remote update.json manifest.
 * Checks on every fresh app open when internet is available, and enforces mandatory updates
 * when minimumVersionCode exceeds the current app versionCode or matches latestVersionCode.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lifecycleRegistered = false

    @Volatile
    private var resumedActivityRef: WeakReference<Activity>? = null

    @Volatile
    private var mandatoryUpdateInfo: UpdateInfo? = null

    private var activeDialogRef: WeakReference<AlertDialog>? = null
    private var activeDialogActivityRef: WeakReference<Activity>? = null
    private var activeDialogMandatory: Boolean = false
    private var activeDialogVersionCode: Int = -1

    enum class UpdateStatus {
        NO_UPDATE,
        OPTIONAL_UPDATE,
        MANDATORY_UPDATE
    }

    data class UpdateInfo(
        val latestVersionCode: Int,
        val latestVersionName: String,
        val changelog: List<String>,
        val apkUrl: String,
        val minimumVersionCode: Int = 0
    )

    /**
     * Registers Application lifecycle callbacks so a mandatory update dialog cannot be bypassed
     * by navigating to another Activity, rotating the device, or returning from the download browser.
     */
    fun registerLifecycleObserver(application: Application) {
        if (lifecycleRegistered) return
        synchronized(this) {
            if (lifecycleRegistered) return
            lifecycleRegistered = true
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityResumed(activity: Activity) {
                    resumedActivityRef = WeakReference(activity)
                    if (activity !is ErrorDisplayActivity) {
                        showMandatoryUpdateDialogIfNeeded(activity)
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumedActivityRef?.get() === activity) {
                        resumedActivityRef = null
                    }
                }

                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {
                    if (activeDialogActivityRef?.get() === activity) {
                        dismissActiveDialogSafely()
                    }
                }
            })
        }
    }

    /**
     * Checks for updates on every fresh app open when internet is available.
     * Fails silently on network errors or when already up to date.
     */
    fun checkOnAppOpen(activity: Activity) {
        checkForUpdates(activity, isManual = false)
    }

    /**
     * Performs an update check against [AppUrls.UPDATE_MANIFEST_URL].
     * @param activity The calling activity to attach dialogs or toasts to.
     * @param isManual True if triggered by user tap ("Check for Updates"), false on automatic app open check.
     */
    fun checkForUpdates(activity: Activity, isManual: Boolean) {
        if (!AppUrls.isNetworkAvailable(activity)) {
            if (isManual) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_toast_no_internet),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(AppUrls.UPDATE_MANIFEST_URL)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
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
                        val targetActivity = resolveUiActivity(activity)
                        if (targetActivity != null) {
                            handleUpdateResult(targetActivity, updateInfo, isManual)
                        } else if (updateInfo != null && evaluateUpdate(updateInfo, BuildConfig.VERSION_CODE) == UpdateStatus.MANDATORY_UPDATE) {
                            // App moved to background while request was in flight; enforce on next resume.
                            mandatoryUpdateInfo = updateInfo
                        }
                    }
                } else {
                    Log.w(TAG, "Update manifest returned HTTP $responseCode")
                    if (isManual) {
                        mainHandler.post {
                            resolveUiActivity(activity)?.let { target ->
                                Toast.makeText(
                                    target,
                                    target.getString(R.string.update_toast_try_later),
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
                        resolveUiActivity(activity)?.let { target ->
                            Toast.makeText(
                                target,
                                target.getString(R.string.update_toast_no_internet),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Re-displays the mandatory update blocking dialog on [activity] if a mandatory update
     * has been flagged for the current app version.
     */
    fun showMandatoryUpdateDialogIfNeeded(activity: Activity, currentVersionCode: Int = BuildConfig.VERSION_CODE) {
        val info = mandatoryUpdateInfo ?: return
        if (evaluateUpdate(info, currentVersionCode) != UpdateStatus.MANDATORY_UPDATE) {
            mandatoryUpdateInfo = null
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        showUpdateDialog(activity, info, isMandatory = true)
    }

    internal fun parseManifest(jsonString: String): UpdateInfo? {
        return try {
            val trimmed = jsonString.trim().removePrefix("\uFEFF")
            if (trimmed.isEmpty()) return null
            val json = JsonParser.parseString(trimmed).asJsonObject
            val latestCode = json.optIntCompat("latestVersionCode", defaultValue = 0)
            val latestName = json.optStringCompat("latestVersionName", defaultValue = "")
            val apkUrl = json.optStringCompat("apkUrl", defaultValue = "")
            val minimumCode = json.optIntCompat(
                "minimumVersionCode",
                "minVersionCode",
                "minimum_version_code",
                "min_version_code",
                defaultValue = 0
            )

            val changelogList = mutableListOf<String>()
            val changelogElem = json.get("changelog")
            if (changelogElem != null && changelogElem.isJsonArray) {
                for (item in changelogElem.asJsonArray) {
                    if (!item.isJsonNull && item.isJsonPrimitive) {
                        val text = item.asString
                        if (text.isNotBlank()) {
                            changelogList.add(text)
                        }
                    }
                }
            }

            UpdateInfo(
                latestVersionCode = latestCode,
                latestVersionName = latestName,
                changelog = changelogList,
                apkUrl = apkUrl,
                minimumVersionCode = minimumCode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing update manifest JSON", e)
            null
        }
    }

    internal fun evaluateUpdate(info: UpdateInfo, currentVersionCode: Int = BuildConfig.VERSION_CODE): UpdateStatus {
        val hasNewUpdate = info.latestVersionCode > currentVersionCode
        if (!hasNewUpdate) {
            return UpdateStatus.NO_UPDATE
        }

        val isMandatory = info.minimumVersionCode > 0 &&
            (info.minimumVersionCode > currentVersionCode || info.minimumVersionCode >= info.latestVersionCode)

        return if (isMandatory) {
            UpdateStatus.MANDATORY_UPDATE
        } else {
            UpdateStatus.OPTIONAL_UPDATE
        }
    }

    private fun handleUpdateResult(activity: Activity, info: UpdateInfo?, isManual: Boolean) {
        if (info == null) {
            if (isManual) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_toast_try_later),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        val currentCode = BuildConfig.VERSION_CODE
        val status = evaluateUpdate(info, currentCode)
        Log.d(
            TAG,
            "Current versionCode: $currentCode, latestVersionCode: ${info.latestVersionCode}, " +
                "minimumVersionCode: ${info.minimumVersionCode}, status: $status"
        )

        when (status) {
            UpdateStatus.MANDATORY_UPDATE -> {
                mandatoryUpdateInfo = info
                showUpdateDialog(activity, info, isMandatory = true)
            }
            UpdateStatus.OPTIONAL_UPDATE -> {
                mandatoryUpdateInfo = null
                showUpdateDialog(activity, info, isMandatory = false)
            }
            UpdateStatus.NO_UPDATE -> {
                mandatoryUpdateInfo = null
                if (isManual) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_toast_latest_version, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo, isMandatory: Boolean) {
        val existingDialog = activeDialogRef?.get()
        val existingOwner = activeDialogActivityRef?.get()
        if (existingDialog != null && existingDialog.isShowing && existingOwner === activity) {
            if (activeDialogMandatory == isMandatory && activeDialogVersionCode == info.latestVersionCode) {
                return
            }
        }
        dismissActiveDialogSafely()

        val changelogFormatted = if (info.changelog.isNotEmpty()) {
            info.changelog.joinToString(separator = "\n") { "• $it" }
        } else {
            "• ${activity.getString(R.string.update_default_changelog)}"
        }

        val versionLabel = info.latestVersionName.ifBlank { "v${info.latestVersionCode}" }
        val title = if (isMandatory) {
            activity.getString(R.string.update_required_title)
        } else {
            activity.getString(R.string.update_available_title)
        }
        val message = if (isMandatory) {
            activity.getString(R.string.update_mandatory_message, versionLabel, changelogFormatted)
        } else {
            activity.getString(R.string.update_optional_message, versionLabel, changelogFormatted)
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)

        if (isMandatory) {
            builder
                .setPositiveButton(R.string.update_btn_update_now, null)
                .setNegativeButton(R.string.update_btn_exit_app) { _, _ ->
                    dismissActiveDialogSafely()
                    activity.finishAffinity()
                }
                .setCancelable(false)
        } else {
            builder
                .setPositiveButton(R.string.update_btn_download) { _, _ ->
                    openDownloadUrl(activity, info)
                }
                .setNegativeButton(R.string.update_btn_later, null)
                .setCancelable(true)
        }

        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(!isMandatory)
        dialog.setOnDismissListener {
            if (activeDialogRef?.get() === dialog) {
                activeDialogRef = null
                activeDialogActivityRef = null
            }
        }
        dialog.show()

        if (isMandatory) {
            // Keep the mandatory dialog visible even after launching the external download URL
            // so returning without updating still prevents app usage.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                openDownloadUrl(activity, info)
            }
        }

        activeDialogRef = WeakReference(dialog)
        activeDialogActivityRef = WeakReference(activity)
        activeDialogMandatory = isMandatory
        activeDialogVersionCode = info.latestVersionCode
    }

    private fun openDownloadUrl(activity: Activity, info: UpdateInfo) {
        val targetUrl = if (info.apkUrl.isNotBlank()) info.apkUrl else AppUrls.UPDATE_MANIFEST_URL
        AppUrls.openSafeUrl(activity, targetUrl)
    }

    private fun resolveUiActivity(fallback: Activity): Activity? {
        val resumed = resumedActivityRef?.get()
        if (resumed != null && !resumed.isFinishing && !resumed.isDestroyed && resumed !is ErrorDisplayActivity) {
            return resumed
        }
        if (!fallback.isFinishing && !fallback.isDestroyed) {
            return fallback
        }
        return null
    }

    private fun dismissActiveDialogSafely() {
        try {
            val dialog = activeDialogRef?.get()
            if (dialog != null && dialog.isShowing) {
                dialog.dismiss()
            }
        } catch (_: Exception) {
        } finally {
            activeDialogRef = null
            activeDialogActivityRef = null
        }
    }

    private fun JsonObject.optIntCompat(vararg keys: String, defaultValue: Int = 0): Int {
        for (key in keys) {
            val elem = get(key) ?: continue
            if (elem.isJsonNull || !elem.isJsonPrimitive) continue
            val prim = elem.asJsonPrimitive
            if (prim.isNumber) {
                try {
                    return prim.asInt
                } catch (_: Exception) {
                }
            } else if (prim.isString) {
                prim.asString.trim().toIntOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    private fun JsonObject.optStringCompat(key: String, defaultValue: String = ""): String {
        val elem = get(key) ?: return defaultValue
        if (elem.isJsonNull || !elem.isJsonPrimitive) return defaultValue
        return elem.asString ?: defaultValue
    }
}

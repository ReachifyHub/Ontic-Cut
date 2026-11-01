package com.boostlyte.vydra.utils

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URLEncoder

/**
 * Central URLs configuration for external community, support, legal, updates, and credit purchase links.
 */
object AppUrls {

    /** Official Privacy Policy URL */
    const val PRIVACY_POLICY_URL = "https://vydra-editor.netlify.app/privacy"

    /** Official Terms of Service URL */
    const val TERMS_OF_SERVICE_URL = "https://vydra-editor.netlify.app/terms"

    /** Official Bug reporting URL */
    const val BUG_REPORT_URL = "https://vydra-editor.netlify.app/bug-report"

    /** In-app update manifest endpoint */
    const val UPDATE_MANIFEST_URL = "https://vydra-editor.netlify.app/update.json"

    /** Official Vydra WhatsApp Community group link */
    const val WHATSAPP_COMMUNITY_URL = "https://chat.whatsapp.com/L5LJK0HMcbLI9zS3CX2S38"

    /** Direct WhatsApp purchase link for manual credit top-up */
    const val WHATSAPP_PURCHASE_URL = "https://wa.me/2348157683854"

    /**
     * Checks if the device has an active internet connection.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens a web URL safely using Chrome Custom Tabs with fallback to system browser.
     * Validates network connectivity and handles errors gracefully with user-friendly toasts.
     */
    fun openSafeUrl(context: Context, url: String) {
        if (url.isBlank() || url.contains("REPLACE_ME")) {
            Toast.makeText(context, "Link not configured yet", Toast.LENGTH_SHORT).show()
            return
        }

        // Verify internet connection
        if (!isNetworkAvailable(context)) {
            Toast.makeText(
                context,
                "No internet connection. Please check your network and try again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            Toast.makeText(context, "Invalid link format", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Attempt Chrome Custom Tabs first
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            // Fallback to standard ACTION_VIEW browser intent
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (fallbackEx: Exception) {
                Toast.makeText(context, "Unable to open link in browser", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Builds the full WhatsApp purchase URL with prefilled text message.
     */
    fun buildWhatsAppPurchaseUrl(userEmail: String? = null): String {
        val message = if (!userEmail.isNullOrBlank()) {
            "Hello, I would like to purchase transcription credits for Vydra. My account email is: $userEmail"
        } else {
            "Hello, I would like to purchase transcription credits for Vydra."
        }

        val encodedMessage = try {
            URLEncoder.encode(message, "UTF-8")
        } catch (e: Exception) {
            message.replace(" ", "%20")
        }

        return "$WHATSAPP_PURCHASE_URL?text=$encodedMessage"
    }

    /**
     * Opens WhatsApp to purchase transcription credits for AI Auto Assemble.
     * Prefills user email in the message if provided to streamline manual account crediting.
     */
    fun openWhatsAppPurchase(context: Context, userEmail: String? = null) {
        val fullUrl = buildWhatsAppPurchaseUrl(userEmail)

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to base WhatsApp URL if intent failed
            openSafeUrl(context, WHATSAPP_PURCHASE_URL)
        }
    }
}

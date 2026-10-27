package com.boostlyte.vydra.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

/**
 * Central URLs configuration for external community, support, and manual credit purchase links.
 */
object AppUrls {

    /** Official Vydra WhatsApp Community group link */
    const val WHATSAPP_COMMUNITY_URL = "https://chat.whatsapp.com/L5LJK0HMcbLI9zS3CX2S38"

    /** Direct WhatsApp purchase link for manual credit top-up */
    const val WHATSAPP_PURCHASE_URL = "https://wa.me/2348157683854"

    /** Bug reporting URL */
    const val BUG_REPORT_URL = "https://github.com/ReachifyHub/Ontic-Cut/issues"

    /**
     * Opens a generic web URL safely with error handling.
     */
    fun openSafeUrl(context: Context, url: String) {
        if (url.isBlank() || url.contains("REPLACE_ME")) {
            Toast.makeText(context, "Link not configured yet", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
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

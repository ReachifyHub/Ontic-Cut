package com.boostlyte.vydra.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object AppUrls {
    const val WHATSAPP_COMMUNITY_URL = "https://chat.whatsapp.com/REPLACE_ME"
    const val PRIVACY_POLICY_URL = "https://example.com/privacy-policy-REPLACE_ME"
    const val TERMS_OF_USE_URL = "https://example.com/terms-REPLACE_ME"
    const val BUG_REPORT_URL = "https://example.com/bug-report-REPLACE_ME"

    fun openSafeUrl(context: Context, url: String) {
        if (url.contains("REPLACE_ME")) {
            Toast.makeText(context, "Link not configured yet", Toast.LENGTH_SHORT).show()
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

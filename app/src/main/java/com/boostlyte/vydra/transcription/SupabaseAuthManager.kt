package com.boostlyte.vydra.transcription

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages Supabase session JWT storage and retrieval.
 *
 * NOTE: Login and sign-up UI screens are pending in a future phase.
 * In accordance with Phase 3 specifications:
 * - Anonymous sign-in, fake tokens, or authentication bypasses are STRICTLY PROHIBITED.
 * - If no valid user JWT is stored, [getCurrentUserJwt] returns null.
 * - Callers must check [hasValidJwt] and abort gracefully if false.
 */
object SupabaseAuthManager {

    private const val PREFS_NAME = "supabase_auth_session"
    private const val KEY_USER_JWT = "supabase_user_jwt"
    private const val KEY_USER_ID = "supabase_user_id"
    private const val KEY_USER_EMAIL = "supabase_user_email"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the current authenticated Supabase user's JWT access token,
     * or null if the user is not authenticated.
     */
    fun getCurrentUserJwt(context: Context): String? {
        val jwt = getPrefs(context).getString(KEY_USER_JWT, null)?.trim()
        return if (jwt.isNullOrEmpty()) null else jwt
    }

    /**
     * Checks if a valid non-empty JWT is available.
     */
    fun hasValidJwt(context: Context): Boolean {
        return !getCurrentUserJwt(context).isNullOrEmpty()
    }

    /**
     * Stores a Supabase session JWT once authentication is completed.
     * Accessible for testing or when the auth flow is wired.
     */
    fun setCurrentUserJwt(context: Context, jwt: String?, userId: String? = null, email: String? = null) {
        getPrefs(context).edit().apply {
            if (jwt.isNullOrEmpty()) {
                remove(KEY_USER_JWT)
                remove(KEY_USER_ID)
                remove(KEY_USER_EMAIL)
            } else {
                putString(KEY_USER_JWT, jwt.trim())
                putString(KEY_USER_ID, userId)
                putString(KEY_USER_EMAIL, email)
            }
            apply()
        }
    }

    /**
     * Clears any active Supabase credentials.
     */
    fun clearSession(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}

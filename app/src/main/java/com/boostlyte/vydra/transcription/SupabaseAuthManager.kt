package com.boostlyte.vydra.transcription

import android.content.Context
import android.util.Log
import com.boostlyte.vydra.auth.AuthResult
import com.boostlyte.vydra.auth.AuthSession
import com.boostlyte.vydra.auth.AuthUser
import com.boostlyte.vydra.auth.EncryptedTokenStorage
import com.boostlyte.vydra.auth.SupabaseAuthClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages Supabase session JWT storage, validation, token refresh, and authentication state.
 *
 * Implements real email/password authentication using Supabase GoTrue REST API.
 * Tokens are securely encrypted on disk using [EncryptedTokenStorage] (Android KeyStore AES-256-GCM).
 * Access tokens are automatically refreshed when within 60 seconds of expiration.
 *
 * Account usage remains completely optional: local video editing, project management, and
 * pre-transcribed timeline assembly function 100% offline without requiring an account.
 */
object SupabaseAuthManager {

    private const val TAG = "SupabaseAuthManager"

    // Proactive refresh buffer (refresh if less than 60 seconds left)
    private const val EXPIRATION_BUFFER_MS = 60_000L

    // In-memory reference for auth client (can be swapped in tests)
    internal var authClient: SupabaseAuthClient = SupabaseAuthClient()

    // Thread-safe listeners for session state changes
    private val authStateListeners = CopyOnWriteArrayList<(isLoggedIn: Boolean, email: String?) -> Unit>()

    @Volatile
    private var tokenStorageInstance: EncryptedTokenStorage? = null

    internal fun resetForTesting() {
        tokenStorageInstance = null
        authStateListeners.clear()
    }

    private fun getStorage(context: Context): EncryptedTokenStorage {
        return tokenStorageInstance ?: synchronized(this) {
            tokenStorageInstance ?: EncryptedTokenStorage(context.applicationContext).also {
                tokenStorageInstance = it
                migrateLegacyPrefsIfNeeded(context, it)
            }
        }
    }

    /**
     * Migrates legacy plain SharedPreferences from Phase 3 to [EncryptedTokenStorage]
     * if any exist, and removes the unencrypted keys.
     */
    private fun migrateLegacyPrefsIfNeeded(context: Context, storage: EncryptedTokenStorage) {
        try {
            val legacyPrefs = context.getSharedPreferences("supabase_auth_session", Context.MODE_PRIVATE)
            val legacyJwt = legacyPrefs.getString("supabase_user_jwt", null)
            val legacyUserId = legacyPrefs.getString("supabase_user_id", "") ?: ""
            val legacyEmail = legacyPrefs.getString("supabase_user_email", "") ?: ""

            if (!legacyJwt.isNullOrBlank() && !storage.hasSession()) {
                val dummySession = AuthSession(
                    accessToken = legacyJwt,
                    refreshToken = "",
                    expiresInSeconds = 3600L,
                    expiresAtTimestampMs = System.currentTimeMillis() + (3600L * 1000L),
                    user = AuthUser(id = legacyUserId, email = legacyEmail)
                )
                storage.saveSession(dummySession)
            }
            if (legacyPrefs.contains("supabase_user_jwt")) {
                legacyPrefs.edit().clear().apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy preferences migration notice: ${e.message}")
        }
    }

    /**
     * Synchronous check if a valid session exists.
     * Compatible with Phase 3 & 4 callers.
     */
    fun hasValidJwt(context: Context): Boolean {
        val session = getStorage(context).getSession()
        return session != null && session.accessToken.isNotBlank()
    }

    /**
     * Checks if user is currently logged in.
     */
    fun isLoggedIn(context: Context): Boolean {
        return hasValidJwt(context)
    }

    /**
     * Returns the current access token synchronously without refreshing.
     * For network requests, prefer [getValidAccessToken].
     */
    fun getCurrentUserJwt(context: Context): String? {
        val session = getStorage(context).getSession()
        return session?.accessToken?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns the email address of the currently authenticated user, or null if signed out.
     */
    fun getCurrentUserEmail(context: Context): String? {
        return getStorage(context).getUserEmail()
    }

    /**
     * Returns the ID of the currently authenticated user, or null if signed out.
     */
    fun getCurrentUserId(context: Context): String? {
        return getStorage(context).getUserId()
    }

    /**
     * Retrieves a valid access token. If the stored token is expired or within 60 seconds
     * of expiring (or if [forceRefresh] is true), this method attempts a token refresh
     * with Supabase using the stored refresh token.
     *
     * @return Fresh access token, or null if not authenticated or refresh failed.
     */
    suspend fun getValidAccessToken(context: Context, forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        val storage = getStorage(context)
        val session = storage.getSession() ?: return@withContext null

        val now = System.currentTimeMillis()
        val isNearExpiry = (session.expiresAtTimestampMs - now) < EXPIRATION_BUFFER_MS

        if (!forceRefresh && !isNearExpiry && session.accessToken.isNotBlank()) {
            return@withContext session.accessToken
        }

        val refreshToken = session.refreshToken
        if (refreshToken.isBlank()) {
            // No refresh token available, return access token if not expired
            return@withContext if (session.expiresAtTimestampMs > now) session.accessToken else null
        }

        Log.i(TAG, "Access token near expiration or refresh requested. Initiating token refresh...")
        val refreshResult = authClient.refreshToken(refreshToken)

        when (refreshResult) {
            is AuthResult.Success -> {
                val newSession = refreshResult.session
                if (newSession != null) {
                    storage.saveSession(newSession)
                    notifyAuthStateChanged(true, newSession.user.email)
                    newSession.accessToken
                } else {
                    session.accessToken
                }
            }
            else -> {
                Log.w(TAG, "Token refresh failed: ${(refreshResult as? AuthResult.Error)?.message}")
                if (forceRefresh || session.expiresAtTimestampMs <= now) {
                    // Session is expired and could not be refreshed
                    storage.clear()
                    notifyAuthStateChanged(false, null)
                    null
                } else {
                    session.accessToken
                }
            }
        }
    }

    /**
     * Authenticates user with email and password.
     */
    suspend fun signIn(context: Context, email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val result = authClient.signIn(email, password)
        if (result is AuthResult.Success && result.session != null) {
            getStorage(context).saveSession(result.session)
            notifyAuthStateChanged(true, result.session.user.email)
        }
        result
    }

    /**
     * Creates a new user account with email and password.
     */
    suspend fun signUp(context: Context, email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val result = authClient.signUp(email, password)
        if (result is AuthResult.Success && result.session != null) {
            getStorage(context).saveSession(result.session)
            notifyAuthStateChanged(true, result.session.user.email)
        }
        result
    }

    /**
     * Signs out the user, invalidates the session on the server, and purges local storage.
     */
    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        val storage = getStorage(context)
        val session = storage.getSession()
        val accessToken = session?.accessToken

        // Attempt remote invalidation
        authClient.signOut(accessToken)

        // Clear local secure storage
        storage.clear()
        notifyAuthStateChanged(false, null)
    }

    /**
     * Resends confirmation email for unverified sign-ups.
     */
    suspend fun resendConfirmationEmail(email: String): AuthResult {
        return authClient.resendConfirmationEmail(email)
    }

    /**
     * Sends password recovery email.
     */
    suspend fun sendPasswordReset(email: String): AuthResult {
        return authClient.recoverPassword(email)
    }

    /**
     * Silently validates and refreshes session on app start if needed.
     */
    suspend fun restoreSessionOnAppStart(context: Context) = withContext(Dispatchers.IO) {
        if (hasValidJwt(context)) {
            getValidAccessToken(context, forceRefresh = false)
        }
    }

    /**
     * Compatible helper for testing and legacy invocations.
     */
    fun setCurrentUserJwt(context: Context, jwt: String?, userId: String? = null, email: String? = null) {
        val storage = getStorage(context)
        if (jwt.isNullOrBlank()) {
            storage.clear()
            notifyAuthStateChanged(false, null)
        } else {
            val session = AuthSession(
                accessToken = jwt.trim(),
                refreshToken = "",
                expiresInSeconds = 3600L,
                expiresAtTimestampMs = System.currentTimeMillis() + 3_600_000L,
                user = AuthUser(id = userId ?: "", email = email ?: "")
            )
            storage.saveSession(session)
            notifyAuthStateChanged(true, email)
        }
    }

    /**
     * Clears all session data.
     */
    fun clearSession(context: Context) {
        getStorage(context).clear()
        notifyAuthStateChanged(false, null)
    }

    /**
     * Registers a listener to observe authentication state transitions.
     */
    fun addAuthStateListener(listener: (isLoggedIn: Boolean, email: String?) -> Unit) {
        if (!authStateListeners.contains(listener)) {
            authStateListeners.add(listener)
        }
    }

    /**
     * Removes an authentication state listener.
     */
    fun removeAuthStateListener(listener: (isLoggedIn: Boolean, email: String?) -> Unit) {
        authStateListeners.remove(listener)
    }

    private fun notifyAuthStateChanged(isLoggedIn: Boolean, email: String?) {
        for (listener in authStateListeners) {
            try {
                listener.invoke(isLoggedIn, email)
            } catch (e: Exception) {
                Log.w(TAG, "Auth state listener error: ${e.message}")
            }
        }
    }
}

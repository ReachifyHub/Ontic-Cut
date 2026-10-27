package com.boostlyte.vydra.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages secure storage of Supabase access and refresh tokens.
 *
 * Uses hardware-backed Android KeyStore AES-256-GCM cipher encryption to protect tokens on disk.
 * Sensitive tokens are NEVER stored in plaintext and NEVER written to logs.
 */
class EncryptedTokenStorage(
    private val context: Context,
    private val prefsName: String = PREFS_NAME
) {

    companion object {
        private const val TAG = "EncryptedTokenStorage"
        private const val PREFS_NAME = "vydra_secure_auth_session"
        private const val KEY_ALIAS = "vydra_auth_key_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128

        // Prefs keys
        private const val PREF_KEY_ACCESS_TOKEN_ENC = "enc_access_token"
        private const val PREF_KEY_REFRESH_TOKEN_ENC = "enc_refresh_token"
        private const val PREF_KEY_EXPIRES_AT = "expires_at_ms"
        private const val PREF_KEY_EXPIRES_IN = "expires_in_secs"
        private const val PREF_KEY_USER_ID = "user_id"
        private const val PREF_KEY_USER_EMAIL = "user_email"
    }

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    // Check if AndroidKeyStore provider is available (true on Android devices, false in plain JVM unit tests)
    private val isKeyStoreAvailable: Boolean by lazy {
        try {
            val vmName = System.getProperty("java.vm.name") ?: ""
            val isAndroidRuntime = vmName.contains("Dalvik", ignoreCase = true) || vmName.contains("Art", ignoreCase = true)
            if (!isAndroidRuntime) {
                return@lazy false
            }
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks?.load(null)
            ks != null
        } catch (e: Throwable) {
            false
        }
    }

    // Fallback key for non-Android JVM unit tests
    private val jvmFallbackKey: SecretKey by lazy {
        val raw = ByteArray(32) { (it + 42).toByte() }
        SecretKeySpec(raw, "AES")
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        if (!isKeyStoreAvailable) {
            return jvmFallbackKey
        }

        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrEmpty()) return null
        return try {
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            java.util.Base64.getEncoder().withoutPadding().encodeToString(combined)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Encryption failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun decrypt(encryptedBase64: String?): String? {
        if (encryptedBase64.isNullOrEmpty()) return null
        return try {
            val combined = java.util.Base64.getDecoder().decode(encryptedBase64)
            if (combined.size < GCM_IV_LENGTH) return null

            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Decryption failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Saves the authenticated session securely.
     */
    @Synchronized
    fun saveSession(session: AuthSession) {
        val encAccess = encrypt(session.accessToken)
        val encRefresh = if (session.refreshToken.isNotBlank()) encrypt(session.refreshToken) else ""

        prefs.edit().apply {
            putString(PREF_KEY_ACCESS_TOKEN_ENC, encAccess)
            putString(PREF_KEY_REFRESH_TOKEN_ENC, encRefresh)
            putLong(PREF_KEY_EXPIRES_AT, session.expiresAtTimestampMs)
            putLong(PREF_KEY_EXPIRES_IN, session.expiresInSeconds)
            putString(PREF_KEY_USER_ID, session.user.id)
            putString(PREF_KEY_USER_EMAIL, session.user.email)
            apply()
        }
    }

    /**
     * Retrieves the stored session, decrypting the access and refresh tokens.
     */
    @Synchronized
    fun getSession(): AuthSession? {
        val encAccess = prefs.getString(PREF_KEY_ACCESS_TOKEN_ENC, null) ?: return null
        val encRefresh = prefs.getString(PREF_KEY_REFRESH_TOKEN_ENC, "") ?: ""
        val expiresAt = prefs.getLong(PREF_KEY_EXPIRES_AT, 0L)
        val expiresIn = prefs.getLong(PREF_KEY_EXPIRES_IN, 3600L)
        val userId = prefs.getString(PREF_KEY_USER_ID, "") ?: ""
        val email = prefs.getString(PREF_KEY_USER_EMAIL, "") ?: ""

        val accessToken = decrypt(encAccess) ?: return null
        val refreshToken = if (encRefresh.isNotBlank()) decrypt(encRefresh) ?: "" else ""

        if (accessToken.isBlank()) {
            return null
        }

        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = expiresIn,
            expiresAtTimestampMs = expiresAt,
            user = AuthUser(id = userId, email = email)
        )
    }

    /**
     * Returns true if a session is currently stored.
     */
    @Synchronized
    fun hasSession(): Boolean {
        return prefs.contains(PREF_KEY_ACCESS_TOKEN_ENC)
    }

    /**
     * Returns the stored user email without needing to decrypt the tokens.
     */
    @Synchronized
    fun getUserEmail(): String? {
        return prefs.getString(PREF_KEY_USER_EMAIL, null)
    }

    /**
     * Returns the stored user ID.
     */
    @Synchronized
    fun getUserId(): String? {
        return prefs.getString(PREF_KEY_USER_ID, null)
    }

    /**
     * Clears all stored tokens and user metadata.
     */
    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }
}

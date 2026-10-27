package com.boostlyte.vydra.auth

import com.boostlyte.vydra.transcription.SupabaseAuthManager
import com.boostlyte.vydra.transcription.SupabaseConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuthManagerTest {

    private lateinit var testContext: TestContext

    @Before
    fun setUp() {
        testContext = TestContext()
        SupabaseAuthManager.resetForTesting()
        SupabaseAuthManager.clearSession(testContext)
    }

    @Test
    fun testEncryptedTokenStorage_encryptsAndDecryptsTokens() {
        val storage = EncryptedTokenStorage(testContext)

        val originalAccessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dummy_access_token"
        val originalRefreshToken = "dummy_refresh_token_abc_123"
        val expiresAt = System.currentTimeMillis() + 3600_000L

        val session = AuthSession(
            accessToken = originalAccessToken,
            refreshToken = originalRefreshToken,
            expiresInSeconds = 3600,
            expiresAtTimestampMs = expiresAt,
            user = AuthUser(id = "user-1234", email = "creator@boostlyte.com")
        )

        storage.saveSession(session)
        assertTrue(storage.hasSession())
        assertEquals("creator@boostlyte.com", storage.getUserEmail())
        assertEquals("user-1234", storage.getUserId())

        // Verify underlying storage is encrypted and does NOT contain plaintext token
        val rawPrefs = testContext.getSharedPreferences("vydra_secure_auth_session", 0)
        val rawEncAccess = rawPrefs.getString("enc_access_token", null)
        assertNotNull(rawEncAccess)
        assertNotEquals(originalAccessToken, rawEncAccess)
        assertFalse(rawEncAccess!!.contains("eyJhbGciOiJIUzI1Ni"))

        // Decrypt and verify
        val retrieved = storage.getSession()
        assertNotNull(retrieved)
        assertEquals(originalAccessToken, retrieved!!.accessToken)
        assertEquals(originalRefreshToken, retrieved.refreshToken)
        assertEquals("creator@boostlyte.com", retrieved.user.email)
        assertEquals("user-1234", retrieved.user.id)

        // Clear and verify
        storage.clear()
        assertFalse(storage.hasSession())
        assertNull(storage.getSession())
        assertNull(storage.getUserEmail())
    }

    @Test
    fun testSupabaseAuthManager_stateListeners() {
        var observedLoginState: Boolean? = null
        var observedEmail: String? = null

        val listener: (Boolean, String?) -> Unit = { loggedIn, email ->
            observedLoginState = loggedIn
            observedEmail = email
        }

        SupabaseAuthManager.addAuthStateListener(listener)

        SupabaseAuthManager.setCurrentUserJwt(
            context = testContext,
            jwt = "valid_token_xyz",
            userId = "usr-1",
            email = "user@test.com"
        )

        assertTrue(SupabaseAuthManager.isLoggedIn(testContext))
        assertEquals(true, observedLoginState)
        assertEquals("user@test.com", observedEmail)
        assertEquals("user@test.com", SupabaseAuthManager.getCurrentUserEmail(testContext))

        SupabaseAuthManager.clearSession(testContext)
        assertFalse(SupabaseAuthManager.isLoggedIn(testContext))
        assertEquals(false, observedLoginState)
        assertNull(observedEmail)

        SupabaseAuthManager.removeAuthStateListener(listener)
    }

    @Test
    fun testSupabaseAuthManager_autoTokenRefreshNearExpiry() = runBlocking {
        val expiredSession = AuthSession(
            accessToken = "initial_expired_token",
            refreshToken = "refresh_token_valid",
            expiresInSeconds = 3600,
            // Expiring in 10 seconds (< 60 second buffer)
            expiresAtTimestampMs = System.currentTimeMillis() + 10_000L,
            user = AuthUser("uid-99", "refresh_user@test.com")
        )

        val storage = EncryptedTokenStorage(testContext)
        storage.saveSession(expiredSession)

        // Mock auth client to return new session on refresh
        val mockClient = object : SupabaseAuthClient() {
            override suspend fun refreshToken(refreshToken: String): AuthResult {
                assertEquals("refresh_token_valid", refreshToken)
                val newSession = AuthSession(
                    accessToken = "newly_refreshed_access_token",
                    refreshToken = "next_refresh_token",
                    expiresInSeconds = 3600,
                    expiresAtTimestampMs = System.currentTimeMillis() + 3600_000L,
                    user = AuthUser("uid-99", "refresh_user@test.com")
                )
                return AuthResult.Success(user = newSession.user, session = newSession)
            }
        }

        val originalClient = SupabaseAuthManager.authClient
        SupabaseAuthManager.authClient = mockClient

        try {
            val token = SupabaseAuthManager.getValidAccessToken(testContext, forceRefresh = false)
            assertEquals("newly_refreshed_access_token", token)

            // Verify refreshed token was persisted
            val savedSession = storage.getSession()
            assertNotNull(savedSession)
            assertEquals("newly_refreshed_access_token", savedSession!!.accessToken)
            assertEquals("next_refresh_token", savedSession.refreshToken)
        } finally {
            SupabaseAuthManager.authClient = originalClient
        }
    }

    @Test
    fun testSupabaseConfig_authUrls() {
        assertEquals("https://rqnbumhburadomrdmzhp.supabase.co", SupabaseConfig.SUPABASE_URL)
        assertEquals("sb_publishable_TuNGrwbWqxVwv8G6t6GxdA_H89LLk8G", SupabaseConfig.ANON_KEY)
    }

    @Test
    fun testOfflineEditing_optionalAuthPreserved() {
        // Confirm that when not logged in, local session is null
        assertFalse(SupabaseAuthManager.isLoggedIn(testContext))
        assertNull(SupabaseAuthManager.getCurrentUserJwt(testContext))

        // All offline actions remain accessible without throwing
        val userEmail = SupabaseAuthManager.getCurrentUserEmail(testContext)
        assertNull(userEmail)
    }
}

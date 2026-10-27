package com.boostlyte.vydra.auth

/**
 * User model representing an authenticated or created Supabase user.
 */
data class AuthUser(
    val id: String,
    val email: String,
    val isEmailConfirmed: Boolean = true
)

/**
 * Session model holding access and refresh tokens with expiration metadata.
 */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val expiresAtTimestampMs: Long,
    val user: AuthUser
)

/**
 * Result sealed class for Supabase Auth operations.
 */
sealed class AuthResult {
    /**
     * Authentication succeeded.
     * [session] will be non-null for interactive logins and token refreshes.
     * [session] may be null on signup if email confirmation is required by Supabase backend.
     */
    data class Success(
        val user: AuthUser,
        val session: AuthSession?
    ) : AuthResult()

    /**
     * Returned when a user successfully registers but must verify their email
     * before a session access token is issued.
     */
    data class NeedsEmailConfirmation(
        val email: String,
        val message: String
    ) : AuthResult()

    /**
     * Operation failed with a classified error code and human-friendly message.
     */
    data class Error(
        val code: AuthErrorCode,
        val message: String,
        val details: String? = null
    ) : AuthResult()
}

/**
 * Classified error codes for Supabase Auth flows.
 */
enum class AuthErrorCode {
    INVALID_CREDENTIALS,
    EMAIL_NOT_CONFIRMED,
    USER_ALREADY_EXISTS,
    WEAK_PASSWORD,
    RATE_LIMITED,
    NETWORK_ERROR,
    SERVER_ERROR,
    UNKNOWN
}

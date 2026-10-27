package com.boostlyte.vydra.auth

import android.util.Log
import com.boostlyte.vydra.transcription.SupabaseConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection

/**
 * Direct HTTPS client for Supabase GoTrue Auth REST endpoints.
 *
 * Implements sign up, sign in, token refresh, password recovery, resending confirmation,
 * and session invalidation without external heavy libraries.
 */
open class SupabaseAuthClient(
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.ANON_KEY
) {

    companion object {
        private const val TAG = "SupabaseAuthClient"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    /**
     * Signs up a new user with email and password.
     */
    open suspend fun signUp(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val endpoint = "$supabaseUrl/auth/v1/signup"
        val payload = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("password", password)
        }

        try {
            val response = executePost(endpoint, payload.toString(), authHeader = null)
            val statusCode = response.statusCode
            val body = response.body

            if (statusCode in 200..299) {
                val json = JsonParser.parseString(body).asJsonObject
                val userObj = json.getAsJsonObject("user") ?: json
                val userId = userObj.get("id")?.asString ?: ""
                val userEmail = userObj.get("email")?.asString ?: email

                val accessToken = json.get("access_token")?.asString
                val refreshToken = json.get("refresh_token")?.asString

                if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                    val expiresIn = json.get("expires_in")?.asLong ?: 3600L
                    val expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L)
                    val session = AuthSession(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresInSeconds = expiresIn,
                        expiresAtTimestampMs = expiresAtMs,
                        user = AuthUser(id = userId, email = userEmail)
                    )
                    Log.i(TAG, "Sign up successful with direct session issued")
                    AuthResult.Success(user = session.user, session = session)
                } else {
                    // Email confirmation is required by Supabase backend
                    Log.i(TAG, "Sign up successful: Email confirmation required")
                    AuthResult.NeedsEmailConfirmation(
                        email = userEmail,
                        message = "Verification email sent. Please check your inbox to confirm your account."
                    )
                }
            } else {
                parseErrorResponse(statusCode, body)
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * Signs in an existing user with email and password.
     */
    open suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val endpoint = "$supabaseUrl/auth/v1/token?grant_type=password"
        val payload = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("password", password)
        }

        try {
            val response = executePost(endpoint, payload.toString(), authHeader = null)
            val statusCode = response.statusCode
            val body = response.body

            if (statusCode in 200..299) {
                val json = JsonParser.parseString(body).asJsonObject
                val accessToken = json.get("access_token")?.asString ?: ""
                val refreshToken = json.get("refresh_token")?.asString ?: ""
                val expiresIn = json.get("expires_in")?.asLong ?: 3600L
                val expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L)

                val userObj = json.getAsJsonObject("user")
                val userId = userObj?.get("id")?.asString ?: ""
                val userEmail = userObj?.get("email")?.asString ?: email

                val session = AuthSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresInSeconds = expiresIn,
                    expiresAtTimestampMs = expiresAtMs,
                    user = AuthUser(id = userId, email = userEmail)
                )
                Log.i(TAG, "Sign in successful")
                AuthResult.Success(user = session.user, session = session)
            } else {
                parseErrorResponse(statusCode, body)
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * Refreshes an expired access token using the refresh token.
     */
    open suspend fun refreshToken(refreshToken: String): AuthResult = withContext(Dispatchers.IO) {
        val endpoint = "$supabaseUrl/auth/v1/token?grant_type=refresh_token"
        val payload = JsonObject().apply {
            addProperty("refresh_token", refreshToken)
        }

        try {
            val response = executePost(endpoint, payload.toString(), authHeader = null)
            val statusCode = response.statusCode
            val body = response.body

            if (statusCode in 200..299) {
                val json = JsonParser.parseString(body).asJsonObject
                val accessToken = json.get("access_token")?.asString ?: ""
                val newRefreshToken = json.get("refresh_token")?.asString ?: refreshToken
                val expiresIn = json.get("expires_in")?.asLong ?: 3600L
                val expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L)

                val userObj = json.getAsJsonObject("user")
                val userId = userObj?.get("id")?.asString ?: ""
                val userEmail = userObj?.get("email")?.asString ?: ""

                val session = AuthSession(
                    accessToken = accessToken,
                    refreshToken = newRefreshToken,
                    expiresInSeconds = expiresIn,
                    expiresAtTimestampMs = expiresAtMs,
                    user = AuthUser(id = userId, email = userEmail)
                )
                Log.i(TAG, "Token refresh successful")
                AuthResult.Success(user = session.user, session = session)
            } else {
                Log.w(TAG, "Token refresh rejected with HTTP $statusCode")
                parseErrorResponse(statusCode, body)
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * Resends the signup confirmation email to the user.
     */
    open suspend fun resendConfirmationEmail(email: String): AuthResult = withContext(Dispatchers.IO) {
        val endpoint = "$supabaseUrl/auth/v1/resend"
        val payload = JsonObject().apply {
            addProperty("type", "signup")
            addProperty("email", email.trim())
        }

        try {
            val response = executePost(endpoint, payload.toString(), authHeader = null)
            val statusCode = response.statusCode
            val body = response.body

            if (statusCode in 200..299) {
                Log.i(TAG, "Resend confirmation email sent successfully")
                AuthResult.Success(user = AuthUser("", email), session = null)
            } else {
                parseErrorResponse(statusCode, body)
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * Sends a password recovery email to the user.
     */
    open suspend fun recoverPassword(email: String): AuthResult = withContext(Dispatchers.IO) {
        val endpoint = "$supabaseUrl/auth/v1/recover"
        val payload = JsonObject().apply {
            addProperty("email", email.trim())
        }

        try {
            val response = executePost(endpoint, payload.toString(), authHeader = null)
            val statusCode = response.statusCode
            val body = response.body

            if (statusCode in 200..299) {
                Log.i(TAG, "Password recovery email requested successfully")
                AuthResult.Success(user = AuthUser("", email), session = null)
            } else {
                parseErrorResponse(statusCode, body)
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * Revokes the active session on Supabase server side.
     */
    open suspend fun signOut(accessToken: String?): Unit = withContext(Dispatchers.IO) {
        if (accessToken.isNullOrBlank()) return@withContext
        val endpoint = "$supabaseUrl/auth/v1/logout"
        try {
            executePost(endpoint, "{}", authHeader = "Bearer $accessToken")
            Log.i(TAG, "Sign out completed on server")
        } catch (e: Exception) {
            Log.w(TAG, "Server-side sign out returned error (proceeding with local cleanup): ${e.message}")
        }
    }

    private fun parseErrorResponse(statusCode: Int, body: String): AuthResult.Error {
        var errorCode = AuthErrorCode.UNKNOWN
        var message = "Authentication failed (HTTP $statusCode)"

        try {
            val json = JsonParser.parseString(body).asJsonObject
            val serverErrorCode = json.get("error_code")?.asString ?: json.get("error")?.asString ?: ""
            val serverMsg = json.get("msg")?.asString ?: json.get("error_description")?.asString ?: json.get("message")?.asString ?: ""

            when {
                statusCode == 429 -> {
                    errorCode = AuthErrorCode.RATE_LIMITED
                    message = "Too many attempts. Please wait a few moments and try again."
                }
                serverErrorCode.contains("email_not_confirmed", ignoreCase = true) ||
                        serverMsg.contains("Email not confirmed", ignoreCase = true) -> {
                    errorCode = AuthErrorCode.EMAIL_NOT_CONFIRMED
                    message = "Your email has not been confirmed yet. Please verify your email before signing in."
                }
                serverErrorCode.contains("invalid_credentials", ignoreCase = true) ||
                        serverErrorCode.contains("invalid_grant", ignoreCase = true) ||
                        serverMsg.contains("Invalid login", ignoreCase = true) -> {
                    errorCode = AuthErrorCode.INVALID_CREDENTIALS
                    message = "Invalid email or password. Please check your credentials and try again."
                }
                serverErrorCode.contains("weak_password", ignoreCase = true) ||
                        serverMsg.contains("Password should be", ignoreCase = true) -> {
                    errorCode = AuthErrorCode.WEAK_PASSWORD
                    message = "Password must be at least 6 characters long."
                }
                serverMsg.contains("already registered", ignoreCase = true) ||
                        serverMsg.contains("already exists", ignoreCase = true) -> {
                    errorCode = AuthErrorCode.USER_ALREADY_EXISTS
                    message = "An account with this email already exists. Please sign in instead."
                }
                statusCode in 500..599 -> {
                    errorCode = AuthErrorCode.SERVER_ERROR
                    message = "Authentication server error. Please try again later."
                }
                serverMsg.isNotBlank() -> {
                    message = serverMsg
                }
            }
        } catch (e: Exception) {
            // Json parsing fallback
            if (statusCode in 500..599) {
                errorCode = AuthErrorCode.SERVER_ERROR
                message = "Authentication server error. Please try again later."
            }
        }

        return AuthResult.Error(code = errorCode, message = message, details = body)
    }

    private fun handleException(e: Exception): AuthResult.Error {
        Log.e(TAG, "Network exception during auth operation: ${e.javaClass.simpleName} - ${e.message}")
        return when (e) {
            is SocketTimeoutException -> AuthResult.Error(
                code = AuthErrorCode.NETWORK_ERROR,
                message = "Connection timed out. Please check your internet connection."
            )
            is UnknownHostException, is ConnectException -> AuthResult.Error(
                code = AuthErrorCode.NETWORK_ERROR,
                message = "Unable to connect to server. Please check your network connection."
            )
            else -> AuthResult.Error(
                code = AuthErrorCode.UNKNOWN,
                message = e.message ?: "An unexpected error occurred."
            )
        }
    }

    private data class HttpResponse(val statusCode: Int, val body: String)

    private fun executePost(endpoint: String, jsonBody: String, authHeader: String?): HttpResponse {
        var connection: HttpsURLConnection? = null
        return try {
            val url = URL(endpoint)
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", anonKey)
                if (!authHeader.isNullOrBlank()) {
                    setRequestProperty("Authorization", authHeader)
                }
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = readStreamSafely(stream)
            HttpResponse(statusCode, body)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readStreamSafely(inputStream: InputStream?): String {
        if (inputStream == null) return ""
        return try {
            InputStreamReader(inputStream, Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }
}

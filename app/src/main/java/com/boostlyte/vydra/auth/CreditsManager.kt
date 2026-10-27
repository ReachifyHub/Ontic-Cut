package com.boostlyte.vydra.auth

import android.content.Context
import android.util.Log
import com.boostlyte.vydra.transcription.SupabaseAuthManager
import com.boostlyte.vydra.transcription.SupabaseConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Manages fetching, caching, and validating transcription credits from Supabase.
 *
 * Credits are consumed in audio minutes for AI Auto Assemble transcription.
 * Queries the public.transcription_subscriptions table for the current user's balance.
 */
object CreditsManager {

    private const val TAG = "CreditsManager"
    private const val PREFS_NAME = "vydra_credits_prefs"
    private const val KEY_CACHED_CREDITS = "cached_credits_minutes"
    private const val KEY_LAST_FETCH_TIME = "last_credits_fetch_time"

    @Volatile
    private var inMemoryCredits: Int? = null

    /**
     * Threshold under which the user should be prompted with a "Buy Credits" button.
     */
    const val LOW_CREDIT_THRESHOLD_MINUTES = 5

    /**
     * Fetches current credit balance from Supabase table transcription_subscriptions.
     * Computes remaining minutes using (credits_granted - credits_used) with fallbacks.
     *
     * @return Credit balance in minutes (defaults to 0 if no row exists or unauthenticated).
     */
    suspend fun fetchCredits(context: Context): Int = withContext(Dispatchers.IO) {
        val userId = SupabaseAuthManager.getCurrentUserId(context)
        val accessToken = SupabaseAuthManager.getValidAccessToken(context)

        if (userId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            inMemoryCredits = 0
            saveCachedCredits(context, 0)
            return@withContext 0
        }

        val endpoint = "${SupabaseConfig.SUPABASE_URL}/rest/v1/transcription_subscriptions?user_id=eq.$userId&select=*"
        var connection: HttpsURLConnection? = null

        try {
            val url = URL(endpoint)
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                useCaches = false
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val body = InputStreamReader(connection.inputStream, Charsets.UTF_8).use { it.readText() }
                val jsonArray = JsonParser.parseString(body).asJsonArray

                val credits = if (jsonArray.size() > 0) {
                    val row = jsonArray[0].asJsonObject
                    extractRemainingCredits(row)
                } else {
                    0
                }

                inMemoryCredits = credits
                saveCachedCredits(context, credits)
                Log.i(TAG, "Successfully fetched transcription credits: $credits minutes")
                credits
            } else {
                val errorBody = try {
                    InputStreamReader(connection.errorStream, Charsets.UTF_8).use { it.readText() }
                } catch (e: Exception) { "" }
                Log.w(TAG, "Credits query returned HTTP $statusCode: $errorBody")
                getCachedCredits(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query transcription_subscriptions from Supabase: ${e.message}")
            getCachedCredits(context)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extracts remaining credits from row, checking schema columns in order of specificity.
     */
    internal fun extractRemainingCredits(row: JsonObject): Int {
        // Schema check 1: credits_granted - credits_used (Exact verified schema on backend)
        if (row.has("credits_granted") && row.has("credits_used")) {
            val granted = row.get("credits_granted").asInt
            val used = row.get("credits_used").asInt
            return (granted - used).coerceAtLeast(0)
        }
        // Schema check 2: explicit remaining column
        if (row.has("credits_remaining")) {
            return row.get("credits_remaining").asInt.coerceAtLeast(0)
        }
        if (row.has("remaining_minutes")) {
            return row.get("remaining_minutes").asInt.coerceAtLeast(0)
        }
        if (row.has("credits")) {
            return row.get("credits").asInt.coerceAtLeast(0)
        }
        if (row.has("minutes")) {
            return row.get("minutes").asInt.coerceAtLeast(0)
        }
        Log.w(TAG, "No recognized credits column found in transcription_subscriptions row: $row")
        return 0
    }

    /**
     * Returns local cached credits count in minutes without network query.
     */
    fun getCachedCredits(context: Context): Int {
        return inMemoryCredits ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CACHED_CREDITS, 0)
    }

    /**
     * Sets cached credits manually or upon local consumption.
     */
    fun setCachedCredits(context: Context, credits: Int) {
        val safeCredits = credits.coerceAtLeast(0)
        inMemoryCredits = safeCredits
        saveCachedCredits(context, safeCredits)
    }

    private fun saveCachedCredits(context: Context, credits: Int) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CACHED_CREDITS, credits)
                .putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error saving cached credits: ${e.message}")
        }
    }

    /**
     * Clears cached credits (e.g., when signing out).
     */
    fun invalidateCache(context: Context) {
        inMemoryCredits = null
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing cached credits: ${e.message}")
        }
    }
}

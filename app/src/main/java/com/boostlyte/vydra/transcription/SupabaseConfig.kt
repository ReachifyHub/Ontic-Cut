package com.boostlyte.vydra.transcription

/**
 * Supabase configuration constants for the Vydra backend.
 * Contains the project URL, Publishable (anon) API key, and storage/edge function endpoints.
 * Note: Secret API keys (e.g. NanoGPT) reside strictly on the server side within the Edge Function.
 */
object SupabaseConfig {

    const val SUPABASE_URL: String = "https://rqnbumhburadomrdmzhp.supabase.co"
    const val ANON_KEY: String = "sb_publishable_TuNGrwbWqxVwv8G6t6GxdA_H89LLk8G"

    const val DEFAULT_STORAGE_BUCKET: String = "transcription-uploads"
    const val EDGE_FUNCTION_TRANSCRIBE: String = "transcribe-whisper"

    /** Maximum audio duration accepted by transcribe-whisper (30 minutes in seconds). */
    const val MAX_AUDIO_DURATION_SECONDS: Int = 1800

    /** Timeout in milliseconds for Supabase API and upload requests. */
    const val HTTP_CONNECT_TIMEOUT_MS: Int = 30_000
    const val HTTP_READ_TIMEOUT_MS: Int = 120_000

    /**
     * Constructs the full endpoint URL for an Edge Function.
     */
    fun getEdgeFunctionUrl(functionName: String = EDGE_FUNCTION_TRANSCRIBE): String {
        return "$SUPABASE_URL/functions/v1/$functionName"
    }

    /**
     * Constructs the storage object URL for uploading or deleting files.
     */
    fun getStorageObjectUrl(bucket: String, path: String): String {
        val sanitizedPath = path.removePrefix("/")
        return "$SUPABASE_URL/storage/v1/object/$bucket/$sanitizedPath"
    }

    /**
     * Constructs the storage sign URL for generating signed URLs.
     */
    fun getStorageSignUrl(bucket: String, path: String): String {
        val sanitizedPath = path.removePrefix("/")
        return "$SUPABASE_URL/storage/v1/object/sign/$bucket/$sanitizedPath"
    }
}

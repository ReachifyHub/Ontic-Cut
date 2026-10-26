package com.boostlyte.vydra.transcription

import com.boostlyte.vydra.transcription.models.TranscriptionResponse

/**
 * Clean architectural abstraction for voiceover transcription providers.
 * Decouples the backend Edge Function / Whisper implementation from UI and workers.
 */
interface TranscriptionProvider {

    /**
     * Transcribes an audio file accessible via a signed HTTPS URL.
     *
     * @param signedAudioUrl Publicly accessible HTTPS signed URL to the audio file.
     * @param audioDurationSeconds Duration of the audio in seconds (must be <= 1800).
     * @param jwt Supabase user JWT for authentication and credit charging.
     * @return [Result] containing [TranscriptionResponse] with parsed segments and raw JSON.
     */
    suspend fun transcribeAudio(
        signedAudioUrl: String,
        audioDurationSeconds: Int,
        jwt: String
    ): Result<TranscriptionResponse>
}

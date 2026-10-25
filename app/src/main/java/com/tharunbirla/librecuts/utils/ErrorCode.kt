package com.tharunbirla.librecuts.utils

enum class ErrorCode(val code: String, val description: String) {
    FFMPEG_EXECUTION_FAILED("LC-101", "FFmpeg failed to process the video."),
    FONT_MISSING("LC-102", "Font file could not be loaded from assets."),
    FILE_NOT_FOUND("LC-201", "Source video file is missing or inaccessible."),
    GALLERY_SAVE_FAILED("LC-202", "Could not save the processed video to the gallery."),
    OUT_OF_MEMORY("LC-301", "The app ran out of memory while processing frames."),
    TRANSCRIPTION_AUTH_MISSING("LC-401", "Valid user JWT is not available. Authentication UI is pending."),
    TRANSCRIPTION_UPLOAD_FAILED("LC-402", "Failed to upload audio to Supabase Storage."),
    TRANSCRIPTION_SIGNED_URL_FAILED("LC-403", "Failed to generate signed URL for audio."),
    TRANSCRIPTION_API_FAILED("LC-404", "Whisper transcription API returned an error."),
    TRANSCRIPTION_NETWORK_ERROR("LC-405", "Network error during transcription."),
    TRANSCRIPTION_AUDIO_TOO_LONG("LC-406", "Voiceover exceeds maximum allowed duration of 30 minutes."),
    UNEXPECTED_CRASH("LC-500", "An unexpected application error occurred.")
}
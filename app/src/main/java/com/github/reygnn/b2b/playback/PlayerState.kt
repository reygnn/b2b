package com.github.reygnn.b2b.playback

/**
 * Minimal projection of Spotify App Remote's PlayerState — only the fields
 * the orchestrator needs. Decouples the rest of the app from the SDK type.
 */
data class PlayerState(
    val trackUri: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPaused: Boolean,
)

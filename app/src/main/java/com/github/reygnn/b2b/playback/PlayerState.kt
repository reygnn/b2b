package com.github.reygnn.b2b.playback

/**
 * Minimal projection of Spotify App Remote's PlayerState — only the fields
 * the orchestrator and UI need. Decouples the rest of the app from the SDK
 * type so the JVM unit tests don't need it on the classpath.
 *
 * `trackName` / `artistName` are present so the UI's status card can show
 * "Currently: <Track> – <Artist>" between enqueues. The orchestrator itself
 * does not branch on these — it only uses `trackUri`, `positionMs`,
 * `durationMs`, `isPaused` for its trigger and latch logic.
 */
data class PlayerState(
    val trackUri: String,
    val trackName: String,
    val artistName: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPaused: Boolean,
)

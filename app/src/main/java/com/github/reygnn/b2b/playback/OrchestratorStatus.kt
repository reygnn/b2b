package com.github.reygnn.b2b.playback

/**
 * High-level state of the orchestrator, surfaced for UI/notification updates.
 * Transient errors (network, rate-limit) do NOT change status — they only
 * cause the orchestrator to skip the current state and retry on the next.
 *
 * The timestamp of when a status was emitted is *not* a field of these types —
 * it is attached by [OrchestratorStatusHolder] when the status is recorded.
 * That keeps the orchestrator pure (no `System.currentTimeMillis()` call) and
 * lets tests assert on type/payload without freezing time.
 */
sealed interface OrchestratorStatus {
    /** Default state — service not running, or running but hasn't reacted yet. */
    data object Idle : OrchestratorStatus

    /**
     * Spotify is playing a track. Emitted once per track URI when a new
     * [PlayerState] arrives — proof that the App Remote connection is alive
     * and producing events, even if the trigger window hasn't been reached
     * yet. Without this status, the UI cannot distinguish "connected but
     * mid-track" from "not connected at all".
     */
    data class Listening(
        val trackName: String,
        val artistName: String,
        val trackUri: String,
    ) : OrchestratorStatus

    /** Successful enqueue. The track is the one we just pushed into Spotify's queue. */
    data class Enqueued(
        val trackName: String,
        val artistName: String,
        val trackUri: String,
    ) : OrchestratorStatus

    data object FreeTier : OrchestratorStatus
    data object NoActiveDevice : OrchestratorStatus
    data class SpotifyUnavailable(val reason: String) : OrchestratorStatus
}

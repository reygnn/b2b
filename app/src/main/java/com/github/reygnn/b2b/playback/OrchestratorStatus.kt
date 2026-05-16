package com.github.reygnn.b2b.playback

/**
 * High-level state of the orchestrator, surfaced for UI/notification updates.
 * Emitted on every enqueue decision (success or skip with a known reason).
 * Transient errors (network, rate-limit) do NOT change status — they only
 * cause the orchestrator to skip the current state and retry on the next.
 */
sealed interface OrchestratorStatus {
    data object Running : OrchestratorStatus
    data object FreeTier : OrchestratorStatus
    data object NoActiveDevice : OrchestratorStatus
    data class SpotifyUnavailable(val reason: String) : OrchestratorStatus
}

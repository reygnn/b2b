package com.github.reygnn.b2b.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Latest [PlayerState] received from the App Remote SDK, plus the wall-clock
 * time when it was captured. The UI needs both to extrapolate the current
 * playback position — the SDK does not stream position updates, so a "ticking"
 * countdown is only possible by adding `(now - capturedAtEpochMs)` to the
 * stored `positionMs`.
 */
data class PlayerStateSnapshot(
    val state: PlayerState,
    val capturedAtEpochMs: Long,
)

/**
 * App-wide latest [PlayerState], for the UI status card. The orchestrator
 * writes into this on every event from [PlayerStateSource]; the service
 * resets it on destroy so the UI doesn't display stale data after stop.
 *
 * Singleton holder pattern mirrors [OrchestratorStatusHolder].
 */
@Singleton
class PlayerStateHolder @Inject constructor() {
    private val _snapshot = MutableStateFlow<PlayerStateSnapshot?>(null)
    val snapshot: StateFlow<PlayerStateSnapshot?> = _snapshot.asStateFlow()

    fun record(state: PlayerState, capturedAtEpochMs: Long = System.currentTimeMillis()) {
        _snapshot.value = PlayerStateSnapshot(state, capturedAtEpochMs)
    }

    fun reset() {
        _snapshot.value = null
    }
}

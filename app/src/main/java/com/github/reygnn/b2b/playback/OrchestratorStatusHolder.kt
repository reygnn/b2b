package com.github.reygnn.b2b.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the orchestrator's last status plus when it was recorded.
 * `atEpochMs` is attached by [OrchestratorStatusHolder.record] so the
 * orchestrator itself stays free of `System.currentTimeMillis()`.
 */
data class OrchestratorStatusSnapshot(
    val status: OrchestratorStatus,
    val atEpochMs: Long,
) {
    companion object {
        val IDLE = OrchestratorStatusSnapshot(OrchestratorStatus.Idle, atEpochMs = 0L)
    }
}

/**
 * App-wide latest [OrchestratorStatus], for the UI to render. The service
 * writes into this; the HomeViewModel reads from it.
 *
 * Singleton holder pattern mirrors [com.github.reygnn.b2b.service.ServiceState]:
 * a single source of truth that survives ViewModel rotations and outlives the
 * collect lifetime of the orchestrator's SharedFlow.
 */
@Singleton
class OrchestratorStatusHolder @Inject constructor() {
    private val _snapshot = MutableStateFlow(OrchestratorStatusSnapshot.IDLE)
    val snapshot: StateFlow<OrchestratorStatusSnapshot> = _snapshot.asStateFlow()

    fun record(status: OrchestratorStatus, atEpochMs: Long = System.currentTimeMillis()) {
        _snapshot.value = OrchestratorStatusSnapshot(status, atEpochMs)
    }

    fun reset() {
        _snapshot.value = OrchestratorStatusSnapshot.IDLE
    }
}

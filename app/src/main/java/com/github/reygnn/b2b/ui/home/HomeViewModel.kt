package com.github.reygnn.b2b.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.data.repository.PoolSyncObserver
import com.github.reygnn.b2b.data.repository.RateLimitState
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.diagnostics.LogBuffer
import com.github.reygnn.b2b.diagnostics.LogEntry
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.playback.OrchestratorStatusHolder
import com.github.reygnn.b2b.playback.OrchestratorStatusSnapshot
import com.github.reygnn.b2b.playback.PlaybackOrchestrator
import com.github.reygnn.b2b.playback.PlayerStateHolder
import com.github.reygnn.b2b.playback.PlayerStateSnapshot
import com.github.reygnn.b2b.playback.PreviewTrackHolder
import com.github.reygnn.b2b.service.ServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Home screen — session status, play/stop, navigation. Whitelist
 * management lives on a separate screen ([com.github.reygnn.b2b.ui.artists]).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val orchestrator: PlaybackOrchestrator,
    private val logBuffer: LogBuffer,
    artistRepo: ArtistRepository,
    poolRepo: PoolRepository,
    poolSyncObserver: PoolSyncObserver,
    rateLimitStore: RateLimitStore,
    statusHolder: OrchestratorStatusHolder,
    playerStateHolder: PlayerStateHolder,
    previewHolder: PreviewTrackHolder,
    serviceState: ServiceState,
) : ViewModel() {

    val isServiceRunning: StateFlow<Boolean> = serviceState.running

    val orchestratorStatus: StateFlow<OrchestratorStatusSnapshot> = statusHolder.snapshot
    val playerState: StateFlow<PlayerStateSnapshot?> = playerStateHolder.snapshot
    val nextPick: StateFlow<Track?> = previewHolder.track
    val poolTrackCount: StateFlow<Int> = poolRepo.observeTrackCount().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )
    val lastSyncEpochMs: StateFlow<Long?> = poolRepo.observeLatestSyncEpochMs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )
    val isSyncing: StateFlow<Boolean> = poolSyncObserver.observeIsSyncing().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    /**
     * Wall-clock epoch of the next periodic sync tick, or `null` when the
     * worker is not currently ENQUEUED. The Home status card uses this to
     * render a "Next sync: HH:MM" line; the rendering gate (within the
     * next hour, and not while [isSyncing]) lives in the Composable layer.
     *
     * `WhileSubscribed(5_000)` matches the other reactive flows above —
     * the same `runCurrent()` test gotcha applies.
     */
    val nextSyncEpochMs: StateFlow<Long?> = poolSyncObserver.observeNextSyncEpochMs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    /**
     * Active / total whitelisted artists, shown on the status card so the
     * user can see at a glance how much of the whitelist is currently
     * participating in the random picker (paused artists are still in the
     * list but won't be drawn from — see [Artist.isActive]). Derived from
     * the existing [ArtistRepository.observeWhitelist] flow rather than a
     * dedicated DAO query: the whitelist is small and already collected.
     */
    val artistCounts: StateFlow<ArtistCounts> = artistRepo.observeWhitelist()
        .map { list -> ArtistCounts(active = list.count { it.isActive }, total = list.size) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ArtistCounts(0, 0),
        )

    /**
     * The last rate-limit the worker observed, or `null` if there isn't
     * one — or if it has already elapsed. The countdown logic lives in
     * [RateLimitState.remainingSecondsAt], called from the status card
     * against the ticking `now` clock; this flow only carries the raw
     * (seconds, recordedAtEpochMs) pair.
     */
    val rateLimit: StateFlow<RateLimitState?> = rateLimitStore.state()

    val logEntries: StateFlow<List<LogEntry>> = logBuffer.entries
    val traceEnabled: StateFlow<Boolean> = logBuffer.traceEnabled
    /**
     * Mirrors [LogBuffer.hasUndoableClear]. The Home screen renders an
     * indefinite-duration snackbar while this is `true`; tapping Undo
     * calls [undoClearLog] and a 5 s timer auto-commits otherwise.
     */
    val logClearPending: StateFlow<Boolean> = logBuffer.hasUndoableClear

    fun setTraceEnabled(on: Boolean) = logBuffer.setTraceEnabled(on)

    private val _serviceCommand = Channel<ServiceCommand>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val serviceCommand: Flow<ServiceCommand> = _serviceCommand.receiveAsFlow()

    fun toggleService() {
        val cmd = if (isServiceRunning.value) ServiceCommand.Stop else ServiceCommand.Start
        _serviceCommand.trySend(cmd)
    }

    fun skipNext() {
        viewModelScope.launch { orchestrator.skipPreview() }
    }

    private var commitClearJob: Job? = null

    /**
     * Wipe the log with a 5 s undo window. Called from the trash-icon
     * tap in the log panel. The previous attempt's clear was a one-tap
     * destructive action that lost diagnostics ahead of a rate-limit
     * incident; the undo restores the cleared entries verbatim, and the
     * 5 s timer auto-commits otherwise.
     *
     * Calling this again before the previous undo window elapses
     * commits the older snapshot (it's gone after the second tap) and
     * starts a fresh window over whatever the buffer holds now —
     * matches the "you get one undo at a time" mental model.
     */
    fun clearLog() {
        commitClearJob?.cancel()
        logBuffer.clear()
        commitClearJob = viewModelScope.launch {
            delay(LOG_UNDO_MS)
            logBuffer.commitClear()
        }
    }

    /** Restore the most recent clear's entries. No-op once the timer fires. */
    fun undoClearLog() {
        commitClearJob?.cancel()
        logBuffer.undoClear()
    }

    private companion object {
        const val LOG_UNDO_MS = 5_000L
    }
}

enum class ServiceCommand { Start, Stop }

data class ArtistCounts(val active: Int, val total: Int)

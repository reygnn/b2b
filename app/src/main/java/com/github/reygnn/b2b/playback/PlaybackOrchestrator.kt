package com.github.reygnn.b2b.playback

import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.PlaybackRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import com.github.reygnn.b2b.domain.usecase.PickNextTrackUseCase
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure-logic playback orchestrator. Consumes a stream of [PlayerState] from
 * [PlayerStateSource] and, when a track is within [TRIGGER_MS] of its end,
 * enqueues a whitelisted track into the Spotify queue exactly once per
 * track URI.
 *
 * No Android imports — testable on the JVM without Robolectric. The service
 * shell that hosts this orchestrator is in
 * [com.github.reygnn.b2b.service.PlaybackOrchestratorService].
 *
 * Per-track latch (`lastEnqueuedForTrackId`): Spotify App Remote emits
 * `PlayerState` events at several Hz. The trigger condition
 * `duration - position < TRIGGER_MS` is true for the entire last 15 s of
 * every track; without the latch we would enqueue dozens of tracks per
 * track played. The latch resets implicitly when the current track URI in
 * a state event differs from the latched URI — i.e. when Spotify advances
 * to the next track (the one we just enqueued, or a manually queued one).
 */
@Singleton
class PlaybackOrchestrator @Inject constructor(
    private val source: PlayerStateSource,
    private val pickNext: PickNextTrackUseCase,
    private val playback: PlaybackRepository,
    private val recents: RecentlyPlayedRepository,
) {
    private val _status = MutableSharedFlow<OrchestratorStatus>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val status: SharedFlow<OrchestratorStatus> = _status.asSharedFlow()

    suspend fun run(antiRepeatWindow: Int) {
        var lastEnqueuedForTrackId: String? = null
        source.states()
            .catch { e ->
                _status.emit(OrchestratorStatus.SpotifyUnavailable(e.message ?: "connection failed"))
            }
            .collect { state ->
                if (state.isPaused) return@collect
                val remaining = state.durationMs - state.positionMs
                if (remaining > TRIGGER_MS) return@collect
                if (state.trackUri == lastEnqueuedForTrackId) return@collect
                if (enqueueOnce(antiRepeatWindow)) {
                    lastEnqueuedForTrackId = state.trackUri
                }
            }
    }

    private suspend fun enqueueOnce(antiRepeatWindow: Int): Boolean {
        when (val premium = playback.isPremium()) {
            is Outcome.Success -> if (!premium.value) {
                _status.emit(OrchestratorStatus.FreeTier)
                return false
            }
            is Outcome.Error -> return false // transient — no status change, retry on next state
        }
        val device = when (val d = playback.activeDeviceId()) {
            is Outcome.Success -> d.value ?: run {
                _status.emit(OrchestratorStatus.NoActiveDevice)
                return false
            }
            is Outcome.Error -> {
                _status.emit(OrchestratorStatus.NoActiveDevice)
                return false
            }
        }
        val pick = pickNext(antiRepeatWindow)
        if (pick !is Outcome.Success) return false
        val enqueueResult = playback.enqueue(pick.value.uri, device)
        if (enqueueResult !is Outcome.Success) return false
        recents.record(pick.value.uri)
        recents.trim(antiRepeatWindow * 2)
        _status.emit(OrchestratorStatus.Running)
        return true
    }

    companion object {
        const val TRIGGER_MS = 15_000L
    }
}

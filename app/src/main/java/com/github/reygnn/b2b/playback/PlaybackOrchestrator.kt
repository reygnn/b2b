package com.github.reygnn.b2b.playback

import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.PlaybackRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import com.github.reygnn.b2b.domain.usecase.PickNextTrackUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
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
 * Trigger model: position-extrapolated timer.
 *
 * App Remote (at least the 0.8.0 AAR shipped here) emits `PlayerState`
 * primarily on state changes (track change, pause/play, seek, buffering) —
 * NOT periodically while playback ticks forward. Waiting for an event with
 * `duration - position < TRIGGER_MS` therefore never fires for tracks where
 * Spotify emits only at the start. Instead, on each event we compute
 * `delay = duration - position - TRIGGER_MS` and arm a coroutine that
 * suspends for that long before calling [enqueueOnce]. Each new event
 * cancels the pending timer and re-arms with the fresh position (handles
 * seek, pause, re-buffering). Pause cancels without re-arming. The
 * per-track latch [lastEnqueuedForTrackId] still prevents a double-fire if
 * multiple events for the same URI arrive after the timer already ran.
 */
@Singleton
class PlaybackOrchestrator @Inject constructor(
    private val source: PlayerStateSource,
    private val pickNext: PickNextTrackUseCase,
    private val playback: PlaybackRepository,
    private val recents: RecentlyPlayedRepository,
    private val playerStateHolder: PlayerStateHolder,
) {
    private val _status = MutableSharedFlow<OrchestratorStatus>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val status: SharedFlow<OrchestratorStatus> = _status.asSharedFlow()

    suspend fun run(antiRepeatWindow: Int): Unit = coroutineScope {
        var lastEnqueuedForTrackId: String? = null
        var lastSeenTrackId: String? = null
        var triggerJob: Job? = null

        source.states()
            .catch { e ->
                _status.emit(OrchestratorStatus.SpotifyUnavailable(e.message ?: "connection failed"))
            }
            .collect { state ->
                // Mirror the raw state for the UI's position/countdown line.
                // The SDK only emits on state changes, so each event is a
                // fresh anchor for time-based extrapolation.
                playerStateHolder.record(state)

                // Track-change beacon: emit Listening exactly once per new URI,
                // independent of the trigger arming below. Lets the UI show
                // "Currently: …" between enqueues and prove the App Remote
                // connection is producing events.
                if (state.trackUri != lastSeenTrackId) {
                    _status.emit(
                        OrchestratorStatus.Listening(
                            trackName = state.trackName,
                            artistName = state.artistName,
                            trackUri = state.trackUri,
                        )
                    )
                    lastSeenTrackId = state.trackUri
                }

                // Re-arm policy: every new state cancels the pending timer and
                // recomputes from the fresh position. Handles seek, pause/resume,
                // and track change uniformly. Paused or already-latched URIs
                // leave the timer cancelled and don't re-arm.
                triggerJob?.cancel()
                if (state.isPaused) return@collect
                if (state.trackUri == lastEnqueuedForTrackId) return@collect

                val delayMs = state.durationMs - state.positionMs - TRIGGER_MS
                triggerJob = launch {
                    if (delayMs > 0) delay(delayMs)
                    if (enqueueOnce(antiRepeatWindow)) {
                        lastEnqueuedForTrackId = state.trackUri
                    }
                }
            }
    }

    private suspend fun enqueueOnce(antiRepeatWindow: Int): Boolean {
        when (val premium = playback.isPremium()) {
            is Outcome.Success -> if (!premium.value) {
                _status.emit(OrchestratorStatus.FreeTier)
                return false
            }
            is Outcome.Error -> {
                // Surface transient errors instead of silently retrying — without
                // this branch, repeated isPremium() failures during the trigger
                // window leave the UI stuck on the prior status with no
                // explanation of why nothing is being enqueued.
                _status.emit(OrchestratorStatus.SpotifyUnavailable(premium.describe("premium check")))
                return false
            }
        }
        val device = when (val d = playback.activeDeviceId()) {
            is Outcome.Success -> d.value ?: run {
                _status.emit(OrchestratorStatus.NoActiveDevice)
                return false
            }
            is Outcome.Error -> {
                // Network/rate-limit/unknown errors from /me/player/devices —
                // not the same as "no active device" (which is Success(null)).
                // Surface them so the user knows the API call itself failed.
                _status.emit(OrchestratorStatus.SpotifyUnavailable(d.describe("devices lookup")))
                return false
            }
        }
        val pick = pickNext(antiRepeatWindow)
        if (pick !is Outcome.Success) return false
        val enqueueResult = playback.enqueue(pick.value.uri, device)
        if (enqueueResult !is Outcome.Success) return false
        recents.record(pick.value.uri)
        recents.trim(antiRepeatWindow * 2)
        _status.emit(
            OrchestratorStatus.Enqueued(
                trackName = pick.value.name,
                artistName = pick.value.artistName,
                trackUri = pick.value.uri,
            )
        )
        return true
    }

    /**
     * Folds an [Outcome.Error] into a one-line reason for [OrchestratorStatus.SpotifyUnavailable].
     * The label identifies which API call failed so the UI can show
     * "Spotify: premium check failed: HTTP 429: Too Many Requests".
     */
    private fun Outcome.Error.describe(label: String): String = "$label failed: " + when (this) {
        Outcome.Error.Network -> "network"
        Outcome.Error.Unauthenticated -> "unauthenticated"
        Outcome.Error.NotPremium -> "premium required"
        Outcome.Error.NoActiveDevice -> "no active device"
        is Outcome.Error.RateLimited -> "rate limited (retry in ${retryAfterSeconds}s)"
        is Outcome.Error.Unknown -> message ?: "unknown"
    }

    companion object {
        const val TRIGGER_MS = 15_000L
    }
}

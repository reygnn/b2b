package com.github.reygnn.b2b.playback

import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.PlaybackRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import com.github.reygnn.b2b.diagnostics.LogSink
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
    private val previewHolder: PreviewTrackHolder,
    private val log: LogSink,
) {
    private val _status = MutableSharedFlow<OrchestratorStatus>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val status: SharedFlow<OrchestratorStatus> = _status.asSharedFlow()

    // Two-phase pick: chosen at track-change so the UI can show
    // "Next: <Track>" with a skip button, consumed at trigger-fire by
    // enqueueOnce. Anti-repeat is NOT updated until the track is actually
    // enqueued — skipping is free.
    @Volatile private var pendingPick: Track? = null

    // Captured from the latest run(...) call so skipPreview() (invoked from
    // the UI thread, outside the run-loop scope) uses the same window the
    // orchestrator was started with.
    @Volatile private var currentAntiRepeatWindow: Int = DEFAULT_ANTI_REPEAT_WINDOW

    suspend fun run(antiRepeatWindow: Int): Unit = coroutineScope {
        currentAntiRepeatWindow = antiRepeatWindow
        var lastEnqueuedForTrackId: String? = null
        var lastSeenTrackId: String? = null
        var triggerJob: Job? = null

        source.states()
            .catch { e ->
                log.log("orchestrator: source failed — ${e.message ?: "no reason"}")
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
                // connection is producing events. Also the place to pre-pick
                // the upcoming push so the UI's "Next: <Track>" line has
                // something to render before the trigger fires.
                if (state.trackUri != lastSeenTrackId) {
                    log.log("listening: ${state.trackName} — ${state.artistName}")
                    _status.emit(
                        OrchestratorStatus.Listening(
                            trackName = state.trackName,
                            artistName = state.artistName,
                            trackUri = state.trackUri,
                        )
                    )
                    lastSeenTrackId = state.trackUri
                    prePickNext(antiRepeatWindow)
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

    /**
     * Re-pick the upcoming track without touching anti-repeat state — invoked
     * from the UI via the ViewModel when the user taps the skip button on the
     * preview row. The current `pendingPick` is replaced (possibly with the
     * same track if `pickNext` happens to roll it again — semantically
     * consistent with the regular pick).
     *
     * No-op when no pick is currently pending (service not running, or the
     * trigger already fired). Suspends because `pickNext` is a suspend
     * use case; the call site is `viewModelScope.launch`.
     */
    suspend fun skipPreview() {
        if (pendingPick == null) return
        log.log("skip: requested")
        prePickNext(currentAntiRepeatWindow)
    }

    private suspend fun prePickNext(antiRepeatWindow: Int) {
        when (val pick = pickNext(antiRepeatWindow)) {
            is Outcome.Success -> {
                pendingPick = pick.value
                previewHolder.set(pick.value)
                log.log("pre-pick: ${pick.value.name} — ${pick.value.artistName}")
            }
            is Outcome.Error -> {
                // Pool empty / lookup failed — leave preview empty so the UI
                // hides the row. No status emission: the orchestrator's
                // status surface is for enqueue outcomes, not pre-pick.
                pendingPick = null
                previewHolder.reset()
                log.log("pre-pick: pool empty")
            }
        }
    }

    private suspend fun enqueueOnce(antiRepeatWindow: Int): Boolean {
        when (val premium = playback.isPremium()) {
            is Outcome.Success -> if (!premium.value) {
                log.log("enqueue: free tier, aborting")
                _status.emit(OrchestratorStatus.FreeTier)
                return false
            }
            is Outcome.Error -> {
                // Surface transient errors instead of silently retrying — without
                // this branch, repeated isPremium() failures during the trigger
                // window leave the UI stuck on the prior status with no
                // explanation of why nothing is being enqueued.
                val reason = premium.describe("premium check")
                log.log("enqueue: $reason")
                _status.emit(OrchestratorStatus.SpotifyUnavailable(reason))
                return false
            }
        }
        val device = when (val d = playback.activeDeviceId()) {
            is Outcome.Success -> d.value ?: run {
                log.log("enqueue: no active device")
                _status.emit(OrchestratorStatus.NoActiveDevice)
                return false
            }
            is Outcome.Error -> {
                // Network/rate-limit/unknown errors from /me/player/devices —
                // not the same as "no active device" (which is Success(null)).
                // Surface them so the user knows the API call itself failed.
                val reason = d.describe("devices lookup")
                log.log("enqueue: $reason")
                _status.emit(OrchestratorStatus.SpotifyUnavailable(reason))
                return false
            }
        }
        // Honour the user-visible pre-pick from the preview. Fallback to a
        // fresh pick if the pre-pick never happened (e.g. pool was empty at
        // track change but has tracks now from a sync that just finished).
        val pick = pendingPick ?: run {
            val fresh = pickNext(antiRepeatWindow)
            if (fresh !is Outcome.Success) {
                log.log("enqueue: no pick available")
                return false
            }
            fresh.value
        }
        val enqueueResult = playback.enqueue(pick.uri, device)
        if (enqueueResult !is Outcome.Success) {
            val reason = (enqueueResult as Outcome.Error).describe("enqueue")
            log.log(reason)
            return false
        }
        recents.record(pick.uri)
        recents.trim(antiRepeatWindow * 2)
        pendingPick = null
        previewHolder.reset()
        log.log("enqueue: ✓ ${pick.name} — ${pick.artistName}")
        _status.emit(
            OrchestratorStatus.Enqueued(
                trackName = pick.name,
                artistName = pick.artistName,
                trackUri = pick.uri,
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

        // Fallback for skipPreview before run() has been called. The service
        // always starts run() before any UI interaction is possible, so this
        // matters only in defensive paths / synthetic tests.
        private const val DEFAULT_ANTI_REPEAT_WINDOW = 50
    }
}

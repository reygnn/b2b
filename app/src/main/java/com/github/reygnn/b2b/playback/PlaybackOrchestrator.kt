package com.github.reygnn.b2b.playback

import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.PlaybackRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.domain.usecase.PickNextTrackUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * seek, pause, re-buffering). Pause cancels without re-arming.
 *
 * Concurrency model around the trigger fire:
 *   1. The per-track latch [lastEnqueuedForTrackId] is claimed OPTIMISTICALLY
 *      — set before the enqueue HTTP call, not after success. Otherwise a
 *      state event arriving during the HTTP round-trip would see the latch
 *      still empty, cancel the running trigger, and re-arm; if the cancel
 *      lands between the `delay` and the HTTP call, the enqueue is lost,
 *      and Spotify falls back to its context's next-track (the "drift"
 *      symptom). Released back to `null` only if the enqueue itself fails.
 *   2. The HTTP call runs inside [NonCancellable]. A `triggerJob.cancel()`
 *      from the very-next state event cannot kill the call mid-flight. The
 *      enqueue either fully succeeds or fully fails — never half-issued.
 *   3. The success-path reset of `pendingPick` / `previewHolder` is guarded
 *      by a reference-equality check against a snapshot taken at the
 *      function entry of [enqueueOnce]. With NonCancellable shielding the
 *      HTTP call, a track-change event can land during the round-trip,
 *      install a fresh pre-pick for the new track, and then have the
 *      old enqueue's success path clobber it. The guard skips the reset
 *      when the live pick has been replaced.
 *   4. State shared between the collect coroutine and the launched trigger
 *      child coroutine ([lastEnqueuedForTrackId], [sessionTerminated]) is
 *      held in class-level `@Volatile` fields, not in `var`s local to
 *      [run]. The production dispatcher is multi-threaded, so a captured
 *      local var — backed by a non-volatile `Ref.ObjectRef` — would be a
 *      JMM-level data race between writer and reader. See the individual
 *      field docs for the per-field rationale.
 *
 * Premium check is hoisted to session start (see [run]), and the active-
 * device lookup is gone entirely (Spotify routes `/me/player/queue` to the
 * active device when `device_id` is omitted; a 404 is mapped to
 * `Outcome.Error.NoActiveDevice` in `PlaybackRepositoryImpl`). The per-
 * enqueue HTTP cost is therefore one round-trip, not three, shrinking the
 * race window the cancellation guard above was meant to cover.
 *
 * Mid-session 403 handling: if the enqueue HTTP returns
 * [Outcome.Error.NotPremium] (account product flipped, or session-start
 * check soft-failed and the account is in fact free), the session is
 * marked terminal via a flag the collect lambda checks at entry. No
 * further state events are processed for this run. Symmetric to the
 * session-start FreeTier branch which returns from [run] directly; the
 * mid-session path can't return from `coroutineScope` through a
 * `Flow.collect`, so the flag is the equivalent dormancy mechanism.
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

    // Per-track latch. Claimed optimistically inside the launched trigger
    // child coroutine before the HTTP call, released to `null` on transient
    // failure. Read from the collect coroutine to short-circuit subsequent
    // same-URI events while the enqueue is in-flight.
    //
    // Class-level @Volatile, not a local var in run(). The latch is WRITTEN
    // from the trigger child coroutine and READ from the collect coroutine
    // every state event. On the production dispatcher (@DefaultDispatcher =
    // Dispatchers.Default, a thread pool) those two can run on different
    // worker threads. A captured local var compiles to a Ref.ObjectRef
    // whose `element` field has no JMM happens-before guarantee between
    // writer and reader; the latch write could be invisible to the
    // collect-side read long enough to allow a double-arm. @Volatile gives
    // us release/acquire semantics on every access. Reset to null at the
    // top of run() so a stop/start session cycle begins with a clean latch.
    @Volatile private var lastEnqueuedForTrackId: String? = null

    // Sticky session-terminal flag. Flipped to true when enqueueOnce
    // returns EnqueueOutcome.Terminal (currently only on mid-session
    // 403 / NotPremium). Once set, the collect lambda short-circuits at
    // entry — no more Listening emissions, no more pre-picks, no more
    // trigger arming. The orchestrator goes dormant; the Service stays
    // foreground until the user manually stops it. Symmetric to the
    // session-start FreeTier branch which exits run() via
    // `return@coroutineScope` — we use a flag here because we can't
    // return from coroutineScope through a Flow.collect lambda.
    //
    // Class-level @Volatile for the same reason as lastEnqueuedForTrackId:
    // the Terminal branch writes from the trigger child, the entry guard
    // reads from collect, the two can be on different worker threads.
    // Reset to false at the top of run() so a fresh session start clears
    // a prior dormant state.
    @Volatile private var sessionTerminated: Boolean = false

    suspend fun run(antiRepeatWindow: Int): Unit = coroutineScope {
        currentAntiRepeatWindow = antiRepeatWindow
        // Reset cross-coroutine session state. These fields persist on the
        // @Singleton orchestrator across run() invocations; without explicit
        // reset, a stale latch or a previous session's terminated-flag would
        // bleed into a fresh start.
        lastEnqueuedForTrackId = null
        sessionTerminated = false

        // Session-start premium check. Premium is a hard runtime prerequisite
        // for Spotify Web API playback control. Hoisted out of enqueueOnce
        // (where it ran on every track) into a one-shot at session start:
        //   - Non-premium → emit FreeTier, exit. No point arming triggers.
        //   - Other error (network, 429, opaque 403, …) → log + emit, then
        //     proceed. A real free-tier account always answers with a
        //     PREMIUM_REQUIRED reason or at minimum the word "premium" in
        //     the message body (see Repositories.kt::map403), so the next
        //     enqueue still classifies as NotPremium and terminates the
        //     session correctly. A bare 403 without that signal — the
        //     2026-05-19 transient-Spotify-backend case — proceeds and
        //     lets the orchestrator recover when Spotify does.
        //   - Premium → proceed silently. Account product can flip mid-session
        //     (rare, but cheap to handle): enqueueOnce maps a fresh 403 to
        //     FreeTier and we stop firing for that account naturally.
        when (val premium = playback.isPremium()) {
            is Outcome.Success -> if (!premium.value) {
                log.log("session: free tier, aborting")
                _status.emit(OrchestratorStatus.FreeTier)
                return@coroutineScope
            }
            is Outcome.Error -> {
                val reason = premium.describe("premium check")
                log.log("session: $reason (proceeding optimistically)")
                _status.emit(OrchestratorStatus.SpotifyUnavailable(reason))
            }
        }

        // Only-accessed-from-collect locals stay locals — no JMM concern.
        var lastSeenTrackId: String? = null
        var triggerJob: Job? = null

        source.states()
            .catch { e ->
                log.log("orchestrator: source failed — ${e.message ?: "no reason"}")
                _status.emit(OrchestratorStatus.SpotifyUnavailable(e.message ?: "connection failed"))
            }
            .collect { state ->
                if (sessionTerminated) return@collect

                // [TRACE] Raw state-event dump (only emitted when the
                // user has flipped the debug-trace toggle in the log
                // panel — silent in normal use).
                log.trace("state: ${state.trackUri.takeLast(8)} pos=${state.positionMs} dur=${state.durationMs} paused=${state.isPaused}")

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
                val priorJob = triggerJob
                if (priorJob != null && priorJob.isActive) {
                    log.trace("cancel: trigger for prior arming")
                }
                priorJob?.cancel()
                if (state.isPaused) {
                    log.trace("arm: skipped (paused)")
                    return@collect
                }
                if (state.trackUri == lastEnqueuedForTrackId) {
                    log.trace("arm: skipped (latch holds ${state.trackUri.takeLast(8)})")
                    return@collect
                }

                val delayMs = state.durationMs - state.positionMs - TRIGGER_MS
                val latched = state.trackUri
                log.trace("arm: delay=${delayMs}ms for ${latched.takeLast(8)}")
                triggerJob = launch {
                    if (delayMs > 0) delay(delayMs)
                    log.trace("fire: trigger for ${latched.takeLast(8)}")
                    // Optimistic latch: claim the slot for THIS track URI
                    // before any HTTP work. If a concurrent state event for
                    // the same URI lands while the enqueue is in-flight, the
                    // collect-side `trackUri == lastEnqueuedForTrackId` check
                    // already short-circuits — no cancel, no double-fire.
                    // (Field is @Volatile; see its declaration for the JMM
                    // rationale on a multi-threaded dispatcher.)
                    //
                    // Cancellation safety of the bare assignment, given
                    // NonCancellable only starts one line later: cancellation
                    // in Kotlin coroutines is cooperative, observed at the
                    // next suspension point, not preemptively. Between this
                    // assignment and the withContext entry there is none —
                    // delay() above is the only suspension point in this
                    // launch up to this line, and the withContext entry
                    // itself doesn't yield because the dispatcher doesn't
                    // change (NonCancellable is a Job, not a Dispatcher) and
                    // its ensureActive() check runs against the new context's
                    // job (NonCancellable), which is always active. A
                    // concurrent triggerJob.cancel() from the collect loop
                    // can only land DURING delay() (where the rest of this
                    // launch never runs) or once we are already inside the
                    // NonCancellable block (where it is swallowed). The
                    // intermediate "latch set but HTTP not yet shielded"
                    // window does not exist on any dispatcher.
                    lastEnqueuedForTrackId = latched
                    // NonCancellable around the HTTP call: a triggerJob.cancel()
                    // from the very-next state event must not abort the call
                    // mid-flight. Either the enqueue lands fully or it fails
                    // fully; "half-issued and silently lost" is the failure
                    // mode that produces drift back into Spotify's context.
                    val outcome = withContext(NonCancellable) { enqueueOnce(antiRepeatWindow) }
                    when (outcome) {
                        EnqueueOutcome.Success -> Unit
                        // Latch released so the next same-URI state event
                        // re-arms a fresh attempt — applies to transient
                        // failures (network, rate limit, no active device,
                        // unknown 5xx) where retrying might succeed.
                        //
                        // Reference-equality guard against `latched`: a
                        // stale trigger must not wipe the latch claimed by
                        // a fresher one. Sequence to guard against —
                        //   1. track:1 trigger fires, claims latch=track:1,
                        //      HTTP suspends.
                        //   2. Track:2 state event arrives also in the
                        //      trigger window (user-seek). New trigger
                        //      fires, claims latch=track:2, HTTP suspends.
                        //      Both lambdas are alive concurrently;
                        //      NonCancellable on the inner HTTP keeps
                        //      track:1's call from being aborted.
                        //   3. track:1's HTTP returns RetryLater (network).
                        //      An unconditional `= null` would clear the
                        //      field — which now holds "track:2". A
                        //      subsequent state event for track:2 would
                        //      then bypass the short-circuit and arm a
                        //      second enqueue for the URI that is already
                        //      mid-flight. The guard collapses the clear
                        //      to a no-op when the field has been re-
                        //      claimed by a later trigger.
                        EnqueueOutcome.RetryLater ->
                            if (lastEnqueuedForTrackId == latched) lastEnqueuedForTrackId = null
                        // Session-terminal: stop processing further state
                        // events for this run. enqueueOnce already cleared
                        // pendingPick + previewHolder; we just flip the flag.
                        EnqueueOutcome.Terminal -> sessionTerminated = true
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

    /**
     * Outcome of one enqueue attempt, distinguished so the caller in [run]
     * can react appropriately:
     *  - [Success]: enqueue landed; the per-track latch should remain bound
     *    so subsequent same-URI events short-circuit.
     *  - [RetryLater]: transient failure (network, rate limit, no active
     *    device, unknown 5xx); the caller releases the latch so the next
     *    state event for the same URI re-arms.
     *  - [Terminal]: session-terminal failure (account is not premium); the
     *    caller flips a session-wide flag that silences further state
     *    processing. Symmetric to the session-start FreeTier branch, which
     *    exits [run] via `return@coroutineScope` — we can't return from
     *    coroutineScope through a Flow.collect, so the signal is propagated
     *    out as a value and handled at the call site.
     */
    private enum class EnqueueOutcome { Success, RetryLater, Terminal }

    private suspend fun enqueueOnce(antiRepeatWindow: Int): EnqueueOutcome {
        // Honour the user-visible pre-pick from the preview. Fallback to a
        // fresh pick if the pre-pick never happened (e.g. pool was empty at
        // track change but has tracks now from a sync that just finished).
        //
        // [picked] snapshots the pendingPick reference at function entry. The
        // success path below resets `pendingPick`/`previewHolder` ONLY if the
        // snapshot is still the live pick — i.e. no track-change-during-HTTP
        // race has replaced it. Without this guard, an event for a new track
        // arriving while this enqueue is still suspended on the HTTP call
        // would set a fresh pendingPick + previewHolder for the new track,
        // and our terminal reset would nuke it — the UI's "Next: <Track>"
        // would flash empty until the next track change. NonCancellable
        // shields the call from being aborted, so the race is real now.
        val picked = pendingPick
        val pick = picked ?: run {
            val fresh = pickNext(antiRepeatWindow)
            if (fresh !is Outcome.Success) {
                log.log("enqueue: no pick available")
                return EnqueueOutcome.RetryLater
            }
            fresh.value
        }
        // Single HTTP call. `device_id` omitted → Spotify routes to the
        // currently active device; a 404 is mapped to NoActiveDevice in
        // PlaybackRepositoryImpl.enqueue, so the prior explicit /devices
        // probe was redundant. Removing it (and the per-enqueue /me probe,
        // hoisted to session start) cuts the trigger-window HTTP cost from
        // three sequential round-trips to one — the original three-call
        // chain was the main amplifier of the cancellation race this class
        // now guards against with NonCancellable.
        when (val result = playback.enqueue(pick.uri, deviceId = null)) {
            is Outcome.Success -> Unit
            Outcome.Error.NoActiveDevice -> {
                log.log("enqueue: no active device")
                _status.emit(OrchestratorStatus.NoActiveDevice)
                return EnqueueOutcome.RetryLater
            }
            Outcome.Error.NotPremium -> {
                // Account product flipped mid-session, or session-start check
                // soft-failed and the account is actually free. Premium is a
                // hard runtime prerequisite of the entire session — there is
                // no recovery, and retrying on every subsequent state event
                // just burns more guaranteed-403 round-trips. Signal Terminal
                // and clear the preview here (the session is over for this
                // run; the UI must not keep advertising a "Next:" pick that
                // will never be enqueued). The caller in [run] will flip the
                // session-terminated flag.
                log.log("enqueue: free tier (terminal)")
                _status.emit(OrchestratorStatus.FreeTier)
                pendingPick = null
                previewHolder.reset()
                return EnqueueOutcome.Terminal
            }
            is Outcome.Error -> {
                val reason = result.describe("enqueue")
                log.log(reason)
                _status.emit(OrchestratorStatus.SpotifyUnavailable(reason))
                return EnqueueOutcome.RetryLater
            }
        }
        recents.record(pick.uri)
        recents.trim(antiRepeatWindow * 2)
        // Guarded reset: clear the preview state only if the live pick is
        // still the one we just enqueued. If a track-change event landed
        // during the HTTP suspension and overwrote `pendingPick` with a
        // fresh pre-pick for the new track, leave that fresh state alone.
        // Reference equality is intentional — each prePickNext produces a
        // new Track instance from the pool, so a real replacement is always
        // a different identity even if the value happens to round-trip.
        if (pendingPick === picked) {
            pendingPick = null
            previewHolder.reset()
        }
        log.log("enqueue: ✓ ${pick.name} — ${pick.artistName}")
        _status.emit(
            OrchestratorStatus.Enqueued(
                trackName = pick.name,
                artistName = pick.artistName,
                trackUri = pick.uri,
            )
        )
        return EnqueueOutcome.Success
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

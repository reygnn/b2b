package com.github.reygnn.b2b.playback

import app.cash.turbine.test
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.PlaybackRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import com.github.reygnn.b2b.domain.usecase.PickNextTrackUseCase
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackOrchestratorTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val pickNext: PickNextTrackUseCase = mockk()
    private val playback: PlaybackRepository = mockk()
    private val recents: RecentlyPlayedRepository = mockk(relaxUnitFun = true)
    private val playerStateHolder = PlayerStateHolder()
    private val previewHolder = PreviewTrackHolder()

    private val playerStates = MutableSharedFlow<PlayerState>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val source = object : PlayerStateSource {
        override fun states(): Flow<PlayerState> = playerStates
    }

    private val sut = PlaybackOrchestrator(
        source, pickNext, playback, recents, playerStateHolder, previewHolder,
        log = mockk<LogSink>(relaxed = true),
    )

    @Before fun stubHappyPath() {
        // Session-start premium check is the only isPremium() call — it was
        // hoisted out of enqueueOnce in the race fix and stays once-per-run.
        // Per-enqueue HTTP is now a single round-trip: enqueue() with
        // deviceId = null, mapping 404 → NoActiveDevice in the repository.
        // (The prior /me/player/devices probe was removed entirely; the
        // PlaybackRepository interface no longer carries activeDeviceId.)
        coEvery { playback.isPremium() } returns Outcome.Success(true)
        coEvery { pickNext(50) } returns Outcome.Success(trackOf("spotify:track:next"))
        coEvery { playback.enqueue(any(), any()) } returns Outcome.Success(Unit)
    }

    @Test fun `enqueues once when track enters trigger zone`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()

            // deviceId is now passed as null — Spotify routes to its active
            // device server-side, and a 404 (no active device) is mapped to
            // Outcome.Error.NoActiveDevice in PlaybackRepositoryImpl.
            coVerify(exactly = 1) { playback.enqueue("spotify:track:next", null) }
            coVerify(exactly = 1) { recents.record("spotify:track:next") }
            coVerify(exactly = 1) { recents.trim(100) }
            job.cancel()
        }

    @Test fun `emits Listening on new track URI before any trigger check`() =
        runTest(mainRule.testScheduler) {
            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                // Mid-track state: nothing should fire from the trigger.
                playerStates.emit(
                    PlayerState(
                        trackUri = "spotify:track:1",
                        trackName = "First",
                        artistName = "ArtistA",
                        positionMs = 10_000,
                        durationMs = 200_000,
                        isPaused = false,
                    )
                )
                runCurrent()

                assertThat(awaitItem()).isEqualTo(
                    OrchestratorStatus.Listening(
                        trackName = "First",
                        artistName = "ArtistA",
                        trackUri = "spotify:track:1",
                    )
                )
                coVerify(exactly = 0) { playback.enqueue(any(), any()) }
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `Listening fires once per URI even with many state events`() =
        runTest(mainRule.testScheduler) {
            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                repeat(5) {
                    playerStates.emit(
                        PlayerState(
                            trackUri = "spotify:track:1",
                            trackName = "First",
                            artistName = "ArtistA",
                            positionMs = 10_000L + it * 100,
                            durationMs = 200_000,
                            isPaused = false,
                        )
                    )
                }
                runCurrent()

                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Listening::class.java)
                expectNoEvents()
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `emits Enqueued status with track info on successful enqueue`() =
        runTest(mainRule.testScheduler) {
            coEvery { pickNext(50) } returns Outcome.Success(
                Track(
                    uri = "spotify:track:next",
                    name = "Some Song",
                    artistId = "artist-1",
                    artistName = "Some Artist",
                    durationMs = 200_000,
                )
            )

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                // First: Listening for the new URI.
                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Listening::class.java)
                // Then: Enqueued, because remaining < TRIGGER_MS.
                assertThat(awaitItem()).isEqualTo(
                    OrchestratorStatus.Enqueued(
                        trackName = "Some Song",
                        artistName = "Some Artist",
                        trackUri = "spotify:track:next",
                    )
                )
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `latch prevents re-fire on subsequent emissions for the same track`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            repeat(5) { playerStates.emit(nearEnd("spotify:track:1")) }
            runCurrent()

            coVerify(exactly = 1) { playback.enqueue(any(), any()) }
            job.cancel()
        }

    @Test fun `latch resets when track URI changes`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            // runCurrent between emits so the first trigger's enqueueOnce
            // (a single HTTP call now — the prior isPremium/activeDeviceId
            // probes have been hoisted out / removed) finishes before the
            // second emit's collect runs. Latch is claimed BEFORE the call
            // and released only on failure; on success it stays bound to
            // track:1, so the second emit (URI track:2) re-arms cleanly.
            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()
            playerStates.emit(nearEnd("spotify:track:2"))
            runCurrent()

            coVerify(exactly = 2) { playback.enqueue(any(), any()) }
            job.cancel()
        }

    @Test fun `does not fire when track is paused`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(nearEnd("spotify:track:1").copy(isPaused = true))
            runCurrent()

            coVerify(exactly = 0) { playback.enqueue(any(), any()) }
            job.cancel()
        }

    @Test fun `does not fire when remaining time exceeds trigger window`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(
                PlayerState(
                    trackUri = "spotify:track:1",
                    trackName = "n",
                    artistName = "a",
                    positionMs = 10_000,
                    durationMs = 200_000,
                    isPaused = false,
                )
            )
            runCurrent()

            coVerify(exactly = 0) { playback.enqueue(any(), any()) }
            job.cancel()
        }

    @Test fun `emits FreeTier at session start and aborts when account is not premium`() =
        runTest(mainRule.testScheduler) {
            coEvery { playback.isPremium() } returns Outcome.Success(false)

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                // Premium check is now hoisted to session start: FreeTier is
                // emitted before any state collection begins, then run()
                // returns. No Listening events are produced because the
                // collect loop never starts.
                assertThat(awaitItem()).isEqualTo(OrchestratorStatus.FreeTier)

                // Subsequent state events are dropped on the floor — the
                // SharedFlow has no active subscriber once run() returned.
                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                coVerify(exactly = 0) { playback.enqueue(any(), any()) }
                expectNoEvents()
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `emits NoActiveDevice when enqueue reports no active device`() =
        runTest(mainRule.testScheduler) {
            // 404 from /me/player/queue is mapped to NoActiveDevice in the
            // repository. The orchestrator no longer probes /me/player/devices
            // first — it relies entirely on this terminal mapping.
            coEvery { playback.enqueue(any(), any()) } returns Outcome.Error.NoActiveDevice

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Listening::class.java)
                assertThat(awaitItem()).isEqualTo(OrchestratorStatus.NoActiveDevice)
                // The single HTTP call WAS attempted, just terminated by 404.
                coVerify(exactly = 1) { playback.enqueue(any(), any()) }
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `soft-fails on premium check error at session start and continues`() =
        runTest(mainRule.testScheduler) {
            coEvery { playback.isPremium() } returns Outcome.Error.Network

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                // Soft-fail: the error is surfaced as SpotifyUnavailable but
                // the orchestrator proceeds into the collect loop anyway.
                // Rationale: a flaky /me probe shouldn't kill an entire
                // session — if the account is actually non-premium, the
                // first real enqueue will return 403 and we'll emit FreeTier
                // through the regular error path in enqueueOnce.
                val errorStatus = awaitItem()
                assertThat(errorStatus).isInstanceOf(OrchestratorStatus.SpotifyUnavailable::class.java)
                assertThat((errorStatus as OrchestratorStatus.SpotifyUnavailable).reason)
                    .contains("premium check")

                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                // Session continued: Listening + Enqueued both fire.
                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Listening::class.java)
                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Enqueued::class.java)
                coVerify(exactly = 1) { playback.enqueue(any(), any()) }
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `mid-session 403 is terminal silences further events and clears preview`() =
        runTest(mainRule.testScheduler) {
            // Account product flipped mid-session, or session-start premium
            // check soft-failed and the account is in fact free. The terminal
            // signal is a 403 from /me/player/queue. Premium is a hard
            // session-level prerequisite — once we see it fail mid-flight,
            // every subsequent enqueue would also 403, so we go dormant for
            // the rest of this run. Symmetric to the session-start FreeTier
            // branch which exits run() directly.
            coEvery { playback.enqueue(any(), any()) } returns Outcome.Error.NotPremium

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                // Track 1 enters the trigger window → Listening fires (and
                // sets a pre-pick on the preview holder via prePickNext) →
                // trigger fires → enqueue returns 403 → FreeTier + terminal.
                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Listening::class.java)
                assertThat(awaitItem()).isEqualTo(OrchestratorStatus.FreeTier)
                coVerify(exactly = 1) { playback.enqueue(any(), any()) }
                // The preview was cleared as part of the terminal handling —
                // the UI must not keep advertising a "Next:" pick for a
                // session that will never enqueue another track.
                assertThat(previewHolder.track.value).isNull()

                // A subsequent state event (different URI, in the trigger
                // window) must NOT cause another doomed 403 attempt, must
                // NOT emit Listening, must NOT pre-pick. The collect lambda
                // is short-circuited at entry by the sessionTerminated flag.
                playerStates.emit(nearEnd("spotify:track:2"))
                runCurrent()

                coVerify(exactly = 1) { playback.enqueue(any(), any()) }
                expectNoEvents()
                // Preview still clear — the second event didn't sneak a
                // prePickNext through.
                assertThat(previewHolder.track.value).isNull()

                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `emits SpotifyUnavailable when enqueue errors with an unknown failure`() =
        runTest(mainRule.testScheduler) {
            coEvery { playback.enqueue(any(), any()) } returns
                Outcome.Error.Unknown("HTTP 500: boom")

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Listening::class.java)
                val errorStatus = awaitItem()
                assertThat(errorStatus).isInstanceOf(OrchestratorStatus.SpotifyUnavailable::class.java)
                val reason = (errorStatus as OrchestratorStatus.SpotifyUnavailable).reason
                assertThat(reason).contains("enqueue")
                assertThat(reason).contains("HTTP 500: boom")
                coVerify(exactly = 1) { playback.enqueue(any(), any()) }
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `timer fires after duration minus position minus TRIGGER_MS`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            // 200 s track, currently 10 s in → delay until trigger = 175 s.
            playerStates.emit(
                PlayerState(
                    trackUri = "spotify:track:1",
                    trackName = "n", artistName = "a",
                    positionMs = 10_000,
                    durationMs = 200_000,
                    isPaused = false,
                )
            )
            advanceTimeBy(174_999)
            runCurrent()
            coVerify(exactly = 0) { playback.enqueue(any(), any()) }

            advanceTimeBy(2)
            runCurrent()
            coVerify(exactly = 1) { playback.enqueue(any(), any()) }
            job.cancel()
        }

    @Test fun `new state cancels pending timer and re-arms with fresh position`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(
                PlayerState(
                    trackUri = "spotify:track:1",
                    trackName = "n", artistName = "a",
                    positionMs = 10_000,
                    durationMs = 200_000,
                    isPaused = false,
                )
            )
            advanceTimeBy(50_000)
            runCurrent()

            // Mid-track seek to 180 s → new delay until trigger = 5 s.
            playerStates.emit(
                PlayerState(
                    trackUri = "spotify:track:1",
                    trackName = "n", artistName = "a",
                    positionMs = 180_000,
                    durationMs = 200_000,
                    isPaused = false,
                )
            )
            advanceTimeBy(4_999)
            runCurrent()
            coVerify(exactly = 0) { playback.enqueue(any(), any()) }

            advanceTimeBy(2)
            runCurrent()
            coVerify(exactly = 1) { playback.enqueue(any(), any()) }
            job.cancel()
        }

    @Test fun `pause cancels pending timer without re-arming`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(
                PlayerState(
                    trackUri = "spotify:track:1",
                    trackName = "n", artistName = "a",
                    positionMs = 10_000,
                    durationMs = 200_000,
                    isPaused = false,
                )
            )
            advanceTimeBy(100_000)
            runCurrent()

            playerStates.emit(
                PlayerState(
                    trackUri = "spotify:track:1",
                    trackName = "n", artistName = "a",
                    positionMs = 110_000,
                    durationMs = 200_000,
                    isPaused = true,
                )
            )

            // Advance well past the original trigger point — must not fire.
            advanceTimeBy(200_000)
            runCurrent()
            coVerify(exactly = 0) { playback.enqueue(any(), any()) }
            job.cancel()
        }

    @Test fun `pre-picks next track on track change and exposes via preview holder`() =
        runTest(mainRule.testScheduler) {
            val expected = trackOf("spotify:track:next")
            coEvery { pickNext(50) } returns Outcome.Success(expected)

            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(
                PlayerState(
                    trackUri = "spotify:track:1",
                    trackName = "n", artistName = "a",
                    positionMs = 5_000,
                    durationMs = 200_000,
                    isPaused = false,
                )
            )
            runCurrent()

            assertThat(previewHolder.track.value).isEqualTo(expected)
            coVerify(exactly = 0) { playback.enqueue(any(), any()) }
            job.cancel()
        }

    @Test fun `enqueue clears the preview holder on success`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()

            coVerify(exactly = 1) { playback.enqueue(any(), any()) }
            assertThat(previewHolder.track.value).isNull()
            job.cancel()
        }

    @Test fun `skipPreview replaces the pending pick`() =
        runTest(mainRule.testScheduler) {
            val firstPick = trackOf("spotify:track:first")
            val secondPick = trackOf("spotify:track:second")
            coEvery { pickNext(50) } returnsMany listOf(
                Outcome.Success(firstPick),
                Outcome.Success(secondPick),
            )

            val job = launch { sut.run(50) }
            runCurrent()

            // Mid-track event → pre-pick fires once, sets firstPick.
            playerStates.emit(
                PlayerState(
                    trackUri = "spotify:track:1",
                    trackName = "n", artistName = "a",
                    positionMs = 5_000,
                    durationMs = 200_000,
                    isPaused = false,
                )
            )
            runCurrent()
            assertThat(previewHolder.track.value).isEqualTo(firstPick)

            sut.skipPreview()
            runCurrent()
            assertThat(previewHolder.track.value).isEqualTo(secondPick)
            coVerify(exactly = 2) { pickNext(50) }
            job.cancel()
        }

    @Test fun `skipPreview is a no-op when nothing is pending`() =
        runTest(mainRule.testScheduler) {
            // run() never called → currentAntiRepeatWindow is the default
            // and pendingPick is null.
            sut.skipPreview()
            runCurrent()

            coVerify(exactly = 0) { pickNext(any()) }
            assertThat(previewHolder.track.value).isNull()
        }

    @Test fun `enqueue uses the pre-picked track from preview, not a fresh pickNext`() =
        runTest(mainRule.testScheduler) {
            val prePicked = trackOf("spotify:track:pre-picked")
            coEvery { pickNext(50) } returns Outcome.Success(prePicked)

            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()

            // pickNext called exactly once — during the Listening pre-pick;
            // enqueueOnce reused the result from the holder, did not pick again.
            coVerify(exactly = 1) { pickNext(50) }
            coVerify(exactly = 1) { playback.enqueue(prePicked.uri, any()) }
            job.cancel()
        }

    @Test fun `enqueue failure releases latch so next emission for same URI retries`() =
        runTest(mainRule.testScheduler) {
            // First attempt: transient network error. Latch is claimed
            // optimistically inside the launched trigger job, then released
            // when enqueueOnce returns false. A subsequent same-URI emission
            // therefore re-arms and tries again. Second attempt: success.
            coEvery { playback.enqueue(any(), any()) } returnsMany listOf(
                Outcome.Error.Network,
                Outcome.Success(Unit),
            )

            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()
            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()

            coVerify(exactly = 2) { playback.enqueue(any(), any()) }
            // Recents only recorded on the successful call.
            coVerify(exactly = 1) { recents.record(any()) }
            job.cancel()
        }

    // ── Race-specific tests for the cancellation guard ──────────────────
    //
    // These two tests pin the behavioural change at the heart of the fix:
    //   - the optimistic latch is set BEFORE the enqueue HTTP call, so a
    //     concurrent same-URI state event short-circuits and does not arm
    //     a second job;
    //   - the enqueue HTTP runs inside withContext(NonCancellable), so a
    //     triggerJob.cancel() from a follow-up state event cannot abort the
    //     call mid-flight.
    //
    // Before the fix, a state event arriving during the 1–3 s HTTP window
    // either lost the enqueue entirely (cancel landed between probe and
    // enqueue) or, less commonly, double-issued it. The first case is the
    // drift symptom the user reported: status card says "Enqueued ✓", actual
    // queue is empty, Spotify falls back to its context's next track.
    //
    // A MockK stub cannot suspend cleanly inside `answers { … }` (the block
    // is non-suspending per TESTING_CONVENTIONS), so the race tests use a
    // tiny gated fake for the PlaybackRepository instead of a mock. All
    // other collaborators stay as the class-level mocks.

    private class GatedPlaybackRepository(
        val gate: CompletableDeferred<Outcome<Unit>>,
        val premium: Outcome<Boolean> = Outcome.Success(true),
    ) : PlaybackRepository {
        @Volatile var enqueueCalls = 0
            private set
        @Volatile var lastDeviceId: String? = "unset"
            private set

        override suspend fun isPremium(): Outcome<Boolean> = premium

        // Defensive: the orchestrator no longer calls activeDeviceId() after
        // the race fix (the /me/player/devices probe was removed). If a
        // refactor accidentally re-introduces the call, this trips the test
        // loud-and-fast instead of silently returning Success(null).
        override suspend fun activeDeviceId(): Outcome<String?> =
            error("activeDeviceId() must not be called from the orchestrator")

        override suspend fun enqueue(uri: String, deviceId: String?): Outcome<Unit> {
            enqueueCalls += 1
            lastDeviceId = deviceId
            return gate.await()
        }
    }

    @Test fun `state event during in-flight enqueue does not cancel or duplicate the call`() =
        runTest(mainRule.testScheduler) {
            val gate = CompletableDeferred<Outcome<Unit>>()
            val gatedPlayback = GatedPlaybackRepository(gate)
            val raceSut = PlaybackOrchestrator(
                source, pickNext, gatedPlayback, recents,
                playerStateHolder, previewHolder,
                log = mockk<LogSink>(relaxed = true),
            )

            val job = launch { raceSut.run(50) }
            runCurrent()

            // First emit drives the trigger fire immediately (remainingMs <
            // TRIGGER_MS). enqueue suspends on the gate.
            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()
            assertThat(gatedPlayback.enqueueCalls).isEqualTo(1)
            assertThat(gatedPlayback.lastDeviceId).isNull()  // we pass null

            // Second emit (same URI, slightly later position) arrives while
            // the enqueue is still suspended on the gate. With the fix:
            //   1. The latch was claimed BEFORE the HTTP call, so the
            //      collect-side `trackUri == lastEnqueuedForTrackId` check
            //      short-circuits and we never arm a second job.
            //   2. Even if a second job WERE armed, the NonCancellable block
            //      around the suspended enqueue would shield the in-flight
            //      call from triggerJob.cancel().
            playerStates.emit(nearEnd("spotify:track:1").copy(positionMs = 195_000))
            runCurrent()
            assertThat(gatedPlayback.enqueueCalls).isEqualTo(1)

            // Complete the gate. The original call resumes and succeeds.
            gate.complete(Outcome.Success(Unit))
            runCurrent()
            assertThat(gatedPlayback.enqueueCalls).isEqualTo(1)
            // Pins NonCancellable specifically (the latch alone would also
            // collapse the second emit to 0 new calls, so enqueueCalls == 1
            // is necessary-but-not-sufficient). recents.record runs only in
            // the success path AFTER gate.await returns. Without
            // NonCancellable, triggerJob.cancel() from the second emit would
            // have propagated to gate.await, thrown CancellationException,
            // and the success path — including recents.record — would never
            // have executed.
            coVerify(exactly = 1) { recents.record(any()) }

            job.cancel()
        }

    @Test fun `latch is claimed before HTTP so a concurrent same-URI event cannot double-enqueue`() =
        runTest(mainRule.testScheduler) {
            // Two distinct gates: if the latch were set AFTER the HTTP (the
            // pre-fix code path), the second emission would observe the
            // latch as still-empty, arm a second job, and call enqueue again.
            // With the fix, the latch is bound the moment the trigger fires,
            // so the second emission short-circuits.
            val gate = CompletableDeferred<Outcome<Unit>>()
            val gatedPlayback = GatedPlaybackRepository(gate)
            val raceSut = PlaybackOrchestrator(
                source, pickNext, gatedPlayback, recents,
                playerStateHolder, previewHolder,
                log = mockk<LogSink>(relaxed = true),
            )

            val job = launch { raceSut.run(50) }
            runCurrent()

            // Flood the same URI through the source while the first enqueue
            // is suspended on the gate. All same-URI emissions must collapse
            // into a single attempt.
            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()
            repeat(5) { i ->
                playerStates.emit(
                    nearEnd("spotify:track:1").copy(positionMs = 193_000L + i * 200)
                )
            }
            runCurrent()

            assertThat(gatedPlayback.enqueueCalls).isEqualTo(1)

            gate.complete(Outcome.Success(Unit))
            runCurrent()
            assertThat(gatedPlayback.enqueueCalls).isEqualTo(1)

            job.cancel()
        }

    @Test fun `track change during in-flight enqueue does not clobber the new pre-pick`() =
        runTest(mainRule.testScheduler) {
            // Pins the preview-reset guard. Sequence:
            //   1. Track:1 hits the trigger zone. pre-pick sets previewHolder
            //      to firstPick. The enqueue HTTP suspends on the gate.
            //   2. Track:2 starts playing while track:1's enqueue is still
            //      in-flight. The collect-side fires Listening for track:2
            //      and pre-picks secondPick — previewHolder now shows
            //      secondPick. A new triggerJob for track:2 is armed and
            //      sits in delay().
            //   3. Gate completes. Track:1's enqueue success path runs. The
            //      guard `pendingPick === picked` is false (pendingPick is
            //      now secondPick, picked was firstPick), so neither
            //      pendingPick nor previewHolder are reset. The user keeps
            //      seeing "Next: secondPick" — no UI flash.
            //
            // Without the guard, previewHolder.track.value would be null
            // after gate.complete and this test would fail.
            val gate = CompletableDeferred<Outcome<Unit>>()
            val gatedPlayback = GatedPlaybackRepository(gate)
            val firstPick = trackOf("spotify:track:next-1")
            val secondPick = trackOf("spotify:track:next-2")
            coEvery { pickNext(50) } returnsMany listOf(
                Outcome.Success(firstPick),
                Outcome.Success(secondPick),
            )
            val raceSut = PlaybackOrchestrator(
                source, pickNext, gatedPlayback, recents,
                playerStateHolder, previewHolder,
                log = mockk<LogSink>(relaxed = true),
            )

            val job = launch { raceSut.run(50) }
            runCurrent()

            // 1) Track:1 → trigger → enqueue suspends.
            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()
            assertThat(previewHolder.track.value).isEqualTo(firstPick)
            assertThat(gatedPlayback.enqueueCalls).isEqualTo(1)

            // 2) Track:2 begins (early position, well outside the trigger
            // window — its own enqueue won't fire here, only its pre-pick).
            playerStates.emit(
                PlayerState(
                    trackUri = "spotify:track:2",
                    trackName = "Second",
                    artistName = "ArtistB",
                    positionMs = 1_000,
                    durationMs = 200_000,
                    isPaused = false,
                )
            )
            runCurrent()
            assertThat(previewHolder.track.value).isEqualTo(secondPick)

            // 3) Track:1's enqueue completes. Guard must protect secondPick.
            gate.complete(Outcome.Success(Unit))
            runCurrent()

            assertThat(previewHolder.track.value).isEqualTo(secondPick)
            // recents got the URI for the pick we actually enqueued, not
            // the one currently in the preview.
            coVerify(exactly = 1) { recents.record(firstPick.uri) }
            coVerify(exactly = 0) { recents.record(secondPick.uri) }

            job.cancel()
        }

    // Fake that resolves the first enqueue through gate1 and every later
    // call through gate2. Lets us drive two concurrent triggers
    // independently so RetryLater on the older one can race against the
    // latch claim of the younger one.
    private class TwoGatePlaybackRepository(
        private val gate1: CompletableDeferred<Outcome<Unit>>,
        private val gate2: CompletableDeferred<Outcome<Unit>>,
    ) : PlaybackRepository {
        @Volatile var enqueueCalls = 0
            private set

        override suspend fun isPremium(): Outcome<Boolean> = Outcome.Success(true)

        override suspend fun activeDeviceId(): Outcome<String?> =
            error("activeDeviceId() must not be called from the orchestrator")

        override suspend fun enqueue(uri: String, deviceId: String?): Outcome<Unit> {
            enqueueCalls += 1
            return if (enqueueCalls == 1) gate1.await() else gate2.await()
        }
    }

    @Test fun `RetryLater on a stale trigger does not release a fresher trigger's latch claim`() =
        runTest(mainRule.testScheduler) {
            // Pins the reference-equality guard on the RetryLater branch.
            // Without it, the older trigger's RetryLater would set
            // lastEnqueuedForTrackId = null even though the field already
            // holds the URI claimed by a younger trigger, and the next
            // same-URI state event for the younger track would arm a
            // duplicate enqueue.
            val gate1 = CompletableDeferred<Outcome<Unit>>()
            val gate2 = CompletableDeferred<Outcome<Unit>>()
            val rep = TwoGatePlaybackRepository(gate1, gate2)
            val raceSut = PlaybackOrchestrator(
                source, pickNext, rep, recents,
                playerStateHolder, previewHolder,
                log = mockk<LogSink>(relaxed = true),
            )

            val job = launch { raceSut.run(50) }
            runCurrent()

            // 1) Track:1 → trigger fires (delay=0, nearEnd) → claims latch
            //    = track:1 → enqueue suspends on gate1.
            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()
            assertThat(rep.enqueueCalls).isEqualTo(1)

            // 2) Track:2 arrives also in the trigger window (user-seek to
            //    near-end of the new track). The collect loop overwrites
            //    triggerJob — the prior job continues inside NonCancellable
            //    — and the new triggerJob claims latch = track:2 and
            //    suspends on gate2. Both lambdas are alive concurrently.
            playerStates.emit(nearEnd("spotify:track:2"))
            runCurrent()
            assertThat(rep.enqueueCalls).isEqualTo(2)

            // 3) gate1 resolves with a transient error. Track:1's lambda
            //    enters the RetryLater branch. The field currently holds
            //    "track:2" (from step 2). With the guard, latched =
            //    "track:1" does not match, so the field is left alone.
            //    Without the guard, the field would be unconditionally
            //    cleared to null.
            gate1.complete(Outcome.Error.Network)
            runCurrent()

            // 4) Another same-URI state event for track:2 arrives. The
            //    collect-side short-circuit `state.trackUri ==
            //    lastEnqueuedForTrackId` must hold — meaning no new
            //    triggerJob is armed, no second enqueue call is issued.
            //    Without the guard, this assertion would fail with
            //    enqueueCalls == 3.
            playerStates.emit(nearEnd("spotify:track:2"))
            runCurrent()
            assertThat(rep.enqueueCalls).isEqualTo(2)

            // Drain the in-flight track:2 call so the test scope exits
            // cleanly.
            gate2.complete(Outcome.Success(Unit))
            runCurrent()
            job.cancel()
        }

    private fun nearEnd(uri: String, remainingMs: Long = 8_000) = PlayerState(
        trackUri = uri,
        trackName = "Track",
        artistName = "Artist",
        positionMs = 200_000 - remainingMs,
        durationMs = 200_000,
        isPaused = false,
    )

    private fun trackOf(uri: String) = Track(
        uri = uri,
        name = "n",
        artistId = "ar",
        artistName = "an",
        durationMs = 200_000,
    )
}

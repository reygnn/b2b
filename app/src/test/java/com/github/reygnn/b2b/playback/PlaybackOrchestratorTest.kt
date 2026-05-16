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
        coEvery { playback.isPremium() } returns Outcome.Success(true)
        coEvery { playback.activeDeviceId() } returns Outcome.Success("device-1")
        coEvery { pickNext(50) } returns Outcome.Success(trackOf("spotify:track:next"))
        coEvery { playback.enqueue(any(), any()) } returns Outcome.Success(Unit)
    }

    @Test fun `enqueues once when track enters trigger zone`() =
        runTest(mainRule.testScheduler) {
            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()

            coVerify(exactly = 1) { playback.enqueue("spotify:track:next", "device-1") }
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
            // chain (isPremium → activeDeviceId → enqueue → latch) finishes
            // before the second emit's collect cancels the still-armed job.
            // In production the same race exists if two PlayerState events
            // arrive within a millisecond — the second re-arms with current
            // info and fires anew, so eventual correctness is preserved.
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

    @Test fun `emits FreeTier status and skips enqueue when account is not premium`() =
        runTest(mainRule.testScheduler) {
            coEvery { playback.isPremium() } returns Outcome.Success(false)

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                // Listening first, then FreeTier from the enqueue path.
                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Listening::class.java)
                assertThat(awaitItem()).isEqualTo(OrchestratorStatus.FreeTier)
                coVerify(exactly = 0) { playback.enqueue(any(), any()) }
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `emits NoActiveDevice when devices lookup returns null`() =
        runTest(mainRule.testScheduler) {
            coEvery { playback.activeDeviceId() } returns Outcome.Success(null)

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Listening::class.java)
                assertThat(awaitItem()).isEqualTo(OrchestratorStatus.NoActiveDevice)
                coVerify(exactly = 0) { playback.enqueue(any(), any()) }
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `emits SpotifyUnavailable when premium check errors`() =
        runTest(mainRule.testScheduler) {
            coEvery { playback.isPremium() } returns Outcome.Error.Network

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                assertThat(awaitItem()).isInstanceOf(OrchestratorStatus.Listening::class.java)
                val errorStatus = awaitItem()
                assertThat(errorStatus).isInstanceOf(OrchestratorStatus.SpotifyUnavailable::class.java)
                assertThat((errorStatus as OrchestratorStatus.SpotifyUnavailable).reason)
                    .contains("premium check")
                coVerify(exactly = 0) { playback.enqueue(any(), any()) }
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `emits SpotifyUnavailable when devices lookup errors`() =
        runTest(mainRule.testScheduler) {
            coEvery { playback.activeDeviceId() } returns
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
                assertThat(reason).contains("devices lookup")
                assertThat(reason).contains("HTTP 500: boom")
                coVerify(exactly = 0) { playback.enqueue(any(), any()) }
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

    @Test fun `transient failure leaves latch open so next emission retries`() =
        runTest(mainRule.testScheduler) {
            // First isPremium call: transient network failure → skip, no latch.
            // Second call: success → fire.
            coEvery { playback.isPremium() } returnsMany listOf(
                Outcome.Error.Network,
                Outcome.Success(true),
            )

            val job = launch { sut.run(50) }
            runCurrent()

            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()
            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()

            coVerify(exactly = 1) { playback.enqueue(any(), any()) }
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

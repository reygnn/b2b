package com.github.reygnn.b2b.playback

import app.cash.turbine.test
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

    private val playerStates = MutableSharedFlow<PlayerState>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val source = object : PlayerStateSource {
        override fun states(): Flow<PlayerState> = playerStates
    }

    private val sut = PlaybackOrchestrator(source, pickNext, playback, recents)

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

            playerStates.emit(nearEnd("spotify:track:1"))
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

                assertThat(awaitItem()).isEqualTo(OrchestratorStatus.FreeTier)
                coVerify(exactly = 0) { playback.enqueue(any(), any()) }
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `emits NoActiveDevice status and skips enqueue when no active device`() =
        runTest(mainRule.testScheduler) {
            coEvery { playback.activeDeviceId() } returns Outcome.Success(null)

            sut.status.test {
                val job = launch { sut.run(50) }
                runCurrent()

                playerStates.emit(nearEnd("spotify:track:1"))
                runCurrent()

                assertThat(awaitItem()).isEqualTo(OrchestratorStatus.NoActiveDevice)
                coVerify(exactly = 0) { playback.enqueue(any(), any()) }
                job.cancel()
                cancelAndConsumeRemainingEvents()
            }
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
            playerStates.emit(nearEnd("spotify:track:1"))
            runCurrent()

            coVerify(exactly = 1) { playback.enqueue(any(), any()) }
            job.cancel()
        }

    private fun nearEnd(uri: String, remainingMs: Long = 8_000) = PlayerState(
        trackUri = uri,
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

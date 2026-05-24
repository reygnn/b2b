package com.github.reygnn.b2b.ui.home

import app.cash.turbine.test
import com.github.reygnn.b2b.data.repository.PoolSyncObserver
import com.github.reygnn.b2b.data.repository.RateLimitState
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.diagnostics.LogBuffer
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.playback.OrchestratorStatus
import com.github.reygnn.b2b.playback.OrchestratorStatusHolder
import com.github.reygnn.b2b.playback.PlaybackOrchestrator
import com.github.reygnn.b2b.playback.PlayerStateHolder
import com.github.reygnn.b2b.playback.PreviewTrackHolder
import com.github.reygnn.b2b.service.ServiceState
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val artistRepo: ArtistRepository = mockk(relaxUnitFun = true)
    private val poolRepo: PoolRepository = mockk(relaxUnitFun = true)
    private val poolSyncObserver: PoolSyncObserver = mockk()
    private val rateLimitStore: RateLimitStore = mockk(relaxed = true)
    private val whitelistFlow = MutableStateFlow<List<Artist>>(emptyList())
    private val rateLimitFlow = MutableStateFlow<RateLimitState?>(null)
    private val serviceState = ServiceState()
    private val statusHolder = OrchestratorStatusHolder()
    private val playerStateHolder = PlayerStateHolder()
    private val previewHolder = PreviewTrackHolder()
    private val orchestrator: PlaybackOrchestrator = mockk(relaxUnitFun = true) {
        coEvery { skipPreview() } returns Unit
    }
    private val logBuffer = LogBuffer()

    @Before fun stubDefaults() {
        every { poolRepo.observeTrackCount() } returns MutableStateFlow(0)
        every { poolRepo.observeLatestSyncEpochMs() } returns MutableStateFlow(null)
        every { poolSyncObserver.observeIsSyncing() } returns MutableStateFlow(false)
        every { poolSyncObserver.observeNextSyncEpochMs() } returns MutableStateFlow(null)
        every { artistRepo.observeWhitelist() } returns whitelistFlow
        every { rateLimitStore.state() } returns rateLimitFlow
    }

    @Test fun `toggleService sends Start when service is not running`() =
        runTest(mainRule.testScheduler) {
            val sut = newSut()
            sut.serviceCommand.test {
                sut.toggleService()
                assertThat(awaitItem()).isEqualTo(ServiceCommand.Start)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `toggleService sends Stop when service is running`() =
        runTest(mainRule.testScheduler) {
            serviceState.setRunning(true)
            val sut = newSut()
            sut.serviceCommand.test {
                sut.toggleService()
                assertThat(awaitItem()).isEqualTo(ServiceCommand.Stop)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `orchestrator status reflects the holder snapshot`() =
        runTest(mainRule.testScheduler) {
            val sut = newSut()
            sut.orchestratorStatus.test {
                assertThat(awaitItem().status).isEqualTo(OrchestratorStatus.Idle)
                statusHolder.record(OrchestratorStatus.FreeTier, atEpochMs = 5L)
                val next = awaitItem()
                assertThat(next.status).isEqualTo(OrchestratorStatus.FreeTier)
                assertThat(next.atEpochMs).isEqualTo(5L)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `pool count and last sync are observed from the repository`() =
        runTest(mainRule.testScheduler) {
            val count = MutableStateFlow(0)
            val lastSync = MutableStateFlow<Long?>(null)
            every { poolRepo.observeTrackCount() } returns count
            every { poolRepo.observeLatestSyncEpochMs() } returns lastSync
            val sut = newSut()

            sut.poolTrackCount.test {
                assertThat(awaitItem()).isEqualTo(0)
                count.value = 1247
                assertThat(awaitItem()).isEqualTo(1247)
                cancelAndConsumeRemainingEvents()
            }
            sut.lastSyncEpochMs.test {
                assertThat(awaitItem()).isNull()
                lastSync.value = 999L
                assertThat(awaitItem()).isEqualTo(999L)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `isSyncing reflects the PoolSyncObserver`() =
        runTest(mainRule.testScheduler) {
            val syncing = MutableStateFlow(false)
            every { poolSyncObserver.observeIsSyncing() } returns syncing
            val sut = newSut()
            sut.isSyncing.test {
                assertThat(awaitItem()).isFalse()
                syncing.value = true
                assertThat(awaitItem()).isTrue()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `nextSyncEpochMs reflects the PoolSyncObserver`() =
        runTest(mainRule.testScheduler) {
            // Mirrors the isSyncing test pattern: drive an upstream
            // MutableStateFlow through the observer mock and assert the
            // VM forwards each new value. Turbine keeps the
            // WhileSubscribed flow live for the duration of the assertions.
            val nextSync = MutableStateFlow<Long?>(null)
            every { poolSyncObserver.observeNextSyncEpochMs() } returns nextSync
            val sut = newSut()
            sut.nextSyncEpochMs.test {
                assertThat(awaitItem()).isNull()
                nextSync.value = 1_700_000_000_000L
                assertThat(awaitItem()).isEqualTo(1_700_000_000_000L)
                nextSync.value = null
                assertThat(awaitItem()).isNull()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `artistCounts derives active and total from the whitelist`() =
        runTest(mainRule.testScheduler) {
            val sut = newSut()
            sut.artistCounts.test {
                // Empty whitelist → 0 / 0.
                assertThat(awaitItem()).isEqualTo(ArtistCounts(active = 0, total = 0))

                // Two active, one paused: total = 3, active = 2.
                whitelistFlow.value = listOf(
                    Artist(id = "a1", name = "A1", isActive = true),
                    Artist(id = "a2", name = "A2", isActive = true),
                    Artist(id = "a3", name = "A3", isActive = false),
                )
                assertThat(awaitItem()).isEqualTo(ArtistCounts(active = 2, total = 3))

                // Pause one more — active drops, total stays.
                whitelistFlow.value = listOf(
                    Artist(id = "a1", name = "A1", isActive = true),
                    Artist(id = "a2", name = "A2", isActive = false),
                    Artist(id = "a3", name = "A3", isActive = false),
                )
                assertThat(awaitItem()).isEqualTo(ArtistCounts(active = 1, total = 3))

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `nextPick reflects the PreviewTrackHolder`() =
        runTest(mainRule.testScheduler) {
            val sut = newSut()
            val track = Track(
                uri = "spotify:track:42",
                name = "Pick",
                artistId = "ar",
                artistName = "Artist",
                durationMs = 100,
            )

            sut.nextPick.test {
                assertThat(awaitItem()).isNull()
                previewHolder.set(track)
                assertThat(awaitItem()).isEqualTo(track)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `skipNext delegates to orchestrator skipPreview`() =
        runTest(mainRule.testScheduler) {
            val sut = newSut()
            sut.skipNext()
            advanceUntilIdle()
            coVerify(exactly = 1) { orchestrator.skipPreview() }
        }

    @Test fun `logEntries reflects the LogBuffer`() = runTest(mainRule.testScheduler) {
        val sut = newSut()
        sut.logEntries.test {
            assertThat(awaitItem()).isEmpty()
            logBuffer.log("hello")
            assertThat(awaitItem()).hasSize(1)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `clearLog empties the buffer`() = runTest(mainRule.testScheduler) {
        logBuffer.log("noise")
        val sut = newSut()
        sut.clearLog()
        assertThat(sut.logEntries.value).isEmpty()
    }

    private fun newSut() = HomeViewModel(
        orchestrator = orchestrator,
        logBuffer = logBuffer,
        artistRepo = artistRepo,
        poolRepo = poolRepo,
        poolSyncObserver = poolSyncObserver,
        rateLimitStore = rateLimitStore,
        statusHolder = statusHolder,
        playerStateHolder = playerStateHolder,
        previewHolder = previewHolder,
        serviceState = serviceState,
    )
}

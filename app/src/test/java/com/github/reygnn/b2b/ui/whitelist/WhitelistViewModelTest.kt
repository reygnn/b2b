package com.github.reygnn.b2b.ui.whitelist

import app.cash.turbine.test
import com.github.reygnn.b2b.data.repository.PoolSyncObserver
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.domain.model.Track
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhitelistViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val artistRepo: ArtistRepository = mockk(relaxUnitFun = true)
    private val poolRepo: PoolRepository = mockk(relaxUnitFun = true)
    private val poolSyncObserver: PoolSyncObserver = mockk()
    private val serviceState = ServiceState()
    private val statusHolder = OrchestratorStatusHolder()
    private val playerStateHolder = PlayerStateHolder()
    private val previewHolder = PreviewTrackHolder()
    private val orchestrator: PlaybackOrchestrator = mockk(relaxUnitFun = true) {
        coEvery { skipPreview() } returns Unit
    }

    @Before fun stubDefaults() {
        coEvery { artistRepo.observeWhitelist() } returns MutableStateFlow(emptyList())
        every { poolRepo.observeTrackCount() } returns MutableStateFlow(0)
        every { poolRepo.observeLatestSyncEpochMs() } returns MutableStateFlow(null)
        every { poolSyncObserver.observeIsSyncing() } returns MutableStateFlow(false)
    }

    @Test fun `search debounces rapid input and calls API once with last query`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists(any()) } returns Outcome.Success(emptyList())
            val sut = newSut()

            sut.search("a")
            sut.search("ab")
            sut.search("abc")
            advanceTimeBy(299)
            runCurrent()
            coVerify(exactly = 0) { artistRepo.searchArtists(any()) }

            advanceTimeBy(2)
            runCurrent()
            coVerify(exactly = 1) { artistRepo.searchArtists("abc") }
        }

    @Test fun `search with blank query clears results without API call`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists("abc") } returns
                Outcome.Success(listOf(artistOf("1", "Found")))
            val sut = newSut()

            sut.search("abc")
            advanceTimeBy(301)
            runCurrent()
            assertThat(sut.searchResults.value).hasSize(1)

            sut.search("")
            advanceTimeBy(301)
            runCurrent()
            assertThat(sut.searchResults.value).isEmpty()
            coVerify(exactly = 1) { artistRepo.searchArtists(any()) }
        }

    @Test fun `search Error outcome surfaces as empty results`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists("x") } returns Outcome.Error.RateLimited(retryAfterSeconds = 1)
            val sut = newSut()

            sut.search("x")
            advanceTimeBy(301)
            runCurrent()

            assertThat(sut.searchResults.value).isEmpty()
            assertThat(sut.isSearching.value).isFalse()
        }

    @Test fun `add and remove delegate to repository`() = runTest(mainRule.testScheduler) {
        val sut = newSut()

        sut.add(artistOf("a1", "Artist"))
        sut.remove("a2")
        advanceUntilIdle()

        coVerify { artistRepo.addToWhitelist(artistOf("a1", "Artist")) }
        coVerify { artistRepo.removeFromWhitelist("a2") }
    }

    /**
     * TESTING_CONVENTIONS §2 — `vm.whitelisted` is `stateIn(WhileSubscribed(5_000))`.
     * `advanceUntilIdle()` would run past the 5 s subscription timeout, drop
     * the upstream, and revert the value to `initialValue` (empty list).
     * The safe pattern is Turbine's `.test { }`, which subscribes for the
     * duration of the test body and keeps the upstream alive.
     */
    @Test fun `whitelisted observes repository flow via Turbine to keep upstream alive`() =
        runTest(mainRule.testScheduler) {
            val source = MutableStateFlow<List<Artist>>(listOf(artistOf("1", "A")))
            coEvery { artistRepo.observeWhitelist() } returns source
            val sut = newSut()

            sut.whitelisted.test {
                assertThat(awaitItem()).containsExactly(artistOf("1", "A"))
                source.value = listOf(artistOf("1", "A"), artistOf("2", "B"))
                assertThat(awaitItem()).hasSize(2)
                cancelAndConsumeRemainingEvents()
            }
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

    private fun newSut() = WhitelistViewModel(
        artistRepo = artistRepo,
        orchestrator = orchestrator,
        poolRepo = poolRepo,
        poolSyncObserver = poolSyncObserver,
        statusHolder = statusHolder,
        playerStateHolder = playerStateHolder,
        previewHolder = previewHolder,
        serviceState = serviceState,
    )

    private fun artistOf(id: String, name: String) = Artist(id = id, name = name)
}

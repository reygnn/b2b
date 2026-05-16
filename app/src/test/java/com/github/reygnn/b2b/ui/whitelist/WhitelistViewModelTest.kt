package com.github.reygnn.b2b.ui.whitelist

import app.cash.turbine.test
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.service.ServiceState
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val serviceState = ServiceState()

    @Before fun stubObserveWhitelist() {
        coEvery { artistRepo.observeWhitelist() } returns MutableStateFlow(emptyList())
    }

    @Test fun `search debounces rapid input and calls API once with last query`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists(any()) } returns Outcome.Success(emptyList())
            val sut = WhitelistViewModel(artistRepo, serviceState)

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
            val sut = WhitelistViewModel(artistRepo, serviceState)

            sut.search("abc")
            advanceTimeBy(301)
            runCurrent()
            assertThat(sut.searchResults.value).hasSize(1)

            sut.search("")
            advanceTimeBy(301)
            runCurrent()
            assertThat(sut.searchResults.value).isEmpty()
            // No additional API call triggered by the blank.
            coVerify(exactly = 1) { artistRepo.searchArtists(any()) }
        }

    @Test fun `search Error outcome surfaces as empty results`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists("x") } returns Outcome.Error.RateLimited(retryAfterSeconds = 1)
            val sut = WhitelistViewModel(artistRepo, serviceState)

            sut.search("x")
            advanceTimeBy(301)
            runCurrent()

            assertThat(sut.searchResults.value).isEmpty()
            assertThat(sut.isSearching.value).isFalse()
        }

    @Test fun `add and remove delegate to repository`() = runTest(mainRule.testScheduler) {
        val sut = WhitelistViewModel(artistRepo, serviceState)

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
            val sut = WhitelistViewModel(artistRepo, serviceState)

            sut.whitelisted.test {
                assertThat(awaitItem()).containsExactly(artistOf("1", "A"))
                source.value = listOf(artistOf("1", "A"), artistOf("2", "B"))
                assertThat(awaitItem()).hasSize(2)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `toggleService sends Start when service is not running`() =
        runTest(mainRule.testScheduler) {
            val sut = WhitelistViewModel(artistRepo, serviceState)

            sut.serviceCommand.test {
                sut.toggleService()
                assertThat(awaitItem()).isEqualTo(ServiceCommand.Start)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `toggleService sends Stop when service is running`() =
        runTest(mainRule.testScheduler) {
            serviceState.setRunning(true)
            val sut = WhitelistViewModel(artistRepo, serviceState)

            sut.serviceCommand.test {
                sut.toggleService()
                assertThat(awaitItem()).isEqualTo(ServiceCommand.Stop)
                cancelAndConsumeRemainingEvents()
            }
        }

    private fun artistOf(id: String, name: String) = Artist(id = id, name = name)
}

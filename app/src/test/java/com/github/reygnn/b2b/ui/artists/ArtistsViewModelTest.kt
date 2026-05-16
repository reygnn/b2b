package com.github.reygnn.b2b.ui.artists

import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistsViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val artistRepo: ArtistRepository = mockk(relaxUnitFun = true)
    private val whitelistFlow = MutableStateFlow<List<Artist>>(emptyList())

    @Before fun stub() {
        coEvery { artistRepo.observeWhitelist() } returns whitelistFlow
    }

    @Test fun `displayedArtists shows whitelisted entries checked`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(artist("a1", "Hannah"))
            val sut = newSut()
            // Drain the eager stateIn collector so its current value is up
            // to date before we inspect it.
            runCurrent()

            assertThat(sut.displayedArtists.value).containsExactly(
                ArtistRow(artist("a1", "Hannah"), isWhitelisted = true)
            )
        }

    @Test fun `submitSearch adds unchecked rows for new hits beneath the whitelist`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(artist("a1", "Hannah"))
            coEvery { artistRepo.searchArtists("ann") } returns Outcome.Success(
                listOf(artist("a2", "Anyma"), artist("a3", "Anna"))
            )
            val sut = newSut()
            runCurrent()
            sut.submitSearch("ann")
            runCurrent()

            assertThat(sut.displayedArtists.value).containsExactly(
                ArtistRow(artist("a1", "Hannah"), isWhitelisted = true),
                ArtistRow(artist("a2", "Anyma"), isWhitelisted = false),
                ArtistRow(artist("a3", "Anna"), isWhitelisted = false),
            ).inOrder()
        }

    @Test fun `search hits that are already whitelisted are deduplicated out`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(artist("a1", "Hannah"))
            coEvery { artistRepo.searchArtists("han") } returns Outcome.Success(
                listOf(artist("a1", "Hannah"), artist("a4", "Hannes"))
            )
            val sut = newSut()
            runCurrent()
            sut.submitSearch("han")
            runCurrent()

            // Hannah appears only once (whitelisted), Hannes is the sole
            // new unchecked entry.
            val rows = sut.displayedArtists.value
            assertThat(rows.map { it.artist.id }).containsExactly("a1", "a4").inOrder()
            assertThat(rows.first().isWhitelisted).isTrue()
            assertThat(rows[1].isWhitelisted).isFalse()
        }

    @Test fun `setWhitelisted true delegates to addToWhitelist`() =
        runTest(mainRule.testScheduler) {
            val sut = newSut()
            sut.setWhitelisted(artist("a1", "Hannah"), checked = true)
            advanceUntilIdle()
            coVerify(exactly = 1) { artistRepo.addToWhitelist(artist("a1", "Hannah")) }
            coVerify(exactly = 0) { artistRepo.removeFromWhitelist(any()) }
        }

    @Test fun `setWhitelisted false delegates to removeFromWhitelist`() =
        runTest(mainRule.testScheduler) {
            val sut = newSut()
            sut.setWhitelisted(artist("a1", "Hannah"), checked = false)
            advanceUntilIdle()
            coVerify(exactly = 1) { artistRepo.removeFromWhitelist("a1") }
            coVerify(exactly = 0) { artistRepo.addToWhitelist(any()) }
        }

    @Test fun `submitSearch with blank query clears search results`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(artist("a1", "Hannah"))
            coEvery { artistRepo.searchArtists("x") } returns Outcome.Success(
                listOf(artist("a2", "Found"))
            )
            val sut = newSut()
            runCurrent()

            sut.submitSearch("x")
            runCurrent()
            assertThat(sut.displayedArtists.value).hasSize(2)

            sut.submitSearch("")
            runCurrent()
            assertThat(sut.displayedArtists.value).containsExactly(
                ArtistRow(artist("a1", "Hannah"), isWhitelisted = true)
            )
            coVerify(exactly = 1) { artistRepo.searchArtists(any()) }
        }

    @Test fun `submitSearch Error surfaces via searchError and clears results`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists("x") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 7)
            val sut = newSut()

            sut.submitSearch("x")
            runCurrent()

            assertThat(sut.searchError.value).contains("Rate limited")
            assertThat(sut.isSearching.value).isFalse()
        }

    private fun newSut() = ArtistsViewModel(artistRepo)

    private fun artist(id: String, name: String) = Artist(id = id, name = name)
}

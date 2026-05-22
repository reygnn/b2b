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
import kotlinx.coroutines.test.advanceTimeBy
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

    @Test fun `onQueryChange debounces and fires search after delay`() =
        runTest(mainRule.testScheduler) {
            // Pins the 300 ms debounce on the search-as-you-type path. A single
            // onQueryChange must not hit Spotify within the debounce window.
            coEvery { artistRepo.searchArtists("annie") } returns
                Outcome.Success(emptyList())
            val sut = newSut()
            runCurrent()

            sut.onQueryChange("annie")
            // Inside the debounce window — search must not fire yet.
            advanceTimeBy(DEBOUNCE_MS - 1)
            runCurrent()
            coVerify(exactly = 0) { artistRepo.searchArtists(any()) }

            // Crossing the debounce threshold — search fires.
            advanceTimeBy(2)
            runCurrent()
            coVerify(exactly = 1) { artistRepo.searchArtists("annie") }
        }

    @Test fun `onQueryChange coalesces rapid keystrokes within the debounce window`() =
        runTest(mainRule.testScheduler) {
            // Rapid typing emits N values within the debounce window. The
            // debounce alone must collapse them down to exactly one hit on
            // Spotify, with the final value as the query (the VM uses the
            // explicit lastSearchedQuery anchor instead of
            // distinctUntilChanged — see the VM for the rationale).
            coEvery { artistRepo.searchArtists(any()) } returns
                Outcome.Success(emptyList())
            val sut = newSut()
            runCurrent()

            sut.onQueryChange("a")
            advanceTimeBy(100)
            sut.onQueryChange("an")
            advanceTimeBy(100)
            sut.onQueryChange("ann")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()

            coVerify(exactly = 1) { artistRepo.searchArtists(any()) }
            coVerify(exactly = 1) { artistRepo.searchArtists("ann") }
        }

    @Test fun `onQueryChange with blank clears state synchronously without hitting Spotify`() =
        runTest(mainRule.testScheduler) {
            // Blank inputs bypass the debounce — searchJob is cancelled and
            // state is cleared immediately so the UI snaps to the whitelist-
            // only view. Even after the debounce window elapses, the blank
            // value must not trigger a no-op searchArtists("") call.
            coEvery { artistRepo.searchArtists("x") } returns Outcome.Success(
                listOf(artist("a2", "Found"))
            )
            whitelistFlow.value = listOf(artist("a1", "Hannah"))
            val sut = newSut()
            runCurrent()

            sut.submitSearch("x")
            runCurrent()
            assertThat(sut.displayedArtists.value).hasSize(2)

            sut.onQueryChange("")
            // No advance — state must be cleared synchronously.
            assertThat(sut.displayedArtists.value).hasSize(1)
            assertThat(sut.searchError.value).isNull()
            assertThat(sut.isSearching.value).isFalse()

            // Even past the debounce window, no further searchArtists call.
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()
            coVerify(exactly = 1) { artistRepo.searchArtists(any()) }
        }

    @Test fun `clear then retype identical query fires the search again`() =
        runTest(mainRule.testScheduler) {
            // Pins the regression that `distinctUntilChanged` after `debounce`
            // used to introduce: the operator's internal "last emitted" memory
            // is not user-resettable, so a clear-then-retype of the same
            // query was silently dropped — the UI looked frozen with empty
            // results. The lastSearchedQuery anchor (reset on the blank/clear
            // path in onQueryChange) is the fix.
            coEvery { artistRepo.searchArtists("annie") } returns
                Outcome.Success(emptyList())
            val sut = newSut()
            runCurrent()

            sut.onQueryChange("annie")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()
            coVerify(exactly = 1) { artistRepo.searchArtists("annie") }

            // Clear, then retype the exact same query.
            sut.onQueryChange("")
            sut.onQueryChange("annie")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()

            // The second search must fire; the user signalled intent by
            // clearing. Total invocation count goes from 1 to 2.
            coVerify(exactly = 2) { artistRepo.searchArtists("annie") }
        }

    @Test fun `submitSearch consumes a subsequent debounce emit for the same query`() =
        runTest(mainRule.testScheduler) {
            // Pins the second case the lastSearchedQuery anchor guards: when
            // submitSearch fires runSearch synchronously for a query and the
            // debounced pipeline subsequently emits the same query 300 ms
            // later (the timer was already running on prior typing), we must
            // not re-run the same search.
            coEvery { artistRepo.searchArtists("annie") } returns
                Outcome.Success(emptyList())
            val sut = newSut()
            runCurrent()

            // Typing pre-charges the debounce timer for "annie".
            sut.onQueryChange("annie")
            advanceTimeBy(DEBOUNCE_MS / 2)
            // Tap the search button before the debounce fires.
            sut.submitSearch("annie")
            runCurrent()

            // submitSearch already issued the search.
            coVerify(exactly = 1) { artistRepo.searchArtists("annie") }

            // The pending debounce emit lands later; it must be filtered out
            // by the anchor, not re-run the same search.
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()
            coVerify(exactly = 1) { artistRepo.searchArtists("annie") }
        }

    @Test fun `retyping the same query without clearing does not re-fire the search`() =
        runTest(mainRule.testScheduler) {
            // Inverse of `clear then retype identical query …`: without an
            // explicit clear/submit, retyping the same query character-by-
            // character must NOT re-issue the same search — that is the
            // whole point of the lastSearchedQuery anchor. Pins the anchor's
            // filter behaviour against a future "always re-fire on emit"
            // refactor that would otherwise silently regress this.
            coEvery { artistRepo.searchArtists("ann") } returns
                Outcome.Success(emptyList())
            val sut = newSut()
            runCurrent()

            sut.onQueryChange("ann")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()
            coVerify(exactly = 1) { artistRepo.searchArtists("ann") }

            // Simulate "select-all & retype same" or "type extra char & delete"
            // — both end at the same value as lastSearchedQuery, neither
            // touches the synchronous clear path.
            sut.onQueryChange("anne")
            sut.onQueryChange("ann")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()

            // Still exactly one search — the anchor blocked the second fire.
            coVerify(exactly = 1) { artistRepo.searchArtists("ann") }
        }

    @Test fun `transient error resets the anchor so retype same query re-fires`() =
        runTest(mainRule.testScheduler) {
            // Pins the failure-retry refinement for the "retryable" branch of
            // shouldResetAnchorOn: a Network failure (or Unknown — same
            // policy) drops the anchor so the user can retry by simply
            // re-typing the same query. Without the reset, the only retry
            // paths are the explicit submit button or a clear-then-retype
            // cycle — unintuitive when the field still visibly contains the
            // query that just failed.
            coEvery { artistRepo.searchArtists("annie") } returnsMany listOf(
                Outcome.Error.Network,
                Outcome.Success(emptyList()),
            )
            val sut = newSut()
            runCurrent()

            sut.onQueryChange("annie")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()
            coVerify(exactly = 1) { artistRepo.searchArtists("annie") }
            assertThat(sut.searchError.value).isNotNull()

            // Retype without an explicit clear. Typo-and-correct path ends
            // back at "annie".
            sut.onQueryChange("annei")
            sut.onQueryChange("annie")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()

            // Second search fired because the failure path nulled the anchor.
            coVerify(exactly = 2) { artistRepo.searchArtists("annie") }
        }

    @Test fun `rate-limited error does not reset the anchor`() =
        runTest(mainRule.testScheduler) {
            // Counterpart to `transient error resets the anchor …`: pins the
            // "do NOT reset" branch of shouldResetAnchorOn. Spotify already
            // told us the exact wait time via Retry-After; dropping the
            // anchor would let the user's natural type-and-correct retyping
            // re-fire /search on every keystroke and prolong the rate-limit
            // penalty. The submit button (which bypasses the anchor) remains
            // available for explicit retry.
            coEvery { artistRepo.searchArtists("annie") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 30)
            val sut = newSut()
            runCurrent()

            sut.onQueryChange("annie")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()
            coVerify(exactly = 1) { artistRepo.searchArtists("annie") }
            assertThat(sut.searchError.value).isNotNull()

            // Same retype pattern as the Network test — but here the anchor
            // must hold, suppressing the second debounce emit.
            sut.onQueryChange("annei")
            sut.onQueryChange("annie")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()

            // Still exactly one call — the anchor blocked the re-fire.
            coVerify(exactly = 1) { artistRepo.searchArtists("annie") }
        }

    @Test fun `submitSearch bypasses the anchor for the rate-limited recovery path`() =
        runTest(mainRule.testScheduler) {
            // Pins the documented escape hatch in shouldResetAnchorOn: when
            // RateLimited holds the anchor in place (so retyping is silently
            // suppressed and Spotify's Retry-After is respected), the user's
            // explicit submit must still go through. Otherwise the
            // RateLimited branch becomes a soft-deadlock — the field shows
            // the query, the error banner says "retry in 30s", the user
            // waits 30 s and taps the search button, and nothing happens.
            //
            // Guards against a future refactor that routes submitSearch
            // through the debounced filter pipeline ("for consistency") and
            // accidentally inherits the anchor filter.
            coEvery { artistRepo.searchArtists("annie") } returnsMany listOf(
                Outcome.Error.RateLimited(retryAfterSeconds = 30),
                Outcome.Success(emptyList()),
            )
            val sut = newSut()
            runCurrent()

            // 1) Initial search hits the rate limit. Anchor stays pinned to
            //    "annie" (RateLimited is non-resetting per policy).
            sut.onQueryChange("annie")
            advanceTimeBy(DEBOUNCE_MS + 1)
            runCurrent()
            coVerify(exactly = 1) { artistRepo.searchArtists("annie") }
            assertThat(sut.searchError.value).contains("Rate limited")

            // 2) User waits past Retry-After and taps the submit button.
            //    submitSearch must NOT consult the anchor; the second call
            //    has to land regardless of lastSearchedQuery == "annie".
            sut.submitSearch("annie")
            runCurrent()

            coVerify(exactly = 2) { artistRepo.searchArtists("annie") }
            // Recovery confirmed end-to-end: the second response is Success,
            // and the error banner is cleared by runSearch's prelude.
            assertThat(sut.searchError.value).isNull()
        }

    private fun newSut() = ArtistsViewModel(artistRepo)

    private fun artist(id: String, name: String) = Artist(id = id, name = name)

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}

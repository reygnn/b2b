package com.github.reygnn.b2b.ui.artists

import com.github.reygnn.b2b.data.repository.KillSwitchStore
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
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
    private val poolRepo: PoolRepository = mockk(relaxUnitFun = true)
    private val killSwitchStore: KillSwitchStore = mockk(relaxed = true)
    private val whitelistFlow = MutableStateFlow<List<Artist>>(emptyList())
    private val trackCountsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val killSwitchFlow = MutableStateFlow(false)

    @Before fun stub() {
        coEvery { artistRepo.observeWhitelist() } returns whitelistFlow
        coEvery { poolRepo.observeTrackCountByArtist() } returns trackCountsFlow
        coEvery { poolRepo.tracksForArtist(any()) } returns emptyList()
        io.mockk.every { killSwitchStore.state() } returns killSwitchFlow
    }

    @Test fun `displayedArtists renders whitelisted entries as Whitelisted rows`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(artist("a1", "Hannah", isActive = true))
            val sut = newSut()
            runCurrent()

            assertThat(sut.displayedArtists.value).containsExactly(
                ArtistRow.Whitelisted(artist("a1", "Hannah", isActive = true), isActive = true, trackCount = 0)
            )
        }

    @Test fun `whitelisted row carries the per-artist pool count`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(
                artist("a1", "Hannah", isActive = true),
                artist("a2", "Anyma", isActive = true),
                artist("a3", "Charlotte", isActive = true),
            )
            // a1: 146 tracks, a2: 0 (missing key in map → default 0),
            // a3: 700. Pins the "missing key → 0" contract.
            trackCountsFlow.value = mapOf("a1" to 146, "a3" to 700)
            val sut = newSut()
            runCurrent()

            val rows = sut.displayedArtists.value.filterIsInstance<ArtistRow.Whitelisted>()
            assertThat(rows.map { it.artist.id to it.trackCount })
                .containsExactly("a1" to 146, "a2" to 0, "a3" to 700)
                .inOrder()
        }

    @Test fun `whitelisted row updates trackCount when the counts flow emits`() =
        runTest(mainRule.testScheduler) {
            // A newly-added artist starts at 0 (trickle hasn't picked it up
            // yet); the count jumps as soon as the worker finishes its
            // slice. This test simulates that emission and asserts the
            // displayedArtists flow re-emits with the new count.
            whitelistFlow.value = listOf(artist("a1", "Hannah", isActive = true))
            val sut = newSut()
            runCurrent()
            assertThat(
                sut.displayedArtists.value.filterIsInstance<ArtistRow.Whitelisted>().first().trackCount
            ).isEqualTo(0)

            trackCountsFlow.value = mapOf("a1" to 146)
            runCurrent()

            assertThat(
                sut.displayedArtists.value.filterIsInstance<ArtistRow.Whitelisted>().first().trackCount
            ).isEqualTo(146)
        }

    @Test fun `whitelisted row mirrors the underlying isActive flag`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(
                artist("a1", "Hannah", isActive = true),
                artist("a2", "Anyma", isActive = false),
            )
            val sut = newSut()
            runCurrent()

            val rows = sut.displayedArtists.value.filterIsInstance<ArtistRow.Whitelisted>()
            assertThat(rows.map { it.artist.id to it.isActive })
                .containsExactly("a1" to true, "a2" to false)
                .inOrder()
        }

    @Test fun `submitSearch adds SearchResult rows beneath the whitelist`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(artist("a1", "Hannah", isActive = true))
            coEvery { artistRepo.searchArtists("ann") } returns Outcome.Success(
                listOf(artist("a2", "Anyma"), artist("a3", "Anna"))
            )
            val sut = newSut()
            runCurrent()
            sut.submitSearch("ann")
            runCurrent()

            assertThat(sut.displayedArtists.value).containsExactly(
                ArtistRow.Whitelisted(artist("a1", "Hannah", isActive = true), isActive = true, trackCount = 0),
                ArtistRow.SearchResult(artist("a2", "Anyma")),
                ArtistRow.SearchResult(artist("a3", "Anna")),
            ).inOrder()
        }

    @Test fun `search hits that are already whitelisted are deduplicated out`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(artist("a1", "Hannah", isActive = true))
            coEvery { artistRepo.searchArtists("han") } returns Outcome.Success(
                listOf(artist("a1", "Hannah"), artist("a4", "Hannes"))
            )
            val sut = newSut()
            runCurrent()
            sut.submitSearch("han")
            runCurrent()

            val rows = sut.displayedArtists.value
            assertThat(rows.map { it.artist.id }).containsExactly("a1", "a4").inOrder()
            assertThat(rows[0]).isInstanceOf(ArtistRow.Whitelisted::class.java)
            assertThat(rows[1]).isInstanceOf(ArtistRow.SearchResult::class.java)
        }

    @Test fun `addToWhitelist delegates to the repo`() = runTest(mainRule.testScheduler) {
        val sut = newSut()
        sut.addToWhitelist(artist("a1", "Hannah"))
        advanceUntilIdle()
        coVerify(exactly = 1) { artistRepo.addToWhitelist(artist("a1", "Hannah")) }
    }

    @Test fun `setActive delegates with the artistId and flag`() =
        runTest(mainRule.testScheduler) {
            val sut = newSut()
            sut.setActive(artist("a1", "Hannah", isActive = true), isActive = false)
            advanceUntilIdle()
            coVerify(exactly = 1) { artistRepo.setActive("a1", false) }
            coVerify(exactly = 0) { artistRepo.removeFromWhitelist(any()) }
        }

    @Test fun `submitSearch with blank query clears search results`() =
        runTest(mainRule.testScheduler) {
            whitelistFlow.value = listOf(artist("a1", "Hannah", isActive = true))
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
            assertThat(sut.displayedArtists.value).hasSize(1)
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

    @Test fun `onQueryChange does not hit Spotify even after a long wait`() =
        runTest(mainRule.testScheduler) {
            // Search is explicit-only: typing never reaches the repo. Pins
            // the rip-out of the previous 300 ms debounce-on-type pipeline
            // — the search button and IME action are the sole entry points.
            val sut = newSut()
            runCurrent()

            sut.onQueryChange("a")
            sut.onQueryChange("an")
            sut.onQueryChange("ann")
            advanceTimeBy(10_000)
            runCurrent()

            coVerify(exactly = 0) { artistRepo.searchArtists(any()) }
        }

    @Test fun `onQueryChange with blank clears prior results synchronously`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists("x") } returns Outcome.Success(
                listOf(artist("a2", "Found"))
            )
            whitelistFlow.value = listOf(artist("a1", "Hannah", isActive = true))
            val sut = newSut()
            runCurrent()

            sut.submitSearch("x")
            runCurrent()
            assertThat(sut.displayedArtists.value).hasSize(2)

            // Wiping the field collapses back to the whitelist-only view,
            // synchronously, without hitting Spotify.
            sut.onQueryChange("")
            assertThat(sut.displayedArtists.value).hasSize(1)
            assertThat(sut.searchError.value).isNull()
            assertThat(sut.isSearching.value).isFalse()
            coVerify(exactly = 1) { artistRepo.searchArtists(any()) }
        }

    @Test fun `submitSearch fires the search exactly once per call`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists("annie") } returns
                Outcome.Success(emptyList())
            val sut = newSut()
            runCurrent()

            sut.submitSearch("annie")
            runCurrent()

            coVerify(exactly = 1) { artistRepo.searchArtists("annie") }
        }

    @Test fun `same query within cache TTL is served from cache without an HTTP call`() =
        runTest(mainRule.testScheduler) {
            // Two submits of the same string a few seconds apart. The
            // cache holds for 5 min; only the first call reaches the
            // repo. Pins the M2 mitigation from NEW-ARTISTS.md.
            coEvery { artistRepo.searchArtists("hannah") } returns
                Outcome.Success(listOf(artist("a1", "Hannah Laing")))
            var virtualNowMs = 100_000L
            val sut = newSut().apply { clock = { virtualNowMs } }
            runCurrent()

            sut.submitSearch("hannah")
            runCurrent()
            virtualNowMs += 10_000L  // 10 s later
            sut.submitSearch("hannah")
            runCurrent()

            coVerify(exactly = 1) { artistRepo.searchArtists("hannah") }
            // Result is still surfaced to the UI on the cached hit.
            val rows = sut.displayedArtists.value.filterIsInstance<ArtistRow.SearchResult>()
            assertThat(rows).hasSize(1)
            assertThat(rows.first().artist.id).isEqualTo("a1")
        }

    @Test fun `same query past cache TTL re-fetches`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists("hannah") } returns
                Outcome.Success(listOf(artist("a1", "Hannah Laing")))
            var virtualNowMs = 100_000L
            val sut = newSut().apply { clock = { virtualNowMs } }
            runCurrent()

            sut.submitSearch("hannah")
            runCurrent()
            // Jump well past the 5 min TTL; entry should be evicted and
            // the next submit refetches.
            virtualNowMs += 10L * 60 * 1000
            sut.submitSearch("hannah")
            runCurrent()

            coVerify(exactly = 2) { artistRepo.searchArtists("hannah") }
        }

    @Test fun `cache is case- and whitespace-insensitive`() =
        runTest(mainRule.testScheduler) {
            // "Hannah", " hannah ", and "HANNAH" all hit the same cache
            // entry. The repo call goes out with the user's original
            // (non-normalized) string so the API receives what the user
            // typed.
            coEvery { artistRepo.searchArtists(any()) } returns
                Outcome.Success(listOf(artist("a1", "Hannah Laing")))
            var virtualNowMs = 100_000L
            val sut = newSut().apply { clock = { virtualNowMs } }
            runCurrent()

            sut.submitSearch("Hannah")
            runCurrent()
            virtualNowMs += 1_000L
            sut.submitSearch(" hannah ")
            runCurrent()
            virtualNowMs += 1_000L
            sut.submitSearch("HANNAH")
            runCurrent()

            coVerify(exactly = 1) { artistRepo.searchArtists(any()) }
        }

    @Test fun `double-tap of the same button within 500ms is swallowed`() =
        runTest(mainRule.testScheduler) {
            // Two submits of DIFFERENT strings under 500 ms apart. The
            // cache doesn't help here (different keys), so the
            // min-interval guard is what stops the second call.
            coEvery { artistRepo.searchArtists(any()) } returns
                Outcome.Success(emptyList())
            var virtualNowMs = 100_000L
            val sut = newSut().apply { clock = { virtualNowMs } }
            runCurrent()

            sut.submitSearch("ab")
            runCurrent()
            virtualNowMs += 100L  // way under MIN_SEARCH_INTERVAL_MS
            sut.submitSearch("abc")
            runCurrent()

            coVerify(exactly = 1) { artistRepo.searchArtists(any()) }
        }

    @Test fun `different queries past the min-interval each hit the repo`() =
        runTest(mainRule.testScheduler) {
            coEvery { artistRepo.searchArtists(any()) } returns
                Outcome.Success(emptyList())
            var virtualNowMs = 100_000L
            val sut = newSut().apply { clock = { virtualNowMs } }
            runCurrent()

            sut.submitSearch("a")
            runCurrent()
            virtualNowMs += 1_000L  // > MIN_SEARCH_INTERVAL_MS
            sut.submitSearch("b")
            runCurrent()

            coVerify(exactly = 1) { artistRepo.searchArtists("a") }
            coVerify(exactly = 1) { artistRepo.searchArtists("b") }
        }

    @Test fun `submitSearch with blank clears state without hitting Spotify`() =
        runTest(mainRule.testScheduler) {
            // Pins the symmetric clear path on the explicit-submit side too:
            // a blank submit (e.g. IME Search on an empty field) must not
            // round-trip to Spotify and must reset the search-state slots.
            coEvery { artistRepo.searchArtists("x") } returns
                Outcome.Success(listOf(artist("a2", "Found")))
            val sut = newSut()

            sut.submitSearch("x")
            runCurrent()
            assertThat(sut.displayedArtists.value).isNotEmpty()

            sut.submitSearch("")
            runCurrent()
            assertThat(sut.displayedArtists.value).isEmpty()
            assertThat(sut.isSearching.value).isFalse()
            coVerify(exactly = 1) { artistRepo.searchArtists(any()) }
        }

    // ---- delete-with-undo ----------------------------------------------

    @Test fun `deleteArtist snapshots tracks before removing and exposes them via deletedSnapshot`() =
        runTest(mainRule.testScheduler) {
            val artist = artist("a1", "Hannah", isActive = true)
            val tracks = listOf(track("t1", "a1"), track("t2", "a1"))
            coEvery { poolRepo.tracksForArtist("a1") } returns tracks
            val sut = newSut()

            sut.deleteArtist(artist)
            runCurrent()

            assertThat(sut.deletedSnapshot.value).isEqualTo(
                DeletedArtistSnapshot(artist, tracks)
            )
            coVerify(exactly = 1) { poolRepo.tracksForArtist("a1") }
            coVerify(exactly = 1) { artistRepo.removeFromWhitelist("a1") }
        }

    @Test fun `undoDelete reinserts the snapshotted artist and tracks`() =
        runTest(mainRule.testScheduler) {
            val artist = artist("a1", "Hannah", isActive = true)
            val tracks = listOf(track("t1", "a1"))
            coEvery { poolRepo.tracksForArtist("a1") } returns tracks
            val sut = newSut()

            sut.deleteArtist(artist)
            runCurrent()
            sut.undoDelete()
            runCurrent()

            coVerify(exactly = 1) { artistRepo.addToWhitelist(artist) }
            coVerify(exactly = 1) { poolRepo.upsertTracks(tracks) }
            assertThat(sut.deletedSnapshot.value).isNull()
        }

    @Test fun `snapshot clears itself after the undo window expires`() =
        runTest(mainRule.testScheduler) {
            val artist = artist("a1", "Hannah", isActive = true)
            coEvery { poolRepo.tracksForArtist("a1") } returns emptyList()
            val sut = newSut()

            sut.deleteArtist(artist)
            runCurrent()
            assertThat(sut.deletedSnapshot.value).isNotNull()

            advanceTimeBy(UNDO_MS - 1)
            runCurrent()
            assertThat(sut.deletedSnapshot.value).isNotNull()

            advanceTimeBy(2)
            runCurrent()
            assertThat(sut.deletedSnapshot.value).isNull()
        }

    @Test fun `undoDelete is a no-op after the undo window has expired`() =
        runTest(mainRule.testScheduler) {
            val artist = artist("a1", "Hannah", isActive = true)
            val tracks = listOf(track("t1", "a1"))
            coEvery { poolRepo.tracksForArtist("a1") } returns tracks
            val sut = newSut()

            sut.deleteArtist(artist)
            runCurrent()
            advanceTimeBy(UNDO_MS + 1)
            runCurrent()

            sut.undoDelete()
            runCurrent()

            coVerify(exactly = 0) { artistRepo.addToWhitelist(any()) }
            coVerify(exactly = 0) { poolRepo.upsertTracks(any()) }
        }

    @Test fun `second deleteArtist within the window finalises the first`() =
        runTest(mainRule.testScheduler) {
            val a1 = artist("a1", "Hannah")
            val a2 = artist("a2", "Anyma")
            coEvery { poolRepo.tracksForArtist("a1") } returns listOf(track("t1", "a1"))
            coEvery { poolRepo.tracksForArtist("a2") } returns listOf(track("t2", "a2"))
            val sut = newSut()

            sut.deleteArtist(a1)
            runCurrent()
            sut.deleteArtist(a2)
            runCurrent()

            // Only the second snapshot is now restorable; both deletions
            // have been forwarded to the repo.
            assertThat(sut.deletedSnapshot.value?.artist?.id).isEqualTo("a2")
            coVerify(exactly = 1) { artistRepo.removeFromWhitelist("a1") }
            coVerify(exactly = 1) { artistRepo.removeFromWhitelist("a2") }

            // Undo restores only a2, not a1 — the first snapshot was finalised
            // when the second delete arrived.
            sut.undoDelete()
            runCurrent()
            coVerify(exactly = 1) { artistRepo.addToWhitelist(a2) }
            coVerify(exactly = 0) { artistRepo.addToWhitelist(a1) }
        }

    private fun newSut() = ArtistsViewModel(artistRepo, poolRepo, killSwitchStore)

    // ---- Kill switch -------------------------------------------------

    @Test fun `submitSearch declines HTTP when kill switch is on`() =
        runTest(mainRule.testScheduler) {
            // Kill switch flips before the user (or an IME-Search action)
            // submits a query. The VM must NOT touch the repo and must
            // surface a clear error so the screen renders feedback.
            killSwitchFlow.value = true
            val sut = newSut()

            sut.submitSearch("anyma")
            advanceUntilIdle()

            coVerify(exactly = 0) { artistRepo.searchArtists(any()) }
            assertThat(sut.searchError.value).isNotNull()
        }

    @Test fun `submitSearch proceeds normally when kill switch is off`() =
        runTest(mainRule.testScheduler) {
            // Sanity check — the regression risk on the gate is that the
            // `if (killSwitchActive.value)` accidentally returns true for
            // all callers and quietly kills the feature even without a
            // penalty. This test pins the false-case.
            coEvery { artistRepo.searchArtists("anyma") } returns
                Outcome.Success(listOf(artist("a1", "Anyma")))
            killSwitchFlow.value = false
            val sut = newSut()

            sut.submitSearch("anyma")
            advanceUntilIdle()

            coVerify(exactly = 1) { artistRepo.searchArtists("anyma") }
            assertThat(sut.searchError.value).isNull()
        }

    private fun artist(id: String, name: String, isActive: Boolean = true) =
        Artist(id = id, name = name, isActive = isActive)

    private fun track(uri: String, artistId: String) = Track(
        uri = uri,
        name = uri,
        artistId = artistId,
        artistName = "n",
        durationMs = 1_000L,
    )

    private companion object {
        const val UNDO_MS = 5_000L
    }
}

package com.github.reygnn.b2b.work

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.repository.KillSwitchStore
import com.github.reygnn.b2b.data.repository.RateLimitState
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.diagnostics.SpotifyCallCounter
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for the trickle design (see ADR-0003).
 *
 * Selection is delegated to [WhitelistDao.pickNextToSync] — its own tests in
 * `WhitelistDaoTest` cover ordering / freshness-floor / inactive filtering.
 * The worker tests here only verify that the trickle treats the returned id
 * correctly and that the rate-limit / network / auth outcomes round-trip
 * to the right [ListenableWorker.Result].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoolSyncWorkerTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val artistRepo: ArtistRepository = mockk()
    private val poolRepo: PoolRepository = mockk(relaxUnitFun = true)
    private val dao: WhitelistDao = mockk()
    private val rateLimitStore: RateLimitStore = mockk(relaxed = true)
    private val killSwitchStore: KillSwitchStore = mockk(relaxed = true)
    private val rateLimitFlow = MutableStateFlow<RateLimitState?>(null)
    private val killSwitchFlow = MutableStateFlow(false)

    @Before fun stubRateLimitState() {
        // Default: no recorded wait + kill switch off → both gates are
        // no-ops and the existing tests reach the normal trickle path.
        // Tests that exercise either gate explicitly flip the
        // corresponding flow.
        every { rateLimitStore.state() } returns rateLimitFlow
        every { killSwitchStore.state() } returns killSwitchFlow
    }

    @Test fun `idle when DAO reports nothing eligible`() = runTest(mainRule.testScheduler) {
        // Steady-state case: everything in the whitelist is younger than
        // the floor. The worker emits a single log line and exits without
        // calling the API.
        coEvery { dao.pickNextToSync(any()) } returns null

        val result = build().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        coVerify(exactly = 0) { artistRepo.fetchAllTrackUrisForArtist(any()) }
        coVerify(exactly = 0) { poolRepo.replaceTracksForArtist(any(), any()) }
        // Idle is not a "Spotify is responsive" signal in itself — don't
        // clear the rate-limit store on a no-op. (A future tick that
        // actually reaches the API does the clearing.)
        coVerify(exactly = 0) { rateLimitStore.clear() }
    }

    @Test fun `fetches the picked artist and atomically swaps its slice`() =
        runTest(mainRule.testScheduler) {
            coEvery { dao.pickNextToSync(any()) } returns "a1"
            coEvery { dao.allIds() } returns listOf("a1", "a2")
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1"), track("t2", "a1")))

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify(exactly = 1) {
                poolRepo.replaceTracksForArtist(
                    "a1",
                    listOf(track("t1", "a1"), track("t2", "a1")),
                )
            }
            // No legacy delete/upsert pair — the atomic replacement is the
            // only mutation path.
            coVerify(exactly = 0) { poolRepo.deleteTracksForArtist(any()) }
            coVerify(exactly = 0) { poolRepo.upsertTracks(any()) }
        }

    @Test fun `successful tick prunes tracks against the current whitelist`() =
        runTest(mainRule.testScheduler) {
            // Pins the "orphan rows get pruned" contract. Even though only
            // a1 was synced this tick, the prune covers the whole whitelist
            // so removed artists' rows disappear within one tick of removal.
            coEvery { dao.pickNextToSync(any()) } returns "a1"
            coEvery { dao.allIds() } returns listOf("a1", "a2")
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(emptyList())

            build().doWork()

            coVerify(exactly = 1) { poolRepo.deleteTracksForRemovedArtists(setOf("a1", "a2")) }
        }

    @Test fun `successful tick clears any previously recorded rate-limit`() =
        runTest(mainRule.testScheduler) {
            // A successful round-trip proves Spotify is talking to us; any
            // stale countdown in the UI must drop.
            coEvery { dao.pickNextToSync(any()) } returns "a1"
            coEvery { dao.allIds() } returns listOf("a1")
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(emptyList())

            build().doWork()

            coVerify(exactly = 1) { rateLimitStore.clear() }
        }

    @Test fun `429 records the wait and returns retry without an in-run delay`() =
        runTest(mainRule.testScheduler) {
            // Should be exceptionally rare with one fetch per 15 min, but
            // when it happens: record so the UI shows a countdown, then let
            // WorkManager's exponential backoff handle the retry. No
            // in-run delay, no second fetch.
            coEvery { dao.pickNextToSync(any()) } returns "a1"
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 42)

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            coVerify(exactly = 1) {
                rateLimitStore.record(retryAfterSeconds = 42, any())
            }
            coVerify(exactly = 0) { rateLimitStore.clear() }
            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist("a1") }
            coVerify(exactly = 0) { poolRepo.replaceTracksForArtist(any(), any()) }
        }

    @Test fun `network error returns retry`() = runTest(mainRule.testScheduler) {
        coEvery { dao.pickNextToSync(any()) } returns "a1"
        coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns Outcome.Error.Network

        val result = build().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        coVerify(exactly = 0) { poolRepo.replaceTracksForArtist(any(), any()) }
    }

    @Test fun `unauthenticated outcome fails the tick`() = runTest(mainRule.testScheduler) {
        // The auth interceptor would have refreshed once before reaching
        // here, so an Unauthenticated outcome means the refresh itself
        // failed — retry would just burn cycles. Hand off and wait for
        // the next 15 min periodic occurrence.
        coEvery { dao.pickNextToSync(any()) } returns "a1"
        coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
            Outcome.Error.Unauthenticated

        val result = build().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        coVerify(exactly = 0) { poolRepo.replaceTracksForArtist(any(), any()) }
    }

    // ---- Active-skip --------------------------------------------------

    @Test fun `respects an announced rate-limit and skips the tick entirely`() =
        runTest(mainRule.testScheduler) {
            // Spotify said "wait 1 hour" and we're well inside that window.
            // The tick must not even consult the DAO — sending requests
            // during an announced wait is the documented path to penalty
            // extension. Result.success (not retry) is intentional:
            // retry would let WorkManager backoff re-fire before the wait
            // closes; success lets the next 15 min periodic occurrence
            // pick it up cleanly once the countdown reaches zero.
            rateLimitFlow.value = RateLimitState(
                retryAfterSeconds = 3_600,
                recordedAtEpochMs = System.currentTimeMillis(),
            )

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify(exactly = 0) { dao.pickNextToSync(any()) }
            coVerify(exactly = 0) { artistRepo.fetchAllTrackUrisForArtist(any()) }
            // Don't clear the store on a skip — the user still needs the countdown.
            coVerify(exactly = 0) { rateLimitStore.clear() }
        }

    @Test fun `proceeds normally when a previously recorded rate-limit has elapsed`() =
        runTest(mainRule.testScheduler) {
            // A stale 1 s wait recorded a minute ago: remainingSecondsAt
            // returns 0, the active-skip is a no-op, and the trickle
            // proceeds. (The next success path will clear the stale
            // record; covered by the success-clears-store test above.)
            rateLimitFlow.value = RateLimitState(
                retryAfterSeconds = 1,
                recordedAtEpochMs = System.currentTimeMillis() - 60_000L,
            )
            coEvery { dao.pickNextToSync(any()) } returns null

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify(exactly = 1) { dao.pickNextToSync(any()) }
        }

    // ---- Kill switch ---------------------------------------------------

    @Test fun `kill switch on skips the tick entirely`() =
        runTest(mainRule.testScheduler) {
            // The kill switch is checked before the rate-limit active-skip
            // and before the DAO lookup. Even with an eligible artist
            // queued, the tick must exit without an API call so the user-
            // set "Spotify silent" mode actually silences sync.
            killSwitchFlow.value = true
            coEvery { dao.pickNextToSync(any()) } returns "a1"

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify(exactly = 0) { dao.pickNextToSync(any()) }
            coVerify(exactly = 0) { artistRepo.fetchAllTrackUrisForArtist(any()) }
            coVerify(exactly = 0) { rateLimitStore.clear() }
        }

    private fun build(): PoolSyncWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        return PoolSyncWorker(
            appContext = mockk(relaxed = true),
            params = params,
            artistRepo = artistRepo,
            poolRepo = poolRepo,
            whitelistDao = dao,
            log = mockk<LogSink>(relaxed = true),
            rateLimitStore = rateLimitStore,
            killSwitchStore = killSwitchStore,
            // The counter is mostly a passthrough here. The dedicated
            // SpotifyCallCounterTest exercises rolling-window semantics;
            // the worker-side stats-emission contract is pinned in
            // PoolSyncWorkerStatsLineTest where a real counter is wired in.
            callCounter = SpotifyCallCounter(),
        )
    }

    private fun track(uri: String, artistId: String) = Track(
        uri = uri,
        name = uri,
        artistId = artistId,
        artistName = "n",
        durationMs = 1_000,
    )
}

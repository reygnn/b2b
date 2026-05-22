package com.github.reygnn.b2b.work

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.repository.RateLimitState
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.diagnostics.LogSink
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PoolSyncWorkerTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val artistRepo: ArtistRepository = mockk()
    private val poolRepo: PoolRepository = mockk(relaxUnitFun = true)
    private val dao: WhitelistDao = mockk()
    private val rateLimitStore: RateLimitStore = mockk(relaxed = true)
    private val rateLimitFlow = MutableStateFlow<RateLimitState?>(null)

    @Before fun stubRateLimitState() {
        every { rateLimitStore.state() } returns rateLimitFlow
        // Default: every artist looks "never synced" so the freshness skip
        // is a no-op and the legacy tests below see the pre-skip behaviour.
        // Tests that exercise the skip explicitly set their own stubs.
        coEvery { poolRepo.lastSyncedEpochMsForArtist(any()) } returns null
    }

    @Test fun `when whitelist empty then succeeds and clears pool`() =
        runTest(mainRule.testScheduler) {
            coEvery { dao.allIds() } returns emptyList()

            val worker = build()
            val result = worker.doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            // Pins the empty-IN-list guard: deleteTracksForRemovedArtists with
            // an empty set must actually clear the pool. The impl routes to
            // dao.deleteAll() so the row count goes to zero.
            coVerify(exactly = 1) { poolRepo.deleteTracksForRemovedArtists(emptySet()) }
        }

    @Test fun `fetches tracks for each active artist and prunes against the full whitelist`() =
        runTest(mainRule.testScheduler) {
            stubIds(allIds = listOf("a1", "a2"), activeIds = listOf("a1", "a2"))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1")))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a2") } returns
                Outcome.Success(listOf(track("t2", "a2"), track("t3", "a2")))

            val worker = build()
            val result = worker.doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify { poolRepo.replaceTracksForArtist("a1", listOf(track("t1", "a1"))) }
            coVerify {
                poolRepo.replaceTracksForArtist(
                    "a2",
                    listOf(track("t2", "a2"), track("t3", "a2")),
                )
            }
            coVerify { poolRepo.deleteTracksForRemovedArtists(setOf("a1", "a2")) }
        }

    @Test fun `inactive artists are skipped during fetch but their tracks survive the prune`() =
        runTest(mainRule.testScheduler) {
            // Pins the lazy-stays design: an inactive artist contributes to
            // [WhitelistDao.allIds] (so its tracks are retained by the prune)
            // but is excluded from [WhitelistDao.activeIds] (so we don't burn
            // an API call refreshing tracks the picker is currently ignoring).
            stubIds(allIds = listOf("a1", "a2"), activeIds = listOf("a1"))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1")))

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            // a1 was fetched; a2 was not.
            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist("a1") }
            coVerify(exactly = 0) { artistRepo.fetchAllTrackUrisForArtist("a2") }
            // The prune set covers both — a2's pool slice survives because
            // it is still in the whitelist, just inactive.
            coVerify { poolRepo.deleteTracksForRemovedArtists(setOf("a1", "a2")) }
        }

    @Test fun `per-artist sync swaps the pool slice atomically`() =
        runTest(mainRule.testScheduler) {
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1")))

            build().doWork()

            // Replace is a single transactional call now; the prior
            // delete-then-upsert pair was not atomic — a worker kill between
            // the two left the artist briefly absent from the pool.
            coVerify(exactly = 1) {
                poolRepo.replaceTracksForArtist("a1", listOf(track("t1", "a1")))
            }
            coVerify(exactly = 0) { poolRepo.deleteTracksForArtist(any()) }
            coVerify(exactly = 0) { poolRepo.upsertTracks(any()) }
        }

    @Test fun `when rate limited then delays and succeeds on next attempt`() =
        runTest(mainRule.testScheduler) {
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returnsMany listOf(
                Outcome.Error.RateLimited(retryAfterSeconds = 2),
                Outcome.Success(listOf(track("t1", "a1"))),
            )

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify { poolRepo.replaceTracksForArtist("a1", listOf(track("t1", "a1"))) }
            coVerify(exactly = 2) { artistRepo.fetchAllTrackUrisForArtist("a1") }
        }

    @Test fun `when rate limit retry-after exceeds cap then defers to WorkManager retry`() =
        runTest(mainRule.testScheduler) {
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            // Spotify sometimes hands out absurdly long Retry-After values
            // after sustained abuse — capped, we hand off to WorkManager.
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 60_000)

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            // Single attempt — no in-run delay, no second fetch.
            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist("a1") }
        }

    @Test fun `when rate limited beyond budget then retries`() =
        runTest(mainRule.testScheduler) {
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 1)

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            coVerify(exactly = 3) { artistRepo.fetchAllTrackUrisForArtist("a1") }
        }

    @Test fun `when network error then retries`() = runTest(mainRule.testScheduler) {
        stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
        coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns Outcome.Error.Network

        val result = build().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist("a1") }
    }

    @Test fun `when unauthenticated then fails`() = runTest(mainRule.testScheduler) {
        stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
        coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns Outcome.Error.Unauthenticated

        val result = build().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
    }

    // ---- RateLimitStore wiring -----------------------------------------

    @Test fun `successful sync clears any previously recorded rate-limit`() =
        runTest(mainRule.testScheduler) {
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1")))

            build().doWork()

            // Reaching success means Spotify is responsive again; the stored
            // wait, if any, is stale and must not linger in the status card.
            coVerify(exactly = 1) { rateLimitStore.clear() }
        }

    @Test fun `cap-exceeding rate-limit records the announced wait`() =
        runTest(mainRule.testScheduler) {
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 56_741)

            build().doWork()

            // The exact value the user wants to see in the status card.
            coVerify(exactly = 1) { rateLimitStore.record(retryAfterSeconds = 56_741, any()) }
            // And no spurious clear() from the same run — the wait is the
            // last word here.
            coVerify(exactly = 0) { rateLimitStore.clear() }
        }

    @Test fun `empty whitelist clears the rate-limit`() =
        runTest(mainRule.testScheduler) {
            // Mirrors the success path: an empty-whitelist short-circuit is
            // still a "Spotify is fine" outcome, so any stale wait should
            // drop out of the UI.
            coEvery { dao.allIds() } returns emptyList()

            build().doWork()

            coVerify(exactly = 1) { rateLimitStore.clear() }
        }

    @Test fun `skips the entire run while inside a recorded rate-limit window`() =
        runTest(mainRule.testScheduler) {
            // A 1-hour ban recorded just now: the worker must not even
            // call the API. Spotify can extend the penalty if it sees a
            // request during the announced wait.
            rateLimitFlow.value = RateLimitState(
                retryAfterSeconds = 3600,
                recordedAtEpochMs = System.currentTimeMillis(),
            )

            val result = build().doWork()

            // Success (not retry): retry would let WorkManager backoff
            // re-fire before the ban window closes. We want a deterministic
            // no-op until the next regular trigger.
            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify(exactly = 0) { artistRepo.fetchAllTrackUrisForArtist(any()) }
            coVerify(exactly = 0) { dao.allIds() }
            // Don't clear the store either — the user still needs to see
            // the countdown.
            coVerify(exactly = 0) { rateLimitStore.clear() }
        }

    @Test fun `proceeds when a previously recorded rate-limit has elapsed`() =
        runTest(mainRule.testScheduler) {
            // Record a 1s wait that was made well in the past: the
            // remaining-seconds calc returns 0 and the run goes ahead.
            // A subsequent successful sync clears the stale record.
            rateLimitFlow.value = RateLimitState(
                retryAfterSeconds = 1,
                recordedAtEpochMs = System.currentTimeMillis() - 60_000L,
            )
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1")))

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist("a1") }
            coVerify(exactly = 1) { rateLimitStore.clear() }
        }

    // ---- Per-artist freshness skip -------------------------------------

    @Test fun `skips an artist whose slice was synced recently`() =
        runTest(mainRule.testScheduler) {
            // Synced 1 hour ago — well inside the 18 h threshold. The
            // worker must not even ask the Spotify API for this artist.
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            coEvery { poolRepo.lastSyncedEpochMsForArtist("a1") } returns
                System.currentTimeMillis() - 60L * 60 * 1000

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify(exactly = 0) { artistRepo.fetchAllTrackUrisForArtist("a1") }
        }

    @Test fun `still fetches an artist whose slice has aged past the threshold`() =
        runTest(mainRule.testScheduler) {
            // Synced 24 h ago — past the 18 h threshold. The periodic
            // worker must pick this up; otherwise the pool would freeze
            // after the first sync.
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            coEvery { poolRepo.lastSyncedEpochMsForArtist("a1") } returns
                System.currentTimeMillis() - 24L * 60 * 60 * 1000
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1")))

            build().doWork()

            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist("a1") }
        }

    @Test fun `force ignores the freshness skip`() =
        runTest(mainRule.testScheduler) {
            // Even though the slice was just synced, force=true (Settings
            // override after the rate-limit dialog) bypasses the skip.
            // Otherwise the Force button could silently do nothing.
            stubIds(allIds = listOf("a1"), activeIds = listOf("a1"))
            coEvery { poolRepo.lastSyncedEpochMsForArtist("a1") } returns
                System.currentTimeMillis()
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1")))

            build(force = true).doWork()

            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist("a1") }
        }

    private fun stubIds(allIds: List<String>, activeIds: List<String>) {
        coEvery { dao.allIds() } returns allIds
        coEvery { dao.activeIds() } returns activeIds
    }

    private fun build(force: Boolean = false): PoolSyncWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        if (force) every { params.inputData } returns
            Data.Builder().putBoolean(PoolSyncWorker.KEY_FORCE, true).build()
        return PoolSyncWorker(
            appContext = mockk(relaxed = true),
            params = params,
            artistRepo = artistRepo,
            poolRepo = poolRepo,
            whitelistDao = dao,
            log = mockk<LogSink>(relaxed = true),
            rateLimitStore = rateLimitStore,
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

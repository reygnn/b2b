package com.github.reygnn.b2b.work

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class PoolSyncWorkerTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val artistRepo: ArtistRepository = mockk()
    private val poolRepo: PoolRepository = mockk(relaxUnitFun = true)
    private val dao: WhitelistDao = mockk()

    @Test fun `when whitelist empty then succeeds and prunes pool`() =
        runTest(mainRule.testScheduler) {
            coEvery { dao.allIds() } returns emptyList()

            val worker = build()
            val result = worker.doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify(exactly = 1) { poolRepo.deleteTracksForRemovedArtists(emptySet()) }
        }

    @Test fun `fetches tracks for each whitelisted artist and prunes others`() =
        runTest(mainRule.testScheduler) {
            coEvery { dao.allIds() } returns listOf("a1", "a2")
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1")))
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a2") } returns
                Outcome.Success(listOf(track("t2", "a2"), track("t3", "a2")))

            val worker = build()
            val result = worker.doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify { poolRepo.upsertTracks(listOf(track("t1", "a1"))) }
            coVerify { poolRepo.upsertTracks(listOf(track("t2", "a2"), track("t3", "a2"))) }
            coVerify { poolRepo.deleteTracksForRemovedArtists(setOf("a1", "a2")) }
        }

    @Test fun `per-artist sync deletes the old pool slice before upserting the fresh one`() =
        runTest(mainRule.testScheduler) {
            coEvery { dao.allIds() } returns listOf("a1")
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(listOf(track("t1", "a1")))

            build().doWork()

            // Delete must precede upsert so leftover tracks from an earlier
            // sync (e.g. ones the old buggy filter left under this artist's
            // id) are evicted, not just overlaid where URIs collide.
            coVerifyOrder {
                poolRepo.deleteTracksForArtist("a1")
                poolRepo.upsertTracks(listOf(track("t1", "a1")))
            }
        }

    @Test fun `when rate limited then delays and succeeds on next attempt`() =
        runTest(mainRule.testScheduler) {
            coEvery { dao.allIds() } returns listOf("a1")
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returnsMany listOf(
                Outcome.Error.RateLimited(retryAfterSeconds = 2),
                Outcome.Success(listOf(track("t1", "a1"))),
            )

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify { poolRepo.upsertTracks(listOf(track("t1", "a1"))) }
            coVerify(exactly = 2) { artistRepo.fetchAllTrackUrisForArtist("a1") }
        }

    @Test fun `when rate limited beyond budget then retries`() =
        runTest(mainRule.testScheduler) {
            coEvery { dao.allIds() } returns listOf("a1")
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 1)

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            coVerify(exactly = 3) { artistRepo.fetchAllTrackUrisForArtist("a1") }
        }

    @Test fun `when network error then retries`() = runTest(mainRule.testScheduler) {
        coEvery { dao.allIds() } returns listOf("a1")
        coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns Outcome.Error.Network

        val result = build().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist("a1") }
    }

    @Test fun `when unauthenticated then fails`() = runTest(mainRule.testScheduler) {
        coEvery { dao.allIds() } returns listOf("a1")
        coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns Outcome.Error.Unauthenticated

        val result = build().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
    }

    private fun build(): PoolSyncWorker = PoolSyncWorker(
        appContext = mockk(relaxed = true),
        params = mockk<WorkerParameters>(relaxed = true),
        artistRepo = artistRepo,
        poolRepo = poolRepo,
        whitelistDao = dao,
        log = mockk<LogSink>(relaxed = true),
    )

    private fun track(uri: String, artistId: String) = Track(
        uri = uri,
        name = uri,
        artistId = artistId,
        artistName = "n",
        durationMs = 1_000,
    )
}

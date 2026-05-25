package com.github.reygnn.b2b.work

import androidx.work.WorkerParameters
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.repository.RateLimitState
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.diagnostics.SpotifyCallCounter
import com.github.reygnn.b2b.diagnostics.SpotifyCallFamily
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * NEW-ARTISTS.md M4: verifies the worker's end-of-tick stats line.
 *
 * Separate from [PoolSyncWorkerTest] so that the trickle's existing
 * Result/mock contracts stay readable. The stats-emission concerns split
 * into three small invariants: format, no-emit-on-empty,
 * no-emit-during-rate-limit-skip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoolSyncWorkerStatsLineTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val artistRepo: ArtistRepository = mockk()
    private val poolRepo: PoolRepository = mockk(relaxUnitFun = true)
    private val dao: WhitelistDao = mockk()
    private val log: LogSink = mockk(relaxed = true)
    private val rateLimitStore: RateLimitStore = mockk(relaxed = true)
    private val rateLimitFlow = MutableStateFlow<RateLimitState?>(null)
    private val counter = SpotifyCallCounter()

    @Test fun `emits a formatted stats line at the end of an idle tick`() =
        runTest(mainRule.testScheduler) {
            // Pre-seed the counter with a representative spread of calls
            // so the format string can be matched literally. (Idle tick
            // chosen because it has the smallest surface area; the same
            // emit fires on success too — verified by the no-emit checks.)
            every { rateLimitStore.state() } returns rateLimitFlow
            counter.record(SpotifyCallFamily.SEARCH)
            counter.record(SpotifyCallFamily.SEARCH)
            counter.record(SpotifyCallFamily.QUEUE)
            coEvery { dao.pickNextToSync(any()) } returns null

            build().doWork()

            // capturedLines() returns every log.log(...) invocation. We
            // pick the stats line out specifically because the idle path
            // also emits a "sync: nothing eligible, idle" entry.
            val statsLine = capturedLines().single { it.startsWith("stats 24h:") }
            assertThat(statsLine).isEqualTo(
                "stats 24h: search=2 artists=0 albums=0 queue=1 me=0 token=0 other=0 total=3",
            )
        }

    @Test fun `does not emit a stats line when 24h has had no calls`() =
        runTest(mainRule.testScheduler) {
            // A first-launch / post-cleared scenario: counter is empty.
            // The trickle should not pollute the log with an all-zeros line.
            every { rateLimitStore.state() } returns rateLimitFlow
            coEvery { dao.pickNextToSync(any()) } returns null

            build().doWork()

            assertThat(capturedLines().any { it.startsWith("stats 24h:") }).isFalse()
        }

    @Test fun `does not emit a stats line during a rate-limit skip`() =
        runTest(mainRule.testScheduler) {
            // Even with non-zero counter content, the rate-limit-skip
            // branch must not emit a stats line. Otherwise during a 20 h
            // penalty WorkManager would fire ~80 ticks and the same line
            // would crowd out everything else in the log buffer.
            every { rateLimitStore.state() } returns rateLimitFlow
            counter.record(SpotifyCallFamily.QUEUE)
            rateLimitFlow.value = RateLimitState(
                retryAfterSeconds = 3_600,
                recordedAtEpochMs = System.currentTimeMillis(),
            )

            build().doWork()

            assertThat(capturedLines().any { it.startsWith("stats 24h:") }).isFalse()
        }

    @Test fun `emits a stats line at the end of a successful fetch tick`() =
        runTest(mainRule.testScheduler) {
            // Pins that the success branch also goes through the emit. We
            // pre-seed the counter (since the fake artistRepo here isn't
            // actually hitting HTTP, nothing would be recorded by the
            // interceptor); the test only proves that the emit path is
            // reached after Result.Success.
            every { rateLimitStore.state() } returns rateLimitFlow
            counter.record(SpotifyCallFamily.ARTISTS)
            coEvery { dao.pickNextToSync(any()) } returns "a1"
            coEvery { dao.allIds() } returns listOf("a1")
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(emptyList())

            build().doWork()

            assertThat(capturedLines().any { it.startsWith("stats 24h:") }).isTrue()
        }

    private fun capturedLines(): List<String> {
        val slot = mutableListOf<String>()
        verify { log.log(capture(slot)) }
        return slot
    }

    private fun build() = PoolSyncWorker(
        appContext = mockk(relaxed = true),
        params = mockk<WorkerParameters>(relaxed = true),
        artistRepo = artistRepo,
        poolRepo = poolRepo,
        whitelistDao = dao,
        log = log,
        rateLimitStore = rateLimitStore,
        callCounter = counter,
    )
}

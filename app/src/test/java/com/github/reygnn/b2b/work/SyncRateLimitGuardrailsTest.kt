package com.github.reygnn.b2b.work

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.repository.RateLimitState
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.domain.model.Outcome
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
 * Rate-limit safety regression guards for the trickle design (ADR-0003).
 *
 * Each test pins a structural property whose violation would reproduce the
 * 2026-05-22/23 Spotify-penalty incident. They duplicate a few assertions
 * from [PoolSyncWorkerTest] on purpose — keeping them in one file means
 * a future change can be reviewed against a single "is rate-limit safety
 * still intact?" suite, and a CI failure here points at the right ADR.
 *
 * Run: `./gradlew :app:testDebugUnitTest --tests "*SyncRateLimitGuardrails*"`.
 *
 * Invariants pinned:
 *  1. **At most one Spotify fetch per `doWork()` invocation**, regardless
 *     of outcome. A regression here is what turned a single user click
 *     into hours of hammering through an announced penalty.
 *  2. **No in-run `delay()` on `RateLimitedError`.** Virtual time must
 *     barely advance during a 429-handling tick — backoff is delegated
 *     to WorkManager.
 *  3. **The active-skip is unconditional.** If [RateLimitStore] reports
 *     a non-zero remaining wait, the worker exits without an API call.
 *     No input-data flag may bypass this — `force=true` was the original
 *     incident's root cause and stays retired.
 *  4. **`PoolSyncWorkNames.ALL` has exactly one lane.** Two lanes
 *     running in parallel against the same already-rate-limited artist
 *     is the second half of the 2026-05-22 failure.
 *  5. **Named regression for the incident's exact Retry-After (22316 s).**
 *     The most direct check that the path which failed then is structurally
 *     immune now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncRateLimitGuardrailsTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val artistRepo: ArtistRepository = mockk()
    private val poolRepo: PoolRepository = mockk(relaxUnitFun = true)
    private val dao: WhitelistDao = mockk()
    private val rateLimitStore: RateLimitStore = mockk(relaxed = true)

    @Before fun stubDefaults() {
        // Default per-test: no recorded wait. Tests that exercise the
        // active-skip override this with a non-null state. Stubbing
        // explicitly (rather than relying on the relaxed mock's `.value`
        // defaulting to null) keeps the contract obvious and isolates
        // tests against MockK's relaxed-default behavior changing.
        every { rateLimitStore.state() } returns MutableStateFlow<RateLimitState?>(null)
    }

    // ---- 1. At most one fetch per doWork --------------------------------

    @Test fun `success outcome triggers exactly one fetch`() =
        runTest(mainRule.testScheduler) {
            arrangeSingleArtist()
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Success(emptyList())

            build().doWork()

            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist(any()) }
        }

    @Test fun `rate-limited outcome triggers exactly one fetch`() =
        runTest(mainRule.testScheduler) {
            arrangeSingleArtist()
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 42)

            build().doWork()

            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist(any()) }
        }

    @Test fun `network outcome triggers exactly one fetch`() =
        runTest(mainRule.testScheduler) {
            arrangeSingleArtist()
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.Network

            build().doWork()

            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist(any()) }
        }

    @Test fun `auth outcome triggers exactly one fetch`() =
        runTest(mainRule.testScheduler) {
            arrangeSingleArtist()
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.Unauthenticated

            build().doWork()

            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist(any()) }
        }

    // ---- 2. No in-run delay on 429 --------------------------------------

    @Test fun `429 handling completes without burning virtual time on delay`() =
        runTest(mainRule.testScheduler) {
            // The old worker waited `retryAfterSeconds * 1000L` in-run before
            // retrying within the same doWork. The trickle MUST NOT do that:
            // a single doWork that suspended for hours would hold a wakelock
            // / foreground budget and (when combined with WorkManager
            // retries that don't know about it) reproduce the burst.
            arrangeSingleArtist()
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 30)

            val start = mainRule.testScheduler.currentTime
            val result = build().doWork()
            val elapsed = mainRule.testScheduler.currentTime - start

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            // Generous ceiling so future logging / book-keeping doesn't
            // false-positive; the point is that no `delay(seconds*1000)`
            // suspended us for the announced wait.
            assertThat(elapsed).isLessThan(1_000L)
        }

    // ---- 3. RateLimitStore active-skip is unconditional -----------------

    @Test fun `worker skips the tick entirely when RateLimitStore has a long active wait`() =
        runTest(mainRule.testScheduler) {
            // Spotify's own advice ("respect Retry-After") and our incident
            // history both say: do not issue a request during the announced
            // wait. The trickle's active-skip is unconditional — there is
            // no `force` flag, no input-data override, no caller-driven
            // bypass. This was the WRONG invariant in the first revision
            // of this test (it asserted "proceeds"); the corrected version
            // asserts the skip.
            every { rateLimitStore.state() } returns MutableStateFlow(
                RateLimitState(
                    retryAfterSeconds = 3_600,
                    recordedAtEpochMs = System.currentTimeMillis(),
                )
            )

            val result = build().doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            // No fetch, no DAO call — total no-op during the announced wait.
            coVerify(exactly = 0) { artistRepo.fetchAllTrackUrisForArtist(any()) }
        }

    @Test fun `no inputData flag can bypass the active-skip (force-flag stays retired)`() =
        runTest(mainRule.testScheduler) {
            // Regression guard for the 2026-05-22/23 incident's root cause:
            // a `force=true` input-data flag that WorkManager preserved
            // across backoff retries and let hammer through the announced
            // wait. This test spams several truthy-named flags into
            // inputData; the worker must ignore all of them and respect
            // the active-skip unconditionally.
            every { rateLimitStore.state() } returns MutableStateFlow(
                RateLimitState(
                    retryAfterSeconds = 3_600,
                    recordedAtEpochMs = System.currentTimeMillis(),
                )
            )
            val noisyParams = mockk<WorkerParameters>(relaxed = true)
            every { noisyParams.inputData } returns androidx.work.Data.Builder()
                .putBoolean("force", true)
                .putBoolean("force_sync", true)
                .putBoolean("skip_rate_limit", true)
                .putBoolean("override", true)
                .putBoolean("bypass", true)
                .build()
            val worker = PoolSyncWorker(
                appContext = mockk(relaxed = true),
                params = noisyParams,
                artistRepo = artistRepo,
                poolRepo = poolRepo,
                whitelistDao = dao,
                log = mockk<LogSink>(relaxed = true),
                rateLimitStore = rateLimitStore,
            )

            val result = worker.doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            coVerify(exactly = 0) { artistRepo.fetchAllTrackUrisForArtist(any()) }
        }

    @Test fun `429 records the announced wait so the UI countdown stays honest`() =
        runTest(mainRule.testScheduler) {
            // The store stays in for *display* — the home card shows
            // "Rate-Limit: HH:MM:SS" while the wait is ticking. The worker
            // must record the announced wait verbatim so the countdown
            // does not lie to the user.
            arrangeSingleArtist()
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 47)

            build().doWork()

            coVerify(exactly = 1) {
                rateLimitStore.record(retryAfterSeconds = 47, any())
            }
        }

    // ---- 4. Single work-lane --------------------------------------------

    @Test fun `PoolSyncWorkNames exposes the single PERIODIC lane only`() {
        // The MANUAL lane was retired in ADR-0003. Re-adding it (or any
        // other parallel lane) would let two workers run concurrently
        // against the same already-rate-limited artist — the second half
        // of the 2026-05-22/23 failure (visible in the logs as duplicate
        // entries at 06:45:59 and an "auth: refresh coalesced" line from
        // PkceAuthManager's mutex catching the race).
        assertThat(PoolSyncWorkNames.ALL).containsExactly(PoolSyncWorkNames.PERIODIC)
    }

    // ---- 5. 2026-05-22/23 incident regression --------------------------

    @Test fun `2026-05-22 incident regression — 22316s Retry-After is handled safely`() =
        runTest(mainRule.testScheduler) {
            // The exact value Spotify returned at 20:50:17 on 2026-05-22.
            // The old design's response was: record, exceed cap, retry. The
            // *retry* (with force=true input data preserved by WorkManager)
            // then hammered through the announced 6 h penalty over the next
            // 10 hours. The trickle's response must be: record once, retry
            // once, and trust WorkManager's exponential backoff to space
            // the next attempt past the wait window. No in-run delay, no
            // second fetch in the same doWork.
            arrangeSingleArtist()
            coEvery { artistRepo.fetchAllTrackUrisForArtist("a1") } returns
                Outcome.Error.RateLimited(retryAfterSeconds = 22_316)

            val start = mainRule.testScheduler.currentTime
            val result = build().doWork()
            val elapsed = mainRule.testScheduler.currentTime - start

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            coVerify(exactly = 1) { artistRepo.fetchAllTrackUrisForArtist(any()) }
            coVerify(exactly = 1) {
                rateLimitStore.record(retryAfterSeconds = 22_316, any())
            }
            // The defining safety property: no in-run delay regardless of
            // how large the Retry-After is. If this elapsed jumps to the
            // tens of thousands of seconds, the trickle has regressed back
            // into the burst era.
            assertThat(elapsed).isLessThan(1_000L)
        }

    // ---- helpers -------------------------------------------------------

    private fun arrangeSingleArtist() {
        coEvery { dao.pickNextToSync(any()) } returns "a1"
        coEvery { dao.allIds() } returns listOf("a1")
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
        )
    }
}

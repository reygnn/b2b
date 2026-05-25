package com.github.reygnn.b2b.diagnostics

import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class SpotifyCallCounterTest {

    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun `empty counter reports no families`() = runTest(mainRule.testScheduler) {
        val sut = SpotifyCallCounter().apply { clock = { 1_000L } }
        assertThat(sut.stats(window = 24.hours)).isEmpty()
    }

    @Test fun `groups records by family`() = runTest(mainRule.testScheduler) {
        var now = 1_000L
        val sut = SpotifyCallCounter().apply { clock = { now } }
        sut.record(SpotifyCallFamily.SEARCH)
        sut.record(SpotifyCallFamily.SEARCH)
        sut.record(SpotifyCallFamily.QUEUE)

        val s = sut.stats(window = 24.hours)
        assertThat(s[SpotifyCallFamily.SEARCH]).isEqualTo(2)
        assertThat(s[SpotifyCallFamily.QUEUE]).isEqualTo(1)
        assertThat(s).doesNotContainKey(SpotifyCallFamily.ARTISTS)
    }

    @Test fun `stats window excludes entries older than the cutoff`() =
        runTest(mainRule.testScheduler) {
            // record at t=0, then again at t=2h, then ask for the last 1h:
            // only the second record falls into the window.
            var now = 0L
            val sut = SpotifyCallCounter().apply { clock = { now } }
            sut.record(SpotifyCallFamily.SEARCH)
            now += 2.hours.inWholeMilliseconds
            sut.record(SpotifyCallFamily.SEARCH)

            val s = sut.stats(window = 1.hours, nowMs = now)
            assertThat(s[SpotifyCallFamily.SEARCH]).isEqualTo(1)
        }

    @Test fun `entries past 24h are pruned on record`() = runTest(mainRule.testScheduler) {
        // Pins the eager-prune invariant: once we record a call beyond the
        // 24 h retention window, earlier entries must be evicted so the
        // internal deque size cannot grow unbounded across days.
        var now = 0L
        val sut = SpotifyCallCounter().apply { clock = { now } }
        sut.record(SpotifyCallFamily.SEARCH)
        sut.record(SpotifyCallFamily.QUEUE)

        // 25 h later, a single new call should evict the pre-window two.
        now += 25.hours.inWholeMilliseconds
        sut.record(SpotifyCallFamily.ME)

        val s = sut.stats(window = 24.hours, nowMs = now)
        assertThat(s).containsExactly(SpotifyCallFamily.ME, 1)
    }

    @Test fun `stats also prunes on read so a long-idle counter stays small`() =
        runTest(mainRule.testScheduler) {
            // Pins the opportunistic prune inside stats(): a process that
            // records a burst, then sits quiet for >24 h, then is queried,
            // must drop the stale entries even though no further record()
            // has fired.
            var now = 0L
            val sut = SpotifyCallCounter().apply { clock = { now } }
            sut.record(SpotifyCallFamily.SEARCH)
            sut.record(SpotifyCallFamily.SEARCH)

            now += 30.hours.inWholeMilliseconds
            val s = sut.stats(window = 24.hours, nowMs = now)
            assertThat(s).isEmpty()
        }

    @Test fun `entries exactly at the cutoff are included`() =
        runTest(mainRule.testScheduler) {
            // Boundary check: a call at t=window-ago is still within the
            // window per the `>=` filter, so it must be counted.
            var now = 0L
            val sut = SpotifyCallCounter().apply { clock = { now } }
            sut.record(SpotifyCallFamily.SEARCH)

            now += 24.hours.inWholeMilliseconds
            // Cutoff is now - 24h = original record time. >= keeps it in.
            val s = sut.stats(window = 24.hours, nowMs = now)
            assertThat(s[SpotifyCallFamily.SEARCH]).isEqualTo(1)
        }

    @Test fun `narrow window only sees recent records`() = runTest(mainRule.testScheduler) {
        // Sanity check that the window arg is honoured (not just 24h
        // hard-coded somewhere).
        var now = 0L
        val sut = SpotifyCallCounter().apply { clock = { now } }
        sut.record(SpotifyCallFamily.SEARCH)
        now += 10.minutes.inWholeMilliseconds
        sut.record(SpotifyCallFamily.QUEUE)

        val s = sut.stats(window = 5.minutes, nowMs = now)
        assertThat(s).containsExactly(SpotifyCallFamily.QUEUE, 1)
    }

    // ---- fromPath classification --------------------------------------

    @Test fun `fromPath maps every known Spotify path family`() {
        // Each row is the exact path encodedPath form OkHttp returns for
        // the corresponding SpotifyApi method.
        assertThat(SpotifyCallFamily.fromPath("/v1/search"))
            .isEqualTo(SpotifyCallFamily.SEARCH)
        assertThat(SpotifyCallFamily.fromPath("/v1/artists/abc/albums"))
            .isEqualTo(SpotifyCallFamily.ARTISTS)
        assertThat(SpotifyCallFamily.fromPath("/v1/albums/def/tracks"))
            .isEqualTo(SpotifyCallFamily.ALBUMS)
        assertThat(SpotifyCallFamily.fromPath("/v1/me/player/queue"))
            .isEqualTo(SpotifyCallFamily.QUEUE)
        assertThat(SpotifyCallFamily.fromPath("/v1/me"))
            .isEqualTo(SpotifyCallFamily.ME)
        assertThat(SpotifyCallFamily.fromPath("/api/token"))
            .isEqualTo(SpotifyCallFamily.TOKEN)
        assertThat(SpotifyCallFamily.fromPath("/v1/something-new"))
            .isEqualTo(SpotifyCallFamily.OTHER)
    }

    @Test fun `queue path is not classified as ME because of prefix-order`() {
        // Regression guard: /v1/me/player/queue starts with /v1/me — if the
        // when-clauses are ever reordered so ME's exact-match comes before
        // the queue prefix, this catches it. (ME is exact-match today so
        // this is already safe; the test pins the contract.)
        assertThat(SpotifyCallFamily.fromPath("/v1/me/player/queue"))
            .isEqualTo(SpotifyCallFamily.QUEUE)
    }
}

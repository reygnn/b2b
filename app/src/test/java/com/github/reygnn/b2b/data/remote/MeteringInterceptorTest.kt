package com.github.reygnn.b2b.data.remote

import com.github.reygnn.b2b.data.auth.TokenStore
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.diagnostics.SpotifyCallCounter
import com.github.reygnn.b2b.diagnostics.SpotifyCallFamily
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/**
 * Demonstrates: the interceptor records one call per HTTP round-trip,
 * classified by URL path, including 401-retries that AuthInterceptor
 * issues on its own (network-interceptor placement is what makes that
 * work — application-level placement would only see the final response).
 */
class MeteringInterceptorTest {

    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun `records the family for each Spotify path`() = runTest(mainRule.testScheduler) {
        val counter = SpotifyCallCounter().apply { clock = { 1_000L } }
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200))
            enqueue(MockResponse().setResponseCode(200))
            enqueue(MockResponse().setResponseCode(200))
            enqueue(MockResponse().setResponseCode(200))
            enqueue(MockResponse().setResponseCode(200))
            enqueue(MockResponse().setResponseCode(200))
        }
        server.start()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(MeteringInterceptor(counter, mockk<LogSink>(relaxed = true)))
            .build()

        fun hit(path: String) {
            client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
        }
        hit("/v1/search?q=foo&type=artist")
        hit("/v1/artists/abc/albums")
        hit("/v1/albums/def/tracks")
        hit("/v1/me/player/queue?uri=spotify%3Atrack%3A1")
        hit("/v1/me")
        hit("/api/token")

        val s = counter.stats(window = 24.hours, nowMs = 1_000L)
        assertThat(s[SpotifyCallFamily.SEARCH]).isEqualTo(1)
        assertThat(s[SpotifyCallFamily.ARTISTS]).isEqualTo(1)
        assertThat(s[SpotifyCallFamily.ALBUMS]).isEqualTo(1)
        assertThat(s[SpotifyCallFamily.QUEUE]).isEqualTo(1)
        assertThat(s[SpotifyCallFamily.ME]).isEqualTo(1)
        assertThat(s[SpotifyCallFamily.TOKEN]).isEqualTo(1)
        server.shutdown()
    }

    @Test fun `records 401 plus retry as two calls`() = runTest(mainRule.testScheduler) {
        // Pins the network-interceptor placement choice: AuthInterceptor's
        // silent 401-refresh-retry must show up as two records, because
        // that's two actual round-trips Spotify's rate limit charged us
        // for. An application interceptor here would only count the
        // outermost call (1) and under-report.
        val counter = SpotifyCallCounter().apply { clock = { 1_000L } }
        val tokenStore = mockk<TokenStore>()
        coEvery { tokenStore.accessToken() } returns "stale"
        coEvery { tokenStore.refresh(staleAccessToken = "stale") } returns "fresh"

        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(401))
            enqueue(MockResponse().setResponseCode(200))
        }
        server.start()
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .addNetworkInterceptor(MeteringInterceptor(counter, mockk<LogSink>(relaxed = true)))
            .build()

        client.newCall(Request.Builder().url(server.url("/v1/me")).build()).execute().close()

        assertThat(counter.stats(window = 24.hours, nowMs = 1_000L)[SpotifyCallFamily.ME])
            .isEqualTo(2)
        server.shutdown()
    }

    @Test fun `records on non-2xx without throwing`() = runTest(mainRule.testScheduler) {
        // A 429 still costs Spotify-side quota; we want it counted. Also
        // serves as a sanity check that the interceptor doesn't blow up
        // on error responses.
        val counter = SpotifyCallCounter().apply { clock = { 1_000L } }
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(429))
        }
        server.start()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(MeteringInterceptor(counter, mockk<LogSink>(relaxed = true)))
            .build()

        client.newCall(Request.Builder().url(server.url("/v1/artists/x/albums")).build())
            .execute().close()

        assertThat(counter.stats(window = 24.hours, nowMs = 1_000L)[SpotifyCallFamily.ARTISTS])
            .isEqualTo(1)
        server.shutdown()
    }
}

package com.github.reygnn.b2b.data.auth

import com.github.reygnn.b2b.data.remote.AuthInterceptor
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test

/**
 * Demonstrates: the interceptor adds `Authorization`, and on 401 it calls
 * `tokenStore.refresh(staleAccessToken = …)` exactly once and retries.
 *
 * Note: MockWebServer is on the test classpath via okhttp transitively;
 * if not, add `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`
 * to app/build.gradle.kts.
 */
class AuthInterceptorTest {

    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun `attaches bearer token on first request`() = runTest(mainRule.testScheduler) {
        val tokenStore = mockk<TokenStore>()
        coEvery { tokenStore.accessToken() } returns "initial-token"

        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200)) }
        server.start()

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .build()

        client.newCall(Request.Builder().url(server.url("/v1/me")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer initial-token")
        server.shutdown()
    }

    @Test fun `refreshes and retries once on 401`() = runTest(mainRule.testScheduler) {
        val tokenStore = mockk<TokenStore>()
        coEvery { tokenStore.accessToken() } returns "stale-token"
        // The interceptor must pass the token that triggered the 401 as the
        // coalescing anchor — PkceAuthManager uses this to skip the HTTP
        // refresh when another caller has already produced a fresher token.
        coEvery { tokenStore.refresh(staleAccessToken = "stale-token") } returns "fresh-token"

        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(401))
            enqueue(MockResponse().setResponseCode(200))
        }
        server.start()

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .build()
        client.newCall(Request.Builder().url(server.url("/v1/me")).build()).execute().close()

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer stale-token")
        assertThat(second.getHeader("Authorization")).isEqualTo("Bearer fresh-token")
        coVerify(exactly = 1) { tokenStore.refresh(staleAccessToken = "stale-token") }
        server.shutdown()
    }

    @Test fun `on failed refresh surfaces the original 401 without duplicate request`() =
        runTest(mainRule.testScheduler) {
            // Pins the no-duplicate-request fix. The pre-fix code path issued
            // a second `chain.proceed(authed)` with the still-stale token on
            // null refresh — guaranteed second 401, wasted round-trip. With
            // the fix, the original 401 response is returned unchanged.
            val tokenStore = mockk<TokenStore>()
            coEvery { tokenStore.accessToken() } returns "stale-token"
            coEvery { tokenStore.refresh(staleAccessToken = "stale-token") } returns null

            val server = MockWebServer().apply {
                enqueue(MockResponse().setResponseCode(401))
            }
            server.start()

            val client = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(tokenStore))
                .build()
            val response = client
                .newCall(Request.Builder().url(server.url("/v1/me")).build())
                .execute()

            assertThat(response.code).isEqualTo(401)
            assertThat(server.requestCount).isEqualTo(1)
            response.close()
            server.shutdown()
        }
}

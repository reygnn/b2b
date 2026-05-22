package com.github.reygnn.b2b.data.auth

import com.github.reygnn.b2b.data.remote.SpotifyAccountsApi
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class PkceAuthManagerTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var api: SpotifyAccountsApi
    private val tokenStore = mockk<TokenStore>(relaxUnitFun = true)

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpotifyAccountsApi::class.java)
        // Refresh paths read sessionEpoch() before the HTTP round-trip. Stub
        // a stable value here so the per-test setup only needs to override
        // it for the logout-during-refresh case.
        every { tokenStore.sessionEpoch() } returns 0L
        // Every successful refresh persists via storeIfMatchingEpoch with
        // the captured epoch. Default-stub to "epoch matched, write went
        // through" so tests don't have to rebuild this every time; the
        // discard test overrides it.
        coEvery {
            tokenStore.storeIfMatchingEpoch(any(), any(), any(), any())
        } returns true
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `exchangeAuthorizationCode stores tokens on success`() =
        runTest(mainRule.testScheduler) {
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"acc","token_type":"Bearer","expires_in":3600,"refresh_token":"ref","scope":"x"}"""
                )
            )

            val sut = newSut()
            sut.buildAuthorizationUri(scopes = listOf("scope-a"))  // primes pendingVerifier

            // Exchange uses storeFromInitialExchange (no epoch check) — the
            // initial OAuth flow has no prior session to race against.
            val accessSlot = slot<String>()
            val refreshSlot = slot<String>()
            val expiresSlot = slot<Long>()
            coEvery {
                tokenStore.storeFromInitialExchange(capture(accessSlot), capture(refreshSlot), capture(expiresSlot))
            } returns Unit

            val now = System.currentTimeMillis()
            val result = sut.exchangeAuthorizationCode(code = "auth-code")

            assertThat(result).isInstanceOf(Outcome.Success::class.java)
            assertThat(accessSlot.captured).isEqualTo("acc")
            assertThat(refreshSlot.captured).isEqualTo("ref")
            assertThat(expiresSlot.captured).isAtLeast(now + 3600 * 1000L)

            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/token")
            val body = recorded.body.readUtf8()
            assertThat(body).contains("grant_type=authorization_code")
            assertThat(body).contains("code=auth-code")
            assertThat(body).contains("client_id=test-client")
            assertThat(body).contains("redirect_uri=b2b")
            assertThat(body).contains("code_verifier=")
        }

    @Test fun `exchangeAuthorizationCode fails when no pending verifier`() =
        runTest(mainRule.testScheduler) {
            val sut = newSut()
            // No buildAuthorizationUri() call → pendingVerifier is null.

            val result = sut.exchangeAuthorizationCode(code = "auth-code")

            assertThat(result).isInstanceOf(Outcome.Error.Unknown::class.java)
            coVerify(exactly = 0) { tokenStore.storeFromInitialExchange(any(), any(), any()) }
        }

    @Test fun `exchangeAuthorizationCode surfaces HTTP error as Outcome Error Unknown`() =
        runTest(mainRule.testScheduler) {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            val sut = newSut()
            sut.buildAuthorizationUri(scopes = listOf("scope-a"))

            val result = sut.exchangeAuthorizationCode(code = "bad-code")

            assertThat(result).isInstanceOf(Outcome.Error.Unknown::class.java)
            coVerify(exactly = 0) { tokenStore.storeFromInitialExchange(any(), any(), any()) }
        }

    @Test fun `exchangeAuthorizationCode consumes pending verifier even on error`() =
        runTest(mainRule.testScheduler) {
            server.enqueue(MockResponse().setResponseCode(500))
            val sut = newSut()
            sut.buildAuthorizationUri(scopes = listOf("scope-a"))

            sut.exchangeAuthorizationCode(code = "code")

            // Second call without a fresh buildAuthorizationUri() must report
            // no pending verifier — codes are single-use.
            server.enqueue(MockResponse().setBody("""{"access_token":"a","token_type":"Bearer","expires_in":1,"refresh_token":"r"}"""))
            val second = sut.exchangeAuthorizationCode(code = "code")
            assertThat(second).isInstanceOf(Outcome.Error.Unknown::class.java)
        }

    @Test fun `refresh returns new access token and stores it`() =
        runTest(mainRule.testScheduler) {
            coEvery { tokenStore.accessToken() } returns "stale"
            coEvery { tokenStore.refreshToken() } returns "stored-refresh"
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"new-acc","token_type":"Bearer","expires_in":3600}"""
                )
            )

            // Refresh path uses storeIfMatchingEpoch, NOT store, so the
            // captured-then-cleared session check is wired correctly.
            val accessSlot = slot<String>()
            val refreshSlot = slot<String>()
            coEvery {
                tokenStore.storeIfMatchingEpoch(
                    any(), capture(accessSlot), capture(refreshSlot), any()
                )
            } returns true

            val result = newSut().refresh(staleAccessToken = "stale")

            assertThat(result).isEqualTo("new-acc")
            assertThat(accessSlot.captured).isEqualTo("new-acc")
            // Spotify omitted refresh_token → keep the existing one.
            assertThat(refreshSlot.captured).isEqualTo("stored-refresh")

            val recordedBody = server.takeRequest().body.readUtf8()
            assertThat(recordedBody).contains("grant_type=refresh_token")
            assertThat(recordedBody).contains("refresh_token=stored-refresh")
        }

    @Test fun `refresh stores rotated refresh token when Spotify sends one`() =
        runTest(mainRule.testScheduler) {
            coEvery { tokenStore.accessToken() } returns "stale"
            coEvery { tokenStore.refreshToken() } returns "old-refresh"
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"acc","token_type":"Bearer","expires_in":3600,"refresh_token":"rotated"}"""
                )
            )
            val refreshSlot = slot<String>()
            coEvery {
                tokenStore.storeIfMatchingEpoch(
                    any(), any(), capture(refreshSlot), any()
                )
            } returns true

            newSut().refresh(staleAccessToken = "stale")

            assertThat(refreshSlot.captured).isEqualTo("rotated")
        }

    @Test fun `refresh returns null when no refresh token stored`() =
        runTest(mainRule.testScheduler) {
            coEvery { tokenStore.accessToken() } returns "stale"
            coEvery { tokenStore.refreshToken() } returns null

            val result = newSut().refresh(staleAccessToken = "stale")

            assertThat(result).isNull()
            coVerify(exactly = 0) {
                tokenStore.storeIfMatchingEpoch(any(), any(), any(), any())
            }
            assertThat(server.requestCount).isEqualTo(0)
        }

    @Test fun `refresh returns null on HTTP error`() = runTest(mainRule.testScheduler) {
        coEvery { tokenStore.accessToken() } returns "stale"
        coEvery { tokenStore.refreshToken() } returns "stored-refresh"
        server.enqueue(MockResponse().setResponseCode(401))

        val result = newSut().refresh(staleAccessToken = "stale")

        assertThat(result).isNull()
        coVerify(exactly = 0) {
            tokenStore.storeIfMatchingEpoch(any(), any(), any(), any())
        }
    }

    @Test fun `refresh coalesces when access token already rotated under the caller`() =
        runTest(mainRule.testScheduler) {
            // Pins the concurrent-refresh-race fix: if another caller refreshed
            // while we were waiting for the mutex (modelled here by the store
            // already holding a different access token), we must not issue a
            // redundant HTTP request — that would invalidate the rotated
            // refresh-token chain.
            coEvery { tokenStore.accessToken() } returns "already-fresh"

            val result = newSut().refresh(staleAccessToken = "stale-from-401")

            assertThat(result).isEqualTo("already-fresh")
            assertThat(server.requestCount).isEqualTo(0)
            coVerify(exactly = 0) {
                tokenStore.storeIfMatchingEpoch(any(), any(), any(), any())
            }
        }

    @Test fun `refresh with null staleAccessToken forces network refresh`() =
        runTest(mainRule.testScheduler) {
            // Null anchor (e.g. tests, or callers without a prior 401) must
            // not engage the coalescing short-circuit — they have no basis
            // for inferring "someone else already refreshed".
            coEvery { tokenStore.accessToken() } returns "current"
            coEvery { tokenStore.refreshToken() } returns "stored-refresh"
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"new-acc","token_type":"Bearer","expires_in":3600}"""
                )
            )

            val result = newSut().refresh(staleAccessToken = null)

            assertThat(result).isEqualTo("new-acc")
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test fun `refresh discards result when session epoch advances during HTTP`() =
        runTest(mainRule.testScheduler) {
            // Pins the logout-during-refresh fix: doRefresh captures the
            // session epoch BEFORE the HTTP round-trip and passes it to
            // storeIfMatchingEpoch. If TokenStore.clear() (logout) bumped the
            // epoch in the meantime, the store call returns false and the
            // refresh result is dropped — no silent re-creation of tokens
            // after the user intended to sign out.
            coEvery { tokenStore.accessToken() } returns "stale"
            coEvery { tokenStore.refreshToken() } returns "stored-refresh"
            every { tokenStore.sessionEpoch() } returns 7L
            // Modelled: clear() advanced the epoch past 7 while we were on
            // the network, so the epoch-7 write is rejected.
            coEvery {
                tokenStore.storeIfMatchingEpoch(7L, any(), any(), any())
            } returns false
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"new-acc","token_type":"Bearer","expires_in":3600}"""
                )
            )

            val result = newSut().refresh(staleAccessToken = "stale")

            // Discarded — caller sees null and surfaces the original 401.
            assertThat(result).isNull()
            // Repository was asked to persist with the captured epoch; it
            // declined, which is what we are pinning.
            coVerify(exactly = 1) {
                tokenStore.storeIfMatchingEpoch(7L, "new-acc", "stored-refresh", any())
            }
            // Single HTTP attempt — no retry/loop on the discard path.
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test fun `refresh persists with the captured session epoch on the happy path`() =
        runTest(mainRule.testScheduler) {
            // Sibling to the discard test: pins the matching-epoch path. The
            // captured sessionEpoch value travels through unchanged into
            // storeIfMatchingEpoch, so refreshes started under one session
            // commit to *that* session — not the current value at HTTP
            // completion time, which could already have advanced.
            //
            // Existing happy-path refresh tests cover this implicitly via
            // the @Before default-stub that accepts any epoch and returns
            // true. This test makes the contract explicit: the actual
            // captured value must reach the store call.
            coEvery { tokenStore.accessToken() } returns "stale"
            coEvery { tokenStore.refreshToken() } returns "stored-refresh"
            every { tokenStore.sessionEpoch() } returns 42L
            coEvery {
                tokenStore.storeIfMatchingEpoch(42L, any(), any(), any())
            } returns true
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"new-acc","token_type":"Bearer","expires_in":3600}"""
                )
            )

            val result = newSut().refresh(staleAccessToken = "stale")

            // Refresh succeeded end-to-end.
            assertThat(result).isEqualTo("new-acc")
            // The captured epoch (42) — not "any old long" — was passed
            // through to the persistence call. A future refactor that
            // re-reads sessionEpoch() at completion time, or hard-codes a
            // sentinel, would fail this assertion.
            coVerify(exactly = 1) {
                tokenStore.storeIfMatchingEpoch(42L, "new-acc", "stored-refresh", any())
            }
        }

    private fun newSut() = PkceAuthManager(
        tokenStore = tokenStore,
        accountsApi = api,
        authEvents = AuthEventBus(),
        clientId = "test-client",
        redirectUri = "b2b://callback",
        io = mainRule.testDispatcher,
        log = mockk<LogSink>(relaxed = true),
    )
}

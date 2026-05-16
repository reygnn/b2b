package com.github.reygnn.b2b.data.auth

import com.github.reygnn.b2b.data.remote.SpotifyAccountsApi
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
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

            val accessSlot = slot<String>()
            val refreshSlot = slot<String>()
            val expiresSlot = slot<Long>()
            coEvery {
                tokenStore.store(capture(accessSlot), capture(refreshSlot), capture(expiresSlot))
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
            coVerify(exactly = 0) { tokenStore.store(any(), any(), any()) }
        }

    @Test fun `exchangeAuthorizationCode surfaces HTTP error as Outcome Error Unknown`() =
        runTest(mainRule.testScheduler) {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            val sut = newSut()
            sut.buildAuthorizationUri(scopes = listOf("scope-a"))

            val result = sut.exchangeAuthorizationCode(code = "bad-code")

            assertThat(result).isInstanceOf(Outcome.Error.Unknown::class.java)
            coVerify(exactly = 0) { tokenStore.store(any(), any(), any()) }
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
            coEvery { tokenStore.refreshToken() } returns "stored-refresh"
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"new-acc","token_type":"Bearer","expires_in":3600}"""
                )
            )

            val accessSlot = slot<String>()
            val refreshSlot = slot<String>()
            coEvery {
                tokenStore.store(capture(accessSlot), capture(refreshSlot), any())
            } returns Unit

            val result = newSut().refresh()

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
            coEvery { tokenStore.refreshToken() } returns "old-refresh"
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"acc","token_type":"Bearer","expires_in":3600,"refresh_token":"rotated"}"""
                )
            )
            val refreshSlot = slot<String>()
            coEvery { tokenStore.store(any(), capture(refreshSlot), any()) } returns Unit

            newSut().refresh()

            assertThat(refreshSlot.captured).isEqualTo("rotated")
        }

    @Test fun `refresh returns null when no refresh token stored`() =
        runTest(mainRule.testScheduler) {
            coEvery { tokenStore.refreshToken() } returns null

            val result = newSut().refresh()

            assertThat(result).isNull()
            coVerify(exactly = 0) { tokenStore.store(any(), any(), any()) }
            assertThat(server.requestCount).isEqualTo(0)
        }

    @Test fun `refresh returns null on HTTP error`() = runTest(mainRule.testScheduler) {
        coEvery { tokenStore.refreshToken() } returns "stored-refresh"
        server.enqueue(MockResponse().setResponseCode(401))

        val result = newSut().refresh()

        assertThat(result).isNull()
        coVerify(exactly = 0) { tokenStore.store(any(), any(), any()) }
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

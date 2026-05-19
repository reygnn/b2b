package com.github.reygnn.b2b.data.repository

import com.github.reygnn.b2b.data.remote.SpotifyApi
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
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

class PlaybackRepositoryImplTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var api: SpotifyApi
    private lateinit var sut: PlaybackRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpotifyApi::class.java)
        sut = PlaybackRepositoryImpl(api, log = mockk(relaxed = true), mainRule.testDispatcher)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `enqueue success on HTTP 204`() = runTest(mainRule.testScheduler) {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = sut.enqueue("spotify:track:abc", "device-1")

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
    }

    @Test fun `enqueue maps 404 to NoActiveDevice (narrowed player-endpoint mapping)`() =
        runTest(mainRule.testScheduler) {
            // Pins the §B narrowing: the generic toOutcome treats 404 as
            // Unknown; PlaybackRepositoryImpl.enqueue handles 404 inline as
            // NoActiveDevice because the player-queue endpoint specifically
            // uses 404 for "no active device".
            server.enqueue(MockResponse().setResponseCode(404))

            val result = sut.enqueue("spotify:track:abc", null)

            assertThat(result).isEqualTo(Outcome.Error.NoActiveDevice)
        }

    @Test fun `enqueue maps 403 PREMIUM_REQUIRED to NotPremium`() =
        runTest(mainRule.testScheduler) {
            // Canonical terminal-session signal: Player API's structured 403.
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"status":403,"message":"Player command failed: Premium required","reason":"PREMIUM_REQUIRED"}}"""
                )
            )

            val result = sut.enqueue("spotify:track:abc", "device-1")

            assertThat(result).isEqualTo(Outcome.Error.NotPremium)
        }

    @Test fun `enqueue maps 403 with no reason but premium-required message to NotPremium`() =
        runTest(mainRule.testScheduler) {
            // Legacy / non-Player-API 403 shape — only `message`, no `reason`.
            // The substring check keeps backward compat with older Spotify
            // responses that may have triggered the original (pre-fix)
            // NotPremium path.
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"status":403,"message":"Premium required"}}"""
                )
            )

            val result = sut.enqueue("spotify:track:abc", "device-1")

            assertThat(result).isEqualTo(Outcome.Error.NotPremium)
        }

    @Test fun `enqueue maps 403 with no reason but localized Premium message to NotPremium`() =
        runTest(mainRule.testScheduler) {
            // The null-reason fallback matches the brand name "Premium"
            // case-insensitively, not the exact English phrase — should
            // Spotify ever localize their error strings the brand survives
            // ("Premium-Account erforderlich", "Cuenta Premium requerida").
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"status":403,"message":"Premium-Account erforderlich"}}"""
                )
            )

            val result = sut.enqueue("spotify:track:abc", "device-1")

            assertThat(result).isEqualTo(Outcome.Error.NotPremium)
        }

    @Test fun `enqueue maps 403 with non-Premium message to Unknown`() =
        runTest(mainRule.testScheduler) {
            // Pins the negative side of the broadened "premium" matcher:
            // a 403 whose body has no Premium mention must stay Unknown
            // so the orchestrator keeps arming triggers (RetryLater path).
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"status":403,"message":"Forbidden by region policy"}}"""
                )
            )

            val result = sut.enqueue("spotify:track:abc", "device-1")

            assertThat(result).isInstanceOf(Outcome.Error.Unknown::class.java)
            val message = (result as Outcome.Error.Unknown).message
            assertThat(message).contains("Forbidden by region policy")
        }

    @Test fun `enqueue maps bare 403 with no body to Unknown (not NotPremium)`() =
        runTest(mainRule.testScheduler) {
            // Pins the 2026-05-19 fix: a 403 with NO body must not silently
            // terminate the session as if it were a definitive Premium
            // failure. Without an explicit signal we surface as Unknown so
            // the orchestrator's `is Outcome.Error` branch maps it to
            // SpotifyUnavailable (RetryLater), not Terminal.
            server.enqueue(MockResponse().setResponseCode(403))

            val result = sut.enqueue("spotify:track:abc", "device-1")

            assertThat(result).isInstanceOf(Outcome.Error.Unknown::class.java)
            val message = (result as Outcome.Error.Unknown).message
            assertThat(message).contains("403")
        }

    @Test fun `enqueue maps 403 RESTRICTED_DEVICE to Unknown with reason in message`() =
        runTest(mainRule.testScheduler) {
            // Spotify's Player API 403 for "your device class can't run this
            // command" (often hit on cars, some speakers). MUST NOT be
            // terminal — the user can move playback to a phone/desktop and
            // we should keep arming triggers.
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"status":403,"message":"Player command failed: Restricted device","reason":"RESTRICTED_DEVICE"}}"""
                )
            )

            val result = sut.enqueue("spotify:track:abc", "device-1")

            assertThat(result).isInstanceOf(Outcome.Error.Unknown::class.java)
            val message = (result as Outcome.Error.Unknown).message
            // Both reason and human message land in the description so the
            // SpotifyUnavailable status line is diagnostic.
            assertThat(message).contains("RESTRICTED_DEVICE")
            assertThat(message).contains("Restricted device")
        }

    @Test fun `enqueue maps 403 NO_ACTIVE_DEVICE reason to NoActiveDevice`() =
        runTest(mainRule.testScheduler) {
            // Defensive: /me/player/queue uses 404 for this case today, but
            // if Spotify ever switches to 403+NO_ACTIVE_DEVICE we map it to
            // the same Outcome so the orchestrator's RetryLater + status
            // line stay correct.
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"status":403,"message":"Player command failed: No active device","reason":"NO_ACTIVE_DEVICE"}}"""
                )
            )

            val result = sut.enqueue("spotify:track:abc", null)

            assertThat(result).isEqualTo(Outcome.Error.NoActiveDevice)
        }

    @Test fun `isPremium maps 403 PREMIUM_REQUIRED on slash-me to NotPremium`() =
        runTest(mainRule.testScheduler) {
            // Session-start premium check path: the same 403 reason mapping
            // applies to /me too — confirms the helper is shared, not
            // duplicated per repository.
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"status":403,"message":"Premium required","reason":"PREMIUM_REQUIRED"}}"""
                )
            )

            val result = sut.isPremium()

            assertThat(result).isEqualTo(Outcome.Error.NotPremium)
        }

    @Test fun `isPremium maps bare 403 on slash-me to Unknown (not NotPremium)`() =
        runTest(mainRule.testScheduler) {
            // The session-start check's "proceed optimistically on Error"
            // path depends on bare-403 NOT being NotPremium — otherwise
            // a Spotify-side hiccup at /me would terminate the session
            // before the first track plays.
            server.enqueue(MockResponse().setResponseCode(403))

            val result = sut.isPremium()

            assertThat(result).isInstanceOf(Outcome.Error.Unknown::class.java)
        }

    @Test fun `isPremium returns true when product is premium`() =
        runTest(mainRule.testScheduler) {
            server.enqueue(MockResponse().setBody("""{"id":"u1","product":"premium"}"""))

            val result = sut.isPremium()

            assertThat(result).isEqualTo(Outcome.Success(true))
        }

    @Test fun `isPremium returns false when product is free`() =
        runTest(mainRule.testScheduler) {
            server.enqueue(MockResponse().setBody("""{"id":"u1","product":"free"}"""))

            val result = sut.isPremium()

            assertThat(result).isEqualTo(Outcome.Success(false))
        }
}

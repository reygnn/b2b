package com.github.reygnn.b2b.data.repository

import com.github.reygnn.b2b.data.remote.SpotifyApi
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
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
        sut = PlaybackRepositoryImpl(api, mainRule.testDispatcher)
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

    @Test fun `enqueue maps 403 to NotPremium`() = runTest(mainRule.testScheduler) {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = sut.enqueue("spotify:track:abc", "device-1")

        assertThat(result).isEqualTo(Outcome.Error.NotPremium)
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

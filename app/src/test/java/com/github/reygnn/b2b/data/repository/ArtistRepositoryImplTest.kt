package com.github.reygnn.b2b.data.repository

import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.local.entity.WhitelistedArtistEntity
import com.github.reygnn.b2b.data.remote.SpotifyApi
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
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

class ArtistRepositoryImplTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var api: SpotifyApi
    private val dao: WhitelistDao = mockk(relaxUnitFun = true)
    private val poolRepo: PoolRepository = mockk(relaxUnitFun = true)
    private val poolSyncTrigger: PoolSyncTrigger = mockk(relaxUnitFun = true)
    private lateinit var sut: ArtistRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpotifyApi::class.java)
        sut = ArtistRepositoryImpl(api, dao, poolRepo, poolSyncTrigger, mainRule.testDispatcher)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `searchArtists maps the response to domain Artist list`() =
        runTest(mainRule.testScheduler) {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"artists":{"items":[
                        {"id":"a1","name":"Artist One","uri":"spotify:artist:a1","images":[{"url":"http://img/a1.png"}]},
                        {"id":"a2","name":"Artist Two","uri":"spotify:artist:a2","images":[]}
                    ],"next":null,"limit":20,"offset":0,"total":2}}
                    """.trimIndent()
                )
            )

            val result = sut.searchArtists("foo")

            assertThat(result).isInstanceOf(Outcome.Success::class.java)
            val artists = (result as Outcome.Success).value
            assertThat(artists).containsExactly(
                Artist("a1", "Artist One", "http://img/a1.png"),
                Artist("a2", "Artist Two", null),
            ).inOrder()
        }

    @Test fun `searchArtists maps 401 to Outcome Error Unauthenticated`() =
        runTest(mainRule.testScheduler) {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = sut.searchArtists("foo")

            assertThat(result).isEqualTo(Outcome.Error.Unauthenticated)
        }

    @Test fun `searchArtists maps 429 to RateLimited with Retry-After`() =
        runTest(mainRule.testScheduler) {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))

            val result = sut.searchArtists("foo")

            assertThat(result).isEqualTo(Outcome.Error.RateLimited(retryAfterSeconds = 7))
        }

    @Test fun `searchArtists maps 404 to Unknown (not NoActiveDevice)`() =
        runTest(mainRule.testScheduler) {
            // Pinning the §B narrowing: non-player endpoints must not surface
            // a generic 404 as NoActiveDevice.
            server.enqueue(MockResponse().setResponseCode(404))

            val result = sut.searchArtists("foo")

            assertThat(result).isInstanceOf(Outcome.Error.Unknown::class.java)
        }

    @Test fun `fetchAllTrackUrisForArtist walks album and track pagination`() =
        runTest(mainRule.testScheduler) {
            // Page 1 of albums: 1 album, `next` non-null → keep walking.
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"items":[{"id":"alb1","name":"Album 1","uri":"spotify:album:alb1","album_type":"album","total_tracks":3}],
                     "next":"http://next-page","limit":50,"offset":0,"total":2}
                    """.trimIndent()
                )
            )
            // Page 1 album 1 tracks: 2 tracks, no further pages.
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"items":[
                        {"id":"t1","name":"Track 1","uri":"spotify:track:t1","duration_ms":180000,"artists":[{"id":"art1","name":"Artist"}]},
                        {"id":"t2","name":"Track 2","uri":"spotify:track:t2","duration_ms":210000,"artists":[{"id":"art1","name":"Artist"}]}
                    ],"next":null,"limit":50,"offset":0,"total":2}
                    """.trimIndent()
                )
            )
            // Page 2 of albums: 1 album, no more pages.
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"items":[{"id":"alb2","name":"Album 2","uri":"spotify:album:alb2","album_type":"single","total_tracks":1}],
                     "next":null,"limit":50,"offset":50,"total":2}
                    """.trimIndent()
                )
            )
            // Page 2 album 2 tracks: 1 track.
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"items":[
                        {"id":"t3","name":"Track 3","uri":"spotify:track:t3","duration_ms":150000,"artists":[{"id":"art1","name":"Artist"}]}
                    ],"next":null,"limit":50,"offset":0,"total":1}
                    """.trimIndent()
                )
            )

            val result = sut.fetchAllTrackUrisForArtist("art1")

            assertThat(result).isInstanceOf(Outcome.Success::class.java)
            val tracks = (result as Outcome.Success).value
            assertThat(tracks.map { it.uri }).containsExactly(
                "spotify:track:t1", "spotify:track:t2", "spotify:track:t3"
            ).inOrder()
            // Sanity-check that we walked both album-list pages.
            assertThat(server.requestCount).isEqualTo(4)
        }

    @Test fun `fetchAllTrackUrisForArtist filters out tracks where the requested artist is not a contributor`() =
        runTest(mainRule.testScheduler) {
            // Single album page, single tracks page with three tracks:
            // - t1: only art1 → keep
            // - t2: stranger + art1 (feature) → keep, display name = art1
            // - t3: only stranger → drop
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"items":[{"id":"alb1","name":"Album","uri":"spotify:album:alb1","album_type":"album","total_tracks":3}],
                     "next":null,"limit":50,"offset":0,"total":1}
                    """.trimIndent()
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"items":[
                        {"id":"t1","name":"Solo","uri":"spotify:track:t1","duration_ms":1000,
                         "artists":[{"id":"art1","name":"Hannah"}]},
                        {"id":"t2","name":"Feature","uri":"spotify:track:t2","duration_ms":1000,
                         "artists":[{"id":"stranger","name":"Stranger"},{"id":"art1","name":"Hannah"}]},
                        {"id":"t3","name":"Foreign","uri":"spotify:track:t3","duration_ms":1000,
                         "artists":[{"id":"stranger","name":"Stranger"}]}
                    ],"next":null,"limit":50,"offset":0,"total":3}
                    """.trimIndent()
                )
            )

            val result = sut.fetchAllTrackUrisForArtist("art1")

            assertThat(result).isInstanceOf(Outcome.Success::class.java)
            val tracks = (result as Outcome.Success).value
            assertThat(tracks.map { it.uri }).containsExactly(
                "spotify:track:t1", "spotify:track:t2"
            ).inOrder()
            // Display name always reflects the requested artist, even for
            // the feature track where Spotify lists the stranger first.
            assertThat(tracks.all { it.artistName == "Hannah" }).isTrue()
            assertThat(tracks.all { it.artistId == "art1" }).isTrue()
        }

    @Test fun `fetchAllTrackUrisForArtist propagates RateLimited mid-pagination`() =
        runTest(mainRule.testScheduler) {
            // Page 1 of albums: success.
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"items":[{"id":"alb1","name":"Album 1","uri":"spotify:album:alb1","album_type":"album","total_tracks":3}],
                     "next":"http://next","limit":50,"offset":0,"total":2}
                    """.trimIndent()
                )
            )
            // album-tracks call hits 429.
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12"))

            val result = sut.fetchAllTrackUrisForArtist("art1")

            assertThat(result).isEqualTo(Outcome.Error.RateLimited(retryAfterSeconds = 12))
        }

    @Test fun `fetchAllTrackUrisForArtist breaks pagination when limit is zero`() =
        runTest(mainRule.testScheduler) {
            // Pathological Spotify Dev-Mode response: `next` is non-null
            // (so the outer break-on-next doesn't catch it) AND `limit` is
            // 0 (so offset would never advance). Without the safety break,
            // this loops forever — the symptom that left an hour-long
            // "Syncing now…" stuck on the device. With the break, we exit
            // cleanly after one page.
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"items":[{"id":"alb1","name":"A","uri":"spotify:album:alb1","album_type":"album","total_tracks":1}],
                     "next":"http://infinite","limit":0,"offset":0,"total":99999}
                    """.trimIndent()
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"items":[
                        {"id":"t1","name":"Track","uri":"spotify:track:t1","duration_ms":1000,
                         "artists":[{"id":"art1","name":"Artist"}]}
                    ],"next":null,"limit":50,"offset":0,"total":1}
                    """.trimIndent()
                )
            )

            val result = sut.fetchAllTrackUrisForArtist("art1")

            assertThat(result).isInstanceOf(Outcome.Success::class.java)
            // One album walked, one track collected, then break — would
            // otherwise hang forever.
            assertThat(server.requestCount).isEqualTo(2)
        }

    @Test fun `addToWhitelist persists entity and triggers one-shot sync`() =
        runTest(mainRule.testScheduler) {
            val captured = slot<WhitelistedArtistEntity>()
            coEvery { dao.upsert(capture(captured)) } just Runs

            sut.addToWhitelist(Artist(id = "art1", name = "Artist", imageUrl = "http://img"))

            assertThat(captured.captured.id).isEqualTo("art1")
            assertThat(captured.captured.name).isEqualTo("Artist")
            assertThat(captured.captured.imageUrl).isEqualTo("http://img")
            assertThat(captured.captured.addedAtEpochMs).isGreaterThan(0)
            coVerify(exactly = 1) { poolSyncTrigger.triggerAfterWhitelistChange() }
        }

    @Test fun `removeFromWhitelist delegates to DAO, prunes pool, does not trigger sync`() =
        runTest(mainRule.testScheduler) {
            sut.removeFromWhitelist("art1")

            coVerify(exactly = 1) { dao.delete("art1") }
            // Pool prune happens inline so the next pickNext does not draw
            // a stale track from the removed artist before the 24 h
            // periodic sync runs.
            coVerify(exactly = 1) { poolRepo.deleteTracksForArtist("art1") }
            coVerify(exactly = 0) { poolSyncTrigger.triggerAfterWhitelistChange() }
        }
}

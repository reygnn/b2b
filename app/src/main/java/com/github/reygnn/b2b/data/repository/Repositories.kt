package com.github.reygnn.b2b.data.repository

import com.github.reygnn.b2b.data.local.dao.PoolTrackDao
import com.github.reygnn.b2b.data.local.dao.RecentlyPlayedDao
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.local.entity.PoolTrackEntity
import com.github.reygnn.b2b.data.local.entity.RecentlyPlayedEntity
import com.github.reygnn.b2b.data.local.entity.WhitelistedArtistEntity
import com.github.reygnn.b2b.data.remote.SpotifyApi
import com.github.reygnn.b2b.di.IoDispatcher
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PlaybackRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default HTTP-status mapping for the Spotify Web API. 404 is intentionally
 * mapped to [Outcome.Error.Unknown] rather than [Outcome.Error.NoActiveDevice]
 * — the "no active device" semantics are specific to the player endpoints
 * (`/me/player/queue`); for everything else, a 404 just means the resource
 * wasn't found. Endpoints that need the player-specific mapping handle 404
 * inline before delegating to this function.
 */
private inline fun <T, R> Response<T>.toOutcome(transform: (T) -> R): Outcome<R> {
    if (isSuccessful) return body()?.let { Outcome.Success(transform(it)) }
        ?: Outcome.Error.Unknown("empty body")
    return when (code()) {
        401 -> Outcome.Error.Unauthenticated
        403 -> Outcome.Error.NotPremium
        429 -> Outcome.Error.RateLimited(
            retryAfterSeconds = headers()["Retry-After"]?.toIntOrNull() ?: 1
        )
        else -> Outcome.Error.Unknown(describeError())
    }
}

/**
 * Pulls Spotify's error envelope (`{"error":{"status":N,"message":"..."}}`)
 * out of the response body and folds it into a one-line description. The
 * raw text is included as a fallback so unknown response shapes still
 * surface something useful in the UI rather than just the status code.
 */
private fun Response<*>.describeError(): String {
    val code = code()
    val raw = try { errorBody()?.string()?.trim().orEmpty() } catch (_: Exception) { "" }
    if (raw.isEmpty()) return "HTTP $code"
    val message = try {
        kotlinx.serialization.json.Json
            .parseToJsonElement(raw)
            .jsonObjectOrNull()
            ?.get("error")
            ?.jsonObjectOrNull()
            ?.get("message")
            ?.jsonPrimitiveOrNull()
            ?.content
    } catch (_: Exception) { null }
    return if (!message.isNullOrBlank()) "HTTP $code: $message"
    else "HTTP $code: ${raw.take(200)}"
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
    this as? kotlinx.serialization.json.JsonObject

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull() =
    this as? kotlinx.serialization.json.JsonPrimitive

@Singleton
class ArtistRepositoryImpl @Inject constructor(
    private val api: SpotifyApi,
    private val dao: WhitelistDao,
    private val poolRepo: PoolRepository,
    private val poolSyncTrigger: PoolSyncTrigger,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ArtistRepository {

    override fun observeWhitelist(): Flow<List<Artist>> =
        dao.observeAll().map { rows ->
            rows.map { Artist(it.id, it.name, it.imageUrl) }
        }

    override suspend fun searchArtists(query: String): Outcome<List<Artist>> = withContext(io) {
        try {
            api.searchArtists(query).toOutcome { resp ->
                resp.artists.items.map { Artist(it.id, it.name, it.images.firstOrNull()?.url) }
            }
        } catch (e: IOException) {
            Outcome.Error.Network
        }
    }

    override suspend fun addToWhitelist(artist: Artist) = withContext(io) {
        dao.upsert(
            WhitelistedArtistEntity(
                id = artist.id,
                name = artist.name,
                imageUrl = artist.imageUrl,
                addedAtEpochMs = System.currentTimeMillis(),
            )
        )
        poolSyncTrigger.triggerAfterWhitelistChange()
    }

    override suspend fun removeFromWhitelist(artistId: String) = withContext(io) {
        dao.delete(artistId)
        // Symmetric to addToWhitelist's one-shot sync: prune the pool now so
        // the next pickNext doesn't draw a stale track from the removed
        // artist. Local DB only — no need to wait for the 24 h periodic
        // PoolSyncWorker (which is gated on UNMETERED network).
        poolRepo.deleteTracksForArtist(artistId)
    }

    override suspend fun fetchAllTrackUrisForArtist(artistId: String): Outcome<List<Track>> =
        withContext(io) {
            try {
                val tracks = mutableListOf<Track>()
                var offset = 0
                var albumPagesSeen = 0
                while (true) {
                    // Hard safety break: bail out if pagination doesn't
                    // terminate. Real Spotify artists max out around a few
                    // hundred albums; we've seen Dev-Mode responses with a
                    // non-null `next` plus `limit == 0` that loop forever
                    // because offset never advances. (1 h hung sync, 2026-05-16.)
                    if (++albumPagesSeen > MAX_ALBUM_PAGES) break
                    val albumsResp = api.artistAlbums(artistId, offset = offset)
                    if (!albumsResp.isSuccessful) return@withContext albumsResp.toOutcome { _ ->
                        emptyList<Track>()
                    }
                    val page = albumsResp.body() ?: break
                    for (album in page.items) {
                        var trackOffset = 0
                        var trackPagesSeen = 0
                        while (true) {
                            if (++trackPagesSeen > MAX_TRACK_PAGES_PER_ALBUM) break
                            val tracksResp = api.albumTracks(album.id, offset = trackOffset)
                            if (!tracksResp.isSuccessful) return@withContext tracksResp.toOutcome { _ ->
                                emptyList<Track>()
                            }
                            val trackPage = tracksResp.body() ?: break
                            for (t in trackPage.items) {
                                // Spotify's /albums/{id}/tracks returns ALL
                                // tracks on the album, not just those by the
                                // artist we asked for. On split / various-
                                // artists / featuring releases this dumps
                                // unrelated tracks into our pool under the
                                // wrong artist id. Filter: keep a track only
                                // if our artist actually appears in its
                                // contributor list. Display name comes from
                                // the matching ArtistRefDto so features
                                // surface consistently as "our artist"
                                // instead of the lead.
                                val matchingArtist =
                                    t.artists.firstOrNull { it.id == artistId } ?: continue
                                tracks += Track(
                                    uri = t.uri,
                                    name = t.name,
                                    artistId = artistId,
                                    artistName = matchingArtist.name,
                                    durationMs = t.durationMs,
                                )
                            }
                            if (trackPage.next == null) break
                            // Spotify's Dev-Mode has been observed returning
                            // `limit: 0` on paged endpoints; `+= 0` would
                            // loop on the same offset forever. Bail out.
                            if (trackPage.limit <= 0) break
                            trackOffset += trackPage.limit
                        }
                    }
                    if (page.next == null) break
                    if (page.limit <= 0) break
                    offset += page.limit
                }
                Outcome.Success(tracks)
            } catch (e: IOException) {
                Outcome.Error.Network
            }
        }

    private companion object {
        // Generous ceilings: more albums or per-album tracks than any real
        // artist has. Exists only so a pathological API response doesn't
        // pin the worker in `RUNNING` indefinitely.
        const val MAX_ALBUM_PAGES = 100        // ~5000 albums
        const val MAX_TRACK_PAGES_PER_ALBUM = 20 // ~1000 tracks per album
    }
}

@Singleton
class PoolRepositoryImpl @Inject constructor(
    private val dao: PoolTrackDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PoolRepository {

    override suspend fun upsertTracks(tracks: List<Track>) = withContext(io) {
        val now = System.currentTimeMillis()
        dao.upsertAll(
            tracks.map {
                PoolTrackEntity(
                    uri = it.uri,
                    name = it.name,
                    artistId = it.artistId,
                    artistName = it.artistName,
                    durationMs = it.durationMs,
                    lastSyncedEpochMs = now,
                )
            }
        )
    }

    override suspend fun trackCount(): Int = withContext(io) { dao.count() }

    override fun observeTrackCount(): Flow<Int> = dao.observeCount()

    override fun observeLatestSyncEpochMs(): Flow<Long?> = dao.observeLatestSyncEpochMs()

    override suspend fun randomTrackExcluding(excludedUris: Set<String>): Track? = withContext(io) {
        val entity = if (excludedUris.isEmpty()) dao.random()
        else dao.randomExcluding(excludedUris.toList())
        entity?.let {
            Track(
                uri = it.uri,
                name = it.name,
                artistId = it.artistId,
                artistName = it.artistName,
                durationMs = it.durationMs,
            )
        }
    }

    override suspend fun deleteTracksForRemovedArtists(currentArtistIds: Set<String>) =
        withContext(io) { dao.deleteWhereArtistNotIn(currentArtistIds.toList()) }

    override suspend fun deleteTracksForArtist(artistId: String) =
        withContext(io) { dao.deleteByArtist(artistId) }
}

@Singleton
class RecentlyPlayedRepositoryImpl @Inject constructor(
    private val dao: RecentlyPlayedDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : RecentlyPlayedRepository {
    override suspend fun record(uri: String) = withContext(io) {
        dao.insert(RecentlyPlayedEntity(uri = uri, playedAtEpochMs = System.currentTimeMillis()))
    }
    override suspend fun recent(window: Int): List<String> = withContext(io) { dao.recent(window) }
    override suspend fun trim(window: Int) = withContext(io) { dao.trimTo(window) }
}

@Singleton
class PlaybackRepositoryImpl @Inject constructor(
    private val api: SpotifyApi,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PlaybackRepository {

    override suspend fun enqueue(uri: String, deviceId: String?): Outcome<Unit> = withContext(io) {
        try {
            val response = api.enqueue(uri, deviceId)
            when {
                // 204 No Content has a null body() — the generic toOutcome
                // would file that under Outcome.Error.Unknown("empty body").
                response.isSuccessful -> Outcome.Success(Unit)
                // Spotify's `/me/player/queue` returns 404 specifically to
                // signal "no active device" — translate explicitly rather
                // than letting the generic mapper file it under Unknown.
                response.code() == 404 -> Outcome.Error.NoActiveDevice
                else -> response.toOutcome<Unit, Unit> { }
            }
        } catch (e: IOException) {
            Outcome.Error.Network
        }
    }

    override suspend fun isPremium(): Outcome<Boolean> = withContext(io) {
        try {
            api.me().toOutcome { it.product == "premium" }
        } catch (e: IOException) {
            Outcome.Error.Network
        }
    }
}

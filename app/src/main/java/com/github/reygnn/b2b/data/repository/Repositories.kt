package com.github.reygnn.b2b.data.repository

import com.github.reygnn.b2b.data.local.dao.PoolTrackDao
import com.github.reygnn.b2b.data.local.dao.RecentlyPlayedDao
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.local.entity.PoolTrackEntity
import com.github.reygnn.b2b.data.local.entity.RecentlyPlayedEntity
import com.github.reygnn.b2b.data.local.entity.WhitelistedArtistEntity
import com.github.reygnn.b2b.data.remote.SpotifyApi
import com.github.reygnn.b2b.di.IoDispatcher
import com.github.reygnn.b2b.diagnostics.LogSink
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
 * Default HTTP-status mapping for the Spotify Web API.
 *
 * - 401 → [Outcome.Error.Unauthenticated]. AuthInterceptor already retried once
 *   with a refreshed token before we get here.
 * - 403 → see [map403]. Spotify uses 403 for many reasons (premium-required,
 *   restricted-device, rate-limited variants, scope issues, account penalty);
 *   we read the `error.reason` field (and fall back to `error.message`) to
 *   distinguish the "session is over for real" case ([Outcome.Error.NotPremium])
 *   from transient/policy-style failures. A bare 403 with no body becomes
 *   [Outcome.Error.Unknown] rather than [Outcome.Error.NotPremium] — the
 *   silent-default-to-NotPremium was diagnosed in 2026-05-19 as terminating
 *   sessions for transient Spotify-side hiccups.
 * - 404 → [Outcome.Error.Unknown]. The "no active device" semantics are
 *   specific to the player endpoints (`/me/player/queue`); for everything
 *   else, 404 just means the resource wasn't found. Endpoints that need the
 *   player-specific mapping handle 404 inline before delegating to this
 *   function.
 * - 429 → [Outcome.Error.RateLimited] with Retry-After (default 1s).
 */
private inline fun <T, R> Response<T>.toOutcome(transform: (T) -> R): Outcome<R> {
    if (isSuccessful) return body()?.let { Outcome.Success(transform(it)) }
        ?: Outcome.Error.Unknown("empty body")
    // Single read of errorBody().string() — the underlying source is one-shot.
    val body = parseErrorBody()
    return when (code()) {
        401 -> Outcome.Error.Unauthenticated
        403 -> map403(body)
        429 -> Outcome.Error.RateLimited(
            retryAfterSeconds = headers()["Retry-After"]?.toIntOrNull() ?: 1
        )
        else -> Outcome.Error.Unknown(formatHttpError(code(), body))
    }
}

/**
 * Parsed view of Spotify's `{"error":{"status":N,"message":"...","reason":"..."}}`
 * envelope. `reason` is set by the Player API endpoints (`/me/player/...`);
 * non-player endpoints omit it. Both fields are nullable: malformed bodies,
 * empty bodies, and pre-Player-API endpoints all collapse to all-null.
 */
private data class ErrorBody(val message: String?, val reason: String?, val raw: String)

private fun Response<*>.parseErrorBody(): ErrorBody {
    val raw = try { errorBody()?.string()?.trim().orEmpty() } catch (_: Exception) { "" }
    if (raw.isEmpty()) return ErrorBody(message = null, reason = null, raw = "")
    val error = try {
        kotlinx.serialization.json.Json
            .parseToJsonElement(raw)
            .jsonObjectOrNull()
            ?.get("error")
            ?.jsonObjectOrNull()
    } catch (_: Exception) { null }
    return ErrorBody(
        message = error?.get("message")?.jsonPrimitiveOrNull()?.content,
        reason = error?.get("reason")?.jsonPrimitiveOrNull()?.content,
        raw = raw,
    )
}

/**
 * Maps a 403 response to a specific [Outcome.Error]. Only `PREMIUM_REQUIRED`
 * (and the legacy message-based fallback) terminate the playback session;
 * everything else surfaces as [Outcome.Error.Unknown] with the parsed reason
 * + message folded into the description so the log panel and the
 * `SpotifyUnavailable` status line show what Spotify actually said.
 *
 * Note on `NO_ACTIVE_DEVICE`: the `/me/player/queue` endpoint returns 404 for
 * this case (handled inline in [PlaybackRepositoryImpl.enqueue] before
 * reaching here); we still map a 403 with this reason in case Spotify ever
 * changes that — keeps the player-state semantics consistent.
 */
private fun map403(body: ErrorBody): Outcome.Error = when (body.reason) {
    "PREMIUM_REQUIRED" -> Outcome.Error.NotPremium
    "NO_ACTIVE_DEVICE" -> Outcome.Error.NoActiveDevice
    null -> {
        // Pre-Player-API 403s (and a few current ones) carry only `message`.
        // The substring check is the explicit "Premium required" signal —
        // any other message keeps us out of the terminal path.
        if (body.message?.contains("Premium required", ignoreCase = true) == true)
            Outcome.Error.NotPremium
        else
            Outcome.Error.Unknown(formatHttpError(403, body))
    }
    else -> Outcome.Error.Unknown(formatHttpError(403, body))
}

private fun formatHttpError(code: Int, body: ErrorBody): String = buildString {
    append("HTTP ").append(code)
    if (!body.reason.isNullOrBlank()) append(' ').append(body.reason)
    when {
        !body.message.isNullOrBlank() -> append(": ").append(body.message)
        body.raw.isNotEmpty() && body.reason.isNullOrBlank() ->
            append(": ").append(body.raw.take(200))
    }
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
    private val log: LogSink,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PlaybackRepository {

    override suspend fun enqueue(uri: String, deviceId: String?): Outcome<Unit> = withContext(io) {
        log.trace("http: POST /me/player/queue uri=${uri.takeLast(8)} deviceId=$deviceId")
        try {
            val response = api.enqueue(uri, deviceId)
            log.trace("http: enqueue → ${response.code()} (${if (response.isSuccessful) "ok" else response.message()})")
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
            log.trace("http: enqueue → IOException ${e.message ?: e::class.simpleName}")
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

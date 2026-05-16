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

private inline fun <T, R> Response<T>.toOutcome(transform: (T) -> R): Outcome<R> {
    if (isSuccessful) return body()?.let { Outcome.Success(transform(it)) }
        ?: Outcome.Error.Unknown("empty body")
    return when (code()) {
        401 -> Outcome.Error.Unauthenticated
        403 -> Outcome.Error.NotPremium
        404 -> Outcome.Error.NoActiveDevice
        429 -> Outcome.Error.RateLimited(
            retryAfterSeconds = headers()["Retry-After"]?.toIntOrNull() ?: 1
        )
        else -> Outcome.Error.Unknown("HTTP ${code()}")
    }
}

@Singleton
class ArtistRepositoryImpl @Inject constructor(
    private val api: SpotifyApi,
    private val dao: WhitelistDao,
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
    }

    override suspend fun fetchAllTrackUrisForArtist(artistId: String): Outcome<List<Track>> =
        withContext(io) {
            try {
                val tracks = mutableListOf<Track>()
                var offset = 0
                while (true) {
                    val albumsResp = api.artistAlbums(artistId, offset = offset)
                    if (!albumsResp.isSuccessful) return@withContext albumsResp.toOutcome { _ ->
                        emptyList<Track>()
                    }
                    val page = albumsResp.body() ?: break
                    for (album in page.items) {
                        var trackOffset = 0
                        while (true) {
                            val tracksResp = api.albumTracks(album.id, offset = trackOffset)
                            if (!tracksResp.isSuccessful) return@withContext tracksResp.toOutcome { _ ->
                                emptyList<Track>()
                            }
                            val trackPage = tracksResp.body() ?: break
                            for (t in trackPage.items) {
                                tracks += Track(
                                    uri = t.uri,
                                    name = t.name,
                                    artistId = artistId,
                                    artistName = t.artists.firstOrNull()?.name ?: "",
                                    durationMs = t.durationMs,
                                )
                            }
                            if (trackPage.next == null) break
                            trackOffset += trackPage.limit
                        }
                    }
                    if (page.next == null) break
                    offset += page.limit
                }
                Outcome.Success(tracks)
            } catch (e: IOException) {
                Outcome.Error.Network
            }
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

    override suspend fun activeDeviceId(): Outcome<String?> = withContext(io) {
        try {
            api.devices().toOutcome { it.devices.firstOrNull { d -> d.isActive }?.id }
        } catch (e: IOException) {
            Outcome.Error.Network
        }
    }

    override suspend fun enqueue(uri: String, deviceId: String?): Outcome<Unit> = withContext(io) {
        try {
            api.enqueue(uri, deviceId).toOutcome { }
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

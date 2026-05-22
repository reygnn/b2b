package com.github.reygnn.b2b.domain.repository

import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface ArtistRepository {
    fun observeWhitelist(): Flow<List<Artist>>
    suspend fun searchArtists(query: String): Outcome<List<Artist>>
    suspend fun addToWhitelist(artist: Artist)
    suspend fun removeFromWhitelist(artistId: String)
    suspend fun fetchAllTrackUrisForArtist(artistId: String): Outcome<List<Track>>
}

interface PoolRepository {
    suspend fun upsertTracks(tracks: List<Track>)
    suspend fun trackCount(): Int
    fun observeTrackCount(): Flow<Int>
    fun observeLatestSyncEpochMs(): Flow<Long?>
    suspend fun randomTrackExcluding(excludedUris: Set<String>): Track?
    suspend fun deleteTracksForRemovedArtists(currentArtistIds: Set<String>)
    suspend fun deleteTracksForArtist(artistId: String)

    /**
     * Atomic replacement of an artist's slice of the pool: delete the
     * existing rows for [artistId] and insert the fresh [tracks] in a
     * single transaction. Used by the sync worker so a kill between delete
     * and upsert can't leave the pool with the artist temporarily empty —
     * the old slice survives until the new one is durable.
     */
    suspend fun replaceTracksForArtist(artistId: String, tracks: List<Track>)
}

interface RecentlyPlayedRepository {
    suspend fun record(uri: String)
    suspend fun recent(window: Int): List<String>
    suspend fun trim(window: Int)
}

interface PlaybackRepository {
    /**
     * Push a track URI into the user's Spotify queue. When [deviceId] is null,
     * the request is routed to whatever device Spotify currently considers
     * active; on no active device, the underlying endpoint returns 404 and
     * the impl maps it to [Outcome.Error.NoActiveDevice].
     *
     * The orchestrator passes `deviceId = null` — see
     * [com.github.reygnn.b2b.playback.PlaybackOrchestrator]. The parameter is
     * kept so the interface still works for hypothetical callers that have
     * already resolved a device id out of band; today there are none.
     */
    suspend fun enqueue(uri: String, deviceId: String?): Outcome<Unit>
    suspend fun isPremium(): Outcome<Boolean>
}

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
}

interface RecentlyPlayedRepository {
    suspend fun record(uri: String)
    suspend fun recent(window: Int): List<String>
    suspend fun trim(window: Int)
}

interface PlaybackRepository {
    suspend fun activeDeviceId(): Outcome<String?>
    suspend fun enqueue(uri: String, deviceId: String?): Outcome<Unit>
    suspend fun isPremium(): Outcome<Boolean>
}

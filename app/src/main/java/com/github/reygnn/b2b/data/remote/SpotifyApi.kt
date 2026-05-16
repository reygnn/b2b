package com.github.reygnn.b2b.data.remote

import com.github.reygnn.b2b.data.remote.dto.AlbumDto
import com.github.reygnn.b2b.data.remote.dto.ArtistSearchResponseDto
import com.github.reygnn.b2b.data.remote.dto.DevicesResponseDto
import com.github.reygnn.b2b.data.remote.dto.PagedResponseDto
import com.github.reygnn.b2b.data.remote.dto.TrackDto
import com.github.reygnn.b2b.data.remote.dto.UserProfileDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Spotify Web API.
 *
 * STRICT: only these endpoints. The following were deprecated for new apps
 * on 2024-11-27 and MUST NOT be added here:
 *   - /recommendations
 *   - /artists/{id}/related-artists
 *   - /audio-features, /audio-analysis
 *   - /browse/featured-playlists, /browse/categories/{id}/playlists
 *
 * Base URL: https://api.spotify.com/v1/
 */
interface SpotifyApi {

    @GET("v1/me")
    suspend fun me(): Response<UserProfileDto>

    // `limit` is intentionally nullable with a null default on these
    // endpoints: Spotify's "Development Mode" (the default for new dev-app
    // registrations post-2024-11-27) rejects explicit limit values that
    // are perfectly legal in the public docs ("Invalid limit"). Skipping
    // the query param entirely makes Spotify fall back to its server-side
    // default, which Dev Mode does accept. Pagination still works because
    // the response carries `limit` in the page envelope.

    @GET("v1/search")
    suspend fun searchArtists(
        @Query("q") query: String,
        @Query("type") type: String = "artist",
        @Query("limit") limit: Int? = null,
    ): Response<ArtistSearchResponseDto>

    @GET("v1/artists/{id}/albums")
    suspend fun artistAlbums(
        @Path("id") artistId: String,
        @Query("include_groups") includeGroups: String = "album,single",
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int = 0,
    ): Response<PagedResponseDto<AlbumDto>>

    @GET("v1/albums/{id}/tracks")
    suspend fun albumTracks(
        @Path("id") albumId: String,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int = 0,
    ): Response<PagedResponseDto<TrackDto>>

    @GET("v1/me/player/devices")
    suspend fun devices(): Response<DevicesResponseDto>

    @POST("v1/me/player/queue")
    suspend fun enqueue(
        @Query("uri") uri: String,
        @Query("device_id") deviceId: String? = null,
    ): Response<Unit>
}

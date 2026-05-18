package com.github.reygnn.b2b.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PagedResponseDto<T>(
    val items: List<T>,
    val next: String? = null,
    val limit: Int,
    val offset: Int,
    val total: Int,
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String,
    val uri: String,
    val images: List<ImageDto> = emptyList(),
)

@Serializable
data class ImageDto(
    val url: String,
    val height: Int? = null,
    val width: Int? = null,
)

@Serializable
data class AlbumDto(
    val id: String,
    val name: String,
    val uri: String,
    @SerialName("album_type") val albumType: String,
    @SerialName("total_tracks") val totalTracks: Int,
)

@Serializable
data class TrackDto(
    val id: String,
    val name: String,
    val uri: String,
    @SerialName("duration_ms") val durationMs: Long,
    val artists: List<ArtistRefDto> = emptyList(),
)

@Serializable
data class ArtistRefDto(
    val id: String,
    val name: String,
)

@Serializable
data class ArtistSearchResponseDto(
    val artists: PagedResponseDto<ArtistDto>,
)

@Serializable
data class UserProfileDto(
    val id: String,
    val product: String? = null, // "premium" | "free" | "open"
)

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresInSeconds: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

package com.github.reygnn.b2b.domain.model

data class Artist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
)

data class Track(
    val uri: String,
    val name: String,
    val artistId: String,
    val artistName: String,
    val durationMs: Long,
)

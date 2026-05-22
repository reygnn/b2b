package com.github.reygnn.b2b.domain.model

data class Artist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    /**
     * Whether the random picker currently uses this artist. Only meaningful
     * for artists that sit in the whitelist; search-result instances and
     * other non-whitelisted constructions keep the default `true`.
     */
    val isActive: Boolean = true,
)

data class Track(
    val uri: String,
    val name: String,
    val artistId: String,
    val artistName: String,
    val durationMs: Long,
)

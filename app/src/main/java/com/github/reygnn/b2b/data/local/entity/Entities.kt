package com.github.reygnn.b2b.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "whitelisted_artist")
data class WhitelistedArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val imageUrl: String?,
    val addedAtEpochMs: Long,
    // When false, [com.github.reygnn.b2b.domain.usecase.PickNextTrackUseCase]
    // skips this artist's tracks via the JOIN in [com.github.reygnn.b2b.data.local.dao.PoolTrackDao]
    // queries. The pool retains the tracks so a re-activate is instant; the
    // periodic sync ignores inactive rows to avoid burning API quota on
    // currently-unused artists. Schema v2.
    val isActive: Boolean = true,
)

@Entity(tableName = "pool_track")
data class PoolTrackEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val artistId: String,
    val artistName: String,
    val durationMs: Long,
    val lastSyncedEpochMs: Long,
)

@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val uri: String,
    val playedAtEpochMs: Long,
)

package com.github.reygnn.b2b.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "whitelisted_artist")
data class WhitelistedArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val imageUrl: String?,
    val addedAtEpochMs: Long,
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

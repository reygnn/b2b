package com.github.reygnn.b2b.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.github.reygnn.b2b.data.local.dao.PoolTrackDao
import com.github.reygnn.b2b.data.local.dao.RecentlyPlayedDao
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.local.entity.PoolTrackEntity
import com.github.reygnn.b2b.data.local.entity.RecentlyPlayedEntity
import com.github.reygnn.b2b.data.local.entity.WhitelistedArtistEntity

@Database(
    entities = [
        WhitelistedArtistEntity::class,
        PoolTrackEntity::class,
        RecentlyPlayedEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun whitelistDao(): WhitelistDao
    abstract fun poolTrackDao(): PoolTrackDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
}

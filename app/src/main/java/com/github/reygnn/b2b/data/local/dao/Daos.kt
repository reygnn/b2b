package com.github.reygnn.b2b.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.reygnn.b2b.data.local.entity.PoolTrackEntity
import com.github.reygnn.b2b.data.local.entity.RecentlyPlayedEntity
import com.github.reygnn.b2b.data.local.entity.WhitelistedArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WhitelistDao {
    @Query("SELECT * FROM whitelisted_artist ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<WhitelistedArtistEntity>>

    @Query("SELECT id FROM whitelisted_artist")
    suspend fun allIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artist: WhitelistedArtistEntity)

    @Query("DELETE FROM whitelisted_artist WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PoolTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<PoolTrackEntity>)

    @Query("SELECT COUNT(*) FROM pool_track")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pool_track")
    fun observeCount(): Flow<Int>

    @Query("SELECT MAX(lastSyncedEpochMs) FROM pool_track")
    fun observeLatestSyncEpochMs(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM pool_track WHERE artistId = :artistId")
    suspend fun countForArtist(artistId: String): Int

    /**
     * Random track NOT in the excluded set. The `:excluded` placeholder works
     * because Room expands the collection at query time.
     */
    @Query(
        """
        SELECT * FROM pool_track
        WHERE uri NOT IN (:excluded)
        ORDER BY RANDOM()
        LIMIT 1
        """
    )
    suspend fun randomExcluding(excluded: List<String>): PoolTrackEntity?

    @Query("SELECT * FROM pool_track ORDER BY RANDOM() LIMIT 1")
    suspend fun random(): PoolTrackEntity?

    @Query("DELETE FROM pool_track WHERE artistId NOT IN (:keep)")
    suspend fun deleteWhereArtistNotIn(keep: List<String>)

    @Query("DELETE FROM pool_track WHERE artistId = :artistId")
    suspend fun deleteByArtist(artistId: String)
}

@Dao
interface RecentlyPlayedDao {
    @Insert
    suspend fun insert(entry: RecentlyPlayedEntity)

    @Query("SELECT uri FROM recently_played ORDER BY playedAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<String>

    @Query(
        """
        DELETE FROM recently_played
        WHERE rowId NOT IN (
            SELECT rowId FROM recently_played
            ORDER BY playedAtEpochMs DESC LIMIT :keep
        )
        """
    )
    suspend fun trimTo(keep: Int)
}

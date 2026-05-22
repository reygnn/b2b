package com.github.reygnn.b2b.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

/**
 * Abstract class (not interface) so we can declare a `@Transaction` method
 * that calls into the other DAO methods — see [replaceTracksForArtist],
 * which atomically swaps an artist's slice of the pool in a single
 * SQLite transaction.
 */
@Dao
abstract class PoolTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(tracks: List<PoolTrackEntity>)

    @Query("SELECT COUNT(*) FROM pool_track")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pool_track")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT MAX(lastSyncedEpochMs) FROM pool_track")
    abstract fun observeLatestSyncEpochMs(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM pool_track WHERE artistId = :artistId")
    abstract suspend fun countForArtist(artistId: String): Int

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
    abstract suspend fun randomExcluding(excluded: List<String>): PoolTrackEntity?

    @Query("SELECT * FROM pool_track ORDER BY RANDOM() LIMIT 1")
    abstract suspend fun random(): PoolTrackEntity?

    @Query("DELETE FROM pool_track WHERE artistId NOT IN (:keep)")
    abstract suspend fun deleteWhereArtistNotIn(keep: List<String>)

    @Query("DELETE FROM pool_track WHERE artistId = :artistId")
    abstract suspend fun deleteByArtist(artistId: String)

    /**
     * Full wipe. Used by [com.github.reygnn.b2b.data.repository.PoolRepositoryImpl]
     * when the whitelist becomes empty — `deleteWhereArtistNotIn(emptyList())`
     * is unsafe because Room translates the empty `IN ()` clause in a way
     * that does not actually delete any rows.
     */
    @Query("DELETE FROM pool_track")
    abstract suspend fun deleteAll()

    /**
     * Atomic replacement of an artist's slice of the pool: delete the
     * existing rows for [artistId] and insert the fresh [tracks] in a
     * single SQLite transaction. Used by [com.github.reygnn.b2b.work.PoolSyncWorker]
     * so a worker kill between the two operations cannot leave the artist
     * temporarily empty in the pool.
     */
    @Transaction
    open suspend fun replaceTracksForArtist(artistId: String, tracks: List<PoolTrackEntity>) {
        deleteByArtist(artistId)
        upsertAll(tracks)
    }
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

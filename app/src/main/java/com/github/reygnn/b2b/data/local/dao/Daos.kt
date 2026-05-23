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

    /**
     * Only the active subset of [allIds]. Used by [com.github.reygnn.b2b.work.PoolSyncWorker]
     * to decide which artists to fetch from Spotify — inactive artists keep
     * their existing pool slice but are not refreshed (the user explicitly
     * paused them).
     */
    @Query("SELECT id FROM whitelisted_artist WHERE isActive = 1")
    suspend fun activeIds(): List<String>

    /**
     * Selects the next active artist that the [com.github.reygnn.b2b.work.PoolSyncWorker]
     * trickle should refresh on this tick, or `null` if everything is fresh.
     *
     * Ordering — oldest first:
     *  - Never-synced artists (no `pool_track` row → `last_sync IS NULL`) come first
     *    because SQLite's default ASC order puts `NULL` ahead of every Long value.
     *  - Among never-synced artists, the one added first (`addedAtEpochMs` ASC)
     *    wins — FIFO so multiple newly-added artists drain in the order the user
     *    chose them.
     *  - Among already-synced artists, the one whose latest pool row is oldest
     *    (`last_sync ASC`) wins, with `addedAtEpochMs` as tiebreaker on the
     *    unlikely event of identical timestamps.
     *
     * The `[floorMs]` cutoff filters out artists whose slice is younger than
     * the freshness floor — they don't need a refresh yet, so we skip the tick.
     * `floorMs = System.currentTimeMillis() - FRESHNESS_FLOOR_MS` (see
     * [com.github.reygnn.b2b.work.PoolSyncWorker]); rows with `last_sync IS NULL`
     * always pass the filter.
     */
    @Query(
        """
        SELECT wa.id FROM whitelisted_artist wa
        LEFT JOIN (
            SELECT artistId, MAX(lastSyncedEpochMs) AS last_sync
            FROM pool_track
            GROUP BY artistId
        ) p ON p.artistId = wa.id
        WHERE wa.isActive = 1
          AND (p.last_sync IS NULL OR p.last_sync < :floorMs)
        ORDER BY p.last_sync ASC, wa.addedAtEpochMs ASC
        LIMIT 1
        """
    )
    suspend fun pickNextToSync(floorMs: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artist: WhitelistedArtistEntity)

    @Query("UPDATE whitelisted_artist SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: String, isActive: Boolean)

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

    /**
     * Pool size restricted to tracks whose artist is currently flagged
     * active in [WhitelistDao]. Mirrors the JOIN used by [random] /
     * [randomExcluding] so the UI's "Pool: N tracks" matches what the
     * picker can actually draw from. Tracks belonging to paused or
     * removed-but-not-yet-pruned artists are intentionally excluded — the
     * picker won't pick them, so counting them would overstate the pool.
     */
    @Query(
        """
        SELECT COUNT(*) FROM pool_track pt
        INNER JOIN whitelisted_artist wa ON pt.artistId = wa.id
        WHERE wa.isActive = 1
        """
    )
    abstract suspend fun activeTrackCount(): Int

    @Query(
        """
        SELECT COUNT(*) FROM pool_track pt
        INNER JOIN whitelisted_artist wa ON pt.artistId = wa.id
        WHERE wa.isActive = 1
        """
    )
    abstract fun observeActiveTrackCount(): Flow<Int>

    @Query("SELECT MAX(lastSyncedEpochMs) FROM pool_track")
    abstract fun observeLatestSyncEpochMs(): Flow<Long?>

    /**
     * Most recent sync timestamp for a single artist, or `null` if the
     * artist has no pool rows yet (just added, or pruned previously).
     * Drives the per-artist freshness skip in [com.github.reygnn.b2b.work.PoolSyncWorker]:
     * artists whose slice was refreshed within the threshold are skipped
     * on this run to keep our Spotify API rate well below the 30 s window.
     * Returning `null` for an empty slice is intentional — that artist
     * must be fetched (the caller treats `null` as "never synced").
     */
    @Query("SELECT MAX(lastSyncedEpochMs) FROM pool_track WHERE artistId = :artistId")
    abstract suspend fun lastSyncedEpochMsForArtist(artistId: String): Long?

    @Query("SELECT COUNT(*) FROM pool_track WHERE artistId = :artistId")
    abstract suspend fun countForArtist(artistId: String): Int

    /**
     * All pool rows belonging to a single artist. Used by the manage-artists
     * delete-with-undo flow to snapshot the tracks before a [deleteByArtist]
     * so they can be re-upserted if the user taps Undo.
     */
    @Query("SELECT * FROM pool_track WHERE artistId = :artistId")
    abstract suspend fun tracksForArtist(artistId: String): List<PoolTrackEntity>

    /**
     * Random track NOT in the excluded set, restricted to artists currently
     * flagged active in [WhitelistDao]. The `:excluded` placeholder works
     * because Room expands the collection at query time.
     *
     * The JOIN doubles as a removed-artist guard: between
     * `whitelistDao.delete(artistId)` and the subsequent
     * `poolRepo.deleteTracksForArtist(artistId)` (in
     * [com.github.reygnn.b2b.data.repository.ArtistRepositoryImpl.removeFromWhitelist])
     * there is a brief window where pool rows still reference the deleted
     * artist; the inner join silently skips them.
     */
    @Query(
        """
        SELECT pt.* FROM pool_track pt
        INNER JOIN whitelisted_artist wa ON pt.artistId = wa.id
        WHERE wa.isActive = 1
          AND pt.uri NOT IN (:excluded)
        ORDER BY RANDOM()
        LIMIT 1
        """
    )
    abstract suspend fun randomExcluding(excluded: List<String>): PoolTrackEntity?

    @Query(
        """
        SELECT pt.* FROM pool_track pt
        INNER JOIN whitelisted_artist wa ON pt.artistId = wa.id
        WHERE wa.isActive = 1
        ORDER BY RANDOM()
        LIMIT 1
        """
    )
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

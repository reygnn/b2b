package com.github.reygnn.b2b.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.b2b.data.local.AppDatabase
import com.github.reygnn.b2b.data.local.entity.PoolTrackEntity
import com.github.reygnn.b2b.data.local.entity.WhitelistedArtistEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room-backed integration tests for [WhitelistDao] focused on the v2-schema
 * additions: [WhitelistDao.activeIds] and [WhitelistDao.setActive]. The
 * existing observe/upsert/delete paths are exercised by the [ArtistsViewModel]
 * tests via the repository.
 */
@RunWith(RobolectricTestRunner::class)
class WhitelistDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WhitelistDao
    private lateinit var poolDao: PoolTrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.whitelistDao()
        poolDao = db.poolTrackDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `upsert defaults isActive to true`() = runBlocking {
        // Pins the entity default: a freshly-added whitelist member should
        // appear in [activeIds] without an explicit [setActive] call.
        dao.upsert(WhitelistedArtistEntity("a1", "Hannah", null, 0L))

        assertThat(dao.activeIds()).isEqualTo(listOf("a1"))
        assertThat(dao.allIds()).isEqualTo(listOf("a1"))
    }

    @Test fun `activeIds returns only entries flagged active`() = runBlocking {
        dao.upsert(entry("a1", isActive = true))
        dao.upsert(entry("a2", isActive = false))
        dao.upsert(entry("a3", isActive = true))

        assertThat(dao.activeIds().toSet()).isEqualTo(setOf("a1", "a3"))
        assertThat(dao.allIds().toSet()).isEqualTo(setOf("a1", "a2", "a3"))
    }

    @Test fun `setActive toggles a single row and leaves siblings unchanged`() = runBlocking {
        dao.upsert(entry("a1", isActive = true))
        dao.upsert(entry("a2", isActive = true))

        dao.setActive("a1", isActive = false)

        assertThat(dao.activeIds().toSet()).isEqualTo(setOf("a2"))
        assertThat(dao.allIds().toSet()).isEqualTo(setOf("a1", "a2"))

        dao.setActive("a1", isActive = true)
        assertThat(dao.activeIds().toSet()).isEqualTo(setOf("a1", "a2"))
    }

    @Test fun `setActive on a non-existent id is a no-op`() = runBlocking {
        // The update query targets `WHERE id = :id` — no row, no effect, no
        // error. Pins the implicit "callers may pass freshly-removed ids
        // without crashing" contract.
        dao.setActive("does-not-exist", isActive = false)

        assertThat(dao.allIds()).isEmpty()
    }

    @Test fun `pickNextToSync returns null when whitelist is empty`() = runBlocking {
        assertThat(dao.pickNextToSync(floorMs = 1_000L)).isNull()
    }

    @Test fun `pickNextToSync prefers never-synced active artist`() = runBlocking {
        // a1 has fresh tracks, a2 has never been synced. The trickle should
        // always reach for the never-synced one first regardless of insertion
        // order, because their last_sync is NULL.
        dao.upsert(entry("a1", isActive = true, addedAt = 1L))
        dao.upsert(entry("a2", isActive = true, addedAt = 2L))
        poolDao.upsertAll(listOf(track("uri-a1", "a1", syncedAt = 500L)))

        val picked = dao.pickNextToSync(floorMs = 1_000L)

        assertThat(picked).isEqualTo("a2")
    }

    @Test fun `pickNextToSync among never-synced returns earliest addedAt`() = runBlocking {
        // FIFO contract: if the user added a2, a3, a1 (in that order, the
        // addedAt column witnesses it), the trickle drains them in that
        // exact order so the user's manual ordering is respected.
        dao.upsert(entry("a1", isActive = true, addedAt = 30L))
        dao.upsert(entry("a2", isActive = true, addedAt = 10L))
        dao.upsert(entry("a3", isActive = true, addedAt = 20L))

        val picked = dao.pickNextToSync(floorMs = 1_000L)

        assertThat(picked).isEqualTo("a2")
    }

    @Test fun `pickNextToSync returns stalest synced artist past the floor`() = runBlocking {
        // All three have been synced; their slices are at 100, 200, 300.
        // With floor at 400 every slice is stale; we expect the oldest
        // (a3 at 100) to come out first.
        dao.upsert(entry("a1", isActive = true, addedAt = 1L))
        dao.upsert(entry("a2", isActive = true, addedAt = 2L))
        dao.upsert(entry("a3", isActive = true, addedAt = 3L))
        poolDao.upsertAll(
            listOf(
                track("u1", "a1", syncedAt = 300L),
                track("u2", "a2", syncedAt = 200L),
                track("u3", "a3", syncedAt = 100L),
            )
        )

        val picked = dao.pickNextToSync(floorMs = 400L)

        assertThat(picked).isEqualTo("a3")
    }

    @Test fun `pickNextToSync returns null when all active slices are within the floor`() =
        runBlocking {
            // Steady state: nothing has aged past the floor, so the worker
            // should idle this tick rather than re-fetch fresh slices.
            dao.upsert(entry("a1", isActive = true, addedAt = 1L))
            dao.upsert(entry("a2", isActive = true, addedAt = 2L))
            poolDao.upsertAll(
                listOf(
                    track("u1", "a1", syncedAt = 900L),
                    track("u2", "a2", syncedAt = 950L),
                )
            )

            val picked = dao.pickNextToSync(floorMs = 500L)

            assertThat(picked).isNull()
        }

    @Test fun `pickNextToSync skips inactive artists even when their slice is stale`() =
        runBlocking {
            // Paused artists keep their pool slice but must not consume API
            // quota. If a1 is paused, the trickle skips to a2 even though
            // a1's slice is older.
            dao.upsert(entry("a1", isActive = false, addedAt = 1L))
            dao.upsert(entry("a2", isActive = true, addedAt = 2L))
            poolDao.upsertAll(
                listOf(
                    track("u1", "a1", syncedAt = 100L),
                    track("u2", "a2", syncedAt = 500L),
                )
            )

            val picked = dao.pickNextToSync(floorMs = 1_000L)

            assertThat(picked).isEqualTo("a2")
        }

    @Test fun `pickNextToSync uses latest sync timestamp per artist not earliest`() = runBlocking {
        // An artist's slice is "fresh" when its NEWEST track is fresh — the
        // worker writes all of a slice's rows with the same lastSyncedEpochMs
        // in production, but a partial re-sync could leave older rows behind.
        // The query uses MAX(lastSyncedEpochMs) per artistId so it doesn't
        // misclassify a freshly-resynced slice as stale.
        dao.upsert(entry("a1", isActive = true, addedAt = 1L))
        poolDao.upsertAll(
            listOf(
                track("u-old", "a1", syncedAt = 100L),
                track("u-new", "a1", syncedAt = 900L),
            )
        )

        // floor 500: a1's max sync (900) is above the floor → not stale.
        assertThat(dao.pickNextToSync(floorMs = 500L)).isNull()
        // floor 1000: even the newest (900) is now below → stale, picked.
        assertThat(dao.pickNextToSync(floorMs = 1_000L)).isEqualTo("a1")
    }

    private fun entry(id: String, isActive: Boolean, addedAt: Long = 0L) =
        WhitelistedArtistEntity(
            id = id,
            name = id,
            imageUrl = null,
            addedAtEpochMs = addedAt,
            isActive = isActive,
        )

    private fun track(uri: String, artistId: String, syncedAt: Long) = PoolTrackEntity(
        uri = uri,
        name = uri,
        artistId = artistId,
        artistName = artistId,
        durationMs = 200_000L,
        lastSyncedEpochMs = syncedAt,
    )
}

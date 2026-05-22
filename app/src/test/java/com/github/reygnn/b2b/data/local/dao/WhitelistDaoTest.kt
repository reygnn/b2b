package com.github.reygnn.b2b.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.b2b.data.local.AppDatabase
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

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.whitelistDao()
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

    private fun entry(id: String, isActive: Boolean) = WhitelistedArtistEntity(
        id = id,
        name = id,
        imageUrl = null,
        addedAtEpochMs = 0L,
        isActive = isActive,
    )
}

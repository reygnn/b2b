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
 * Room-backed integration tests for [PoolTrackDao]. The rest of the project
 * mocks the DAO interface, which is fine for verifying that the impl calls
 * the right methods — but a handful of SQL-level invariants need to be pinned
 * against real Room: empty `IN ()` semantics, `@Transaction` atomicity, the
 * full-wipe path, and the JOIN-on-active-whitelist behaviour added in v2.
 *
 * Robolectric is used because Room needs an Android [android.content.Context];
 * `runBlocking` is used instead of `runTest` because Room owns its own
 * executors and virtual-time scheduling does not apply.
 */
@RunWith(RobolectricTestRunner::class)
class PoolTrackDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PoolTrackDao
    private lateinit var whitelistDao: WhitelistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.poolTrackDao()
        whitelistDao = db.whitelistDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `deleteAll wipes every row regardless of artist`() = runBlocking {
        dao.upsertAll(listOf(track("u1", "a1"), track("u2", "a2"), track("u3", "a1")))
        assertThat(dao.count()).isEqualTo(3)

        dao.deleteAll()

        assertThat(dao.count()).isEqualTo(0)
    }

    @Test fun `deleteWhereArtistNotIn with empty list wipes every row`() = runBlocking {
        // Empirical Room+SQLite behaviour: the empty `keep` list expands to
        // `WHERE artistId NOT IN ()`, which SQLite treats as a tautology
        // (`x NOT IN ()` is true for every x) — every row matches and the
        // table is wiped. (FIXES.md §3 incorrectly described the symptom as
        // "no rows deleted"; the correction lives here.)
        //
        // For the §3 use case this happens to do the right thing (empty
        // whitelist → empty pool), but the behaviour is undocumented and
        // version-fragile: Room's placeholder expansion is an internal
        // detail, and a future Room release could just as legitimately raise
        // a syntax error or skip the DELETE. PoolRepositoryImpl routes the
        // empty case to [deleteAll] explicitly to stand on something
        // documented; this test pins the current Room behaviour so a
        // regression — in either direction — surfaces in CI rather than
        // mid-feature, and so a contributor who removes the explicit branch
        // sees what they are now relying on.
        dao.upsertAll(listOf(track("u1", "a1"), track("u2", "a2")))

        dao.deleteWhereArtistNotIn(emptyList())

        assertThat(dao.count()).isEqualTo(0)
    }

    @Test fun `deleteWhereArtistNotIn with non-empty list keeps the listed artists`() = runBlocking {
        dao.upsertAll(listOf(track("u1", "a1"), track("u2", "a2"), track("u3", "a3")))

        dao.deleteWhereArtistNotIn(listOf("a1", "a3"))

        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.countForArtist("a2")).isEqualTo(0)
        assertThat(dao.countForArtist("a1")).isEqualTo(1)
        assertThat(dao.countForArtist("a3")).isEqualTo(1)
    }

    @Test fun `replaceTracksForArtist swaps the slice for the given artist only`() = runBlocking {
        dao.upsertAll(listOf(track("u1", "a1"), track("u2", "a1"), track("u3", "a2")))

        dao.replaceTracksForArtist(
            artistId = "a1",
            tracks = listOf(track("u4", "a1"), track("u5", "a1")),
        )

        // a1's old slice (u1, u2) is gone; a1's fresh slice (u4, u5) is in;
        // a2 is untouched.
        assertThat(dao.countForArtist("a1")).isEqualTo(2)
        assertThat(dao.countForArtist("a2")).isEqualTo(1)
        assertThat(dao.count()).isEqualTo(3)
    }

    @Test fun `replaceTracksForArtist with empty fresh slice clears the artist`() = runBlocking {
        // Edge case: an artist whose Spotify response now yields zero matching
        // tracks (everything was filtered out as foreign-artist). The DAO's
        // delete-then-upsert pair must still drop the previous slice; the
        // empty upsert is a no-op.
        dao.upsertAll(listOf(track("u1", "a1"), track("u2", "a2")))

        dao.replaceTracksForArtist(artistId = "a1", tracks = emptyList())

        assertThat(dao.countForArtist("a1")).isEqualTo(0)
        assertThat(dao.countForArtist("a2")).isEqualTo(1)
    }

    // ---- JOIN on isActive ---------------------------------------------

    @Test fun `random returns only tracks whose artist is active in the whitelist`() = runBlocking {
        // Two artists in the whitelist: a1 active, a2 inactive. The picker
        // must only ever see a1's tracks; a2's are reachable again the
        // moment the user re-activates a2 (the rows aren't removed, just
        // hidden by the JOIN).
        whitelistDao.upsert(whitelistEntry("a1", isActive = true))
        whitelistDao.upsert(whitelistEntry("a2", isActive = false))
        dao.upsertAll(listOf(track("u1", "a1"), track("u2", "a2")))

        // Sample several times to reduce the chance of RANDOM() coincidentally
        // returning the same row every iteration — we want the *set* of
        // possible outputs to be {a1}.
        val seenArtists = (1..20).mapNotNull { dao.random()?.artistId }.toSet()

        assertThat(seenArtists).isEqualTo(setOf("a1"))
    }

    @Test fun `random returns null when every artist is inactive`() = runBlocking {
        whitelistDao.upsert(whitelistEntry("a1", isActive = false))
        dao.upsertAll(listOf(track("u1", "a1")))

        assertThat(dao.random()).isNull()
    }

    @Test fun `random skips orphan tracks whose whitelist row has been removed`() = runBlocking {
        // Models the brief window in [ArtistRepositoryImpl.removeFromWhitelist]
        // between `dao.delete(artistId)` and `poolRepo.deleteTracksForArtist`:
        // the whitelist row is gone but the pool rows linger. The JOIN must
        // hide them silently — no Foreign Key constraint is set on
        // pool_track.artistId, so the rows are valid SQL-wise; the
        // visibility guarantee comes from the JOIN, not from the schema.
        dao.upsertAll(listOf(track("u1", "a1")))
        // Note: no whitelistDao.upsert for "a1".

        assertThat(dao.random()).isNull()
    }

    @Test fun `randomExcluding honours both the active filter and the excluded URI set`() = runBlocking {
        whitelistDao.upsert(whitelistEntry("a1", isActive = true))
        dao.upsertAll(listOf(track("u1", "a1"), track("u2", "a1"), track("u3", "a1")))

        // Exclude u1 and u3; only u2 should ever come back.
        val seenUris = (1..20)
            .mapNotNull { dao.randomExcluding(listOf("u1", "u3"))?.uri }
            .toSet()

        assertThat(seenUris).isEqualTo(setOf("u2"))
    }

    @Test fun `randomExcluding returns null when only inactive artists have non-excluded tracks`() = runBlocking {
        whitelistDao.upsert(whitelistEntry("a1", isActive = true))
        whitelistDao.upsert(whitelistEntry("a2", isActive = false))
        dao.upsertAll(listOf(track("u1", "a1"), track("u2", "a2"), track("u3", "a2")))

        // Exclude a1's only track. The remaining candidates (u2, u3) all
        // belong to inactive a2 — picker should refuse to pick.
        assertThat(dao.randomExcluding(listOf("u1"))).isNull()
    }

    private fun track(uri: String, artistId: String) = PoolTrackEntity(
        uri = uri,
        name = uri,
        artistId = artistId,
        artistName = "n",
        durationMs = 1_000L,
        lastSyncedEpochMs = 0L,
    )

    private fun whitelistEntry(id: String, isActive: Boolean) = WhitelistedArtistEntity(
        id = id,
        name = id,
        imageUrl = null,
        addedAtEpochMs = 0L,
        isActive = isActive,
    )
}

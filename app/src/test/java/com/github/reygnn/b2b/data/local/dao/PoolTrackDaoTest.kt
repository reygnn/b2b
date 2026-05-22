package com.github.reygnn.b2b.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.b2b.data.local.AppDatabase
import com.github.reygnn.b2b.data.local.entity.PoolTrackEntity
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
 * against real Room: empty `IN ()` semantics, `@Transaction` atomicity, and
 * the full-wipe path. Mock verification cannot catch those.
 *
 * Robolectric is used because Room needs an Android [android.content.Context];
 * `runBlocking` is used instead of `runTest` because Room owns its own
 * executors and virtual-time scheduling does not apply.
 */
@RunWith(RobolectricTestRunner::class)
class PoolTrackDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PoolTrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.poolTrackDao()
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

        val remaining = listOfNotNull(dao.random()).map { it.artistId }.toSet() // smoke check
        assertThat(dao.count()).isEqualTo(2)
        assertThat(remaining).isNotEmpty()
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

    private fun track(uri: String, artistId: String) = PoolTrackEntity(
        uri = uri,
        name = uri,
        artistId = artistId,
        artistName = "n",
        durationMs = 1_000L,
        lastSyncedEpochMs = 0L,
    )
}

package com.github.reygnn.b2b.data.local

import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test for the v1→v2 migration. We can't use Room's
 * [androidx.room.testing.MigrationTestHelper] yet because no v1 schema JSON
 * exists in `schemas/` (exportSchema only kicked in with v2). Instead we
 * open a raw SQLite database, hand-build the v1 `whitelisted_artist` schema,
 * insert a row that the upgrade should preserve, then open the same file
 * via Room and verify the column got added with the expected default.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "migration-test.db"

    @Before fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @After fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test fun `migration 1 to 2 adds isActive column with default 1`() = runBlocking {
        // Step 1: open a raw v1 database by hand. We replicate the columns
        // exactly as Room would have generated them at version 1 — `isActive`
        // is the new column the migration adds, everything else is identical.
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `whitelisted_artist` (
                                `id` TEXT NOT NULL,
                                `name` TEXT NOT NULL,
                                `imageUrl` TEXT,
                                `addedAtEpochMs` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `pool_track` (
                                `uri` TEXT NOT NULL,
                                `name` TEXT NOT NULL,
                                `artistId` TEXT NOT NULL,
                                `artistName` TEXT NOT NULL,
                                `durationMs` INTEGER NOT NULL,
                                `lastSyncedEpochMs` INTEGER NOT NULL,
                                PRIMARY KEY(`uri`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `recently_played` (
                                `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `uri` TEXT NOT NULL,
                                `playedAtEpochMs` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        // The pinned row should retain `isActive = 1` after
                        // the migration (the DEFAULT 1 fills it in).
                        db.execSQL(
                            "INSERT INTO whitelisted_artist (id, name, imageUrl, addedAtEpochMs) " +
                                "VALUES ('a1', 'Hannah', NULL, 12345)"
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()

        // Step 2: open the same file as a v2 Room database. Room sees the
        // existing v1 schema and runs MIGRATION_1_2.
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        // Step 3: read back via DAO. The pre-migration row exists and reads
        // back as `isActive = true` (DEFAULT 1) — the data survived and the
        // upgrade-time invariant "previously-whitelisted artists keep being
        // used by the picker" holds.
        val activeIds = room.whitelistDao().activeIds()
        val allIds = room.whitelistDao().allIds()

        assertThat(allIds).containsExactly("a1")
        assertThat(activeIds).containsExactly("a1")

        room.close()
    }
}

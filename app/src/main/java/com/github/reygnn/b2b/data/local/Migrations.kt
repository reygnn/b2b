package com.github.reygnn.b2b.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for [AppDatabase]. The pinned JSON snapshots live under
 * `app/schemas/`; KSP generates them on every Room compile and a CI / dev
 * sanity check is `git diff schemas/` after a release build.
 *
 * Migration policy: prefer additive ALTERs over destructive paths so existing
 * on-device data survives. There is no fallback to destructive migration on
 * this database — every version bump must ship a [Migration] alongside, or
 * Room will throw on app start. That is intentional: the personal-app
 * maintainer is also the only user, and silently wiping their whitelist on
 * upgrade would be a surprise.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    // v2 adds `isActive` to whitelisted_artist so the manage-artists screen
    // can toggle whether the random picker uses an artist without removing it
    // from the whitelist entirely. Default 1 (active) preserves the prior
    // behaviour for every row that exists at upgrade time — pre-feature, the
    // implicit invariant was "every whitelisted artist is active".
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE whitelisted_artist ADD COLUMN isActive INTEGER NOT NULL DEFAULT 1"
        )
    }
}

internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

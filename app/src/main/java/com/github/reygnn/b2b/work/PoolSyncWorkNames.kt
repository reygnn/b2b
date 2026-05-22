package com.github.reygnn.b2b.work

/**
 * Single source of truth for the unique-work names under which
 * [PoolSyncWorker] is enqueued. Centralised so the UI's "is any sync running?"
 * indicator can combine WorkInfo flows over the same names that the schedulers
 * use.
 *
 * Two lanes deliberately, with two different policies:
 *  - [PERIODIC]: enqueued from `B2BApp.onCreate` as a 24 h periodic, KEEP.
 *  - [MANUAL]: one-shot from the "Sync now" buttons in Settings and Artists,
 *    REPLACE, so the user can force a fresh sync without waiting for an
 *    in-flight one.
 *
 * There used to be a third lane (`AFTER_WHITELIST`) auto-fired by the
 * `addToWhitelist` repository call, but that ran the user straight into
 * Spotify rate limits during multi-artist add sessions. Pool population
 * after a whitelist edit is now manual (button) or eventual (24 h periodic).
 */
object PoolSyncWorkNames {
    const val PERIODIC = "pool_sync"
    const val MANUAL = "manual_pool_sync"

    val ALL: List<String> = listOf(PERIODIC, MANUAL)
}

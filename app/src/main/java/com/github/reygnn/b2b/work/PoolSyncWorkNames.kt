package com.github.reygnn.b2b.work

/**
 * Single source of truth for the three unique-work names under which
 * [PoolSyncWorker] is enqueued. Centralised so the UI's "is any sync running?"
 * indicator can combine WorkInfo flows over the same names that the schedulers
 * use.
 *
 * Three lanes deliberately, with three different policies:
 *  - [PERIODIC]: enqueued from `B2BApp.onCreate` as a 24 h periodic, KEEP.
 *  - [AFTER_WHITELIST]: one-shot from `WorkManagerPoolSyncTrigger` on add,
 *    KEEP, so rapid sequential adds coalesce into one run.
 *  - [MANUAL]: one-shot from `SettingsViewModel.manualSync`, REPLACE, so the
 *    user can force a fresh sync without waiting for an in-flight one.
 */
object PoolSyncWorkNames {
    const val PERIODIC = "pool_sync"
    const val AFTER_WHITELIST = "pool_sync_after_whitelist_change"
    const val MANUAL = "manual_pool_sync"

    val ALL: List<String> = listOf(PERIODIC, AFTER_WHITELIST, MANUAL)
}

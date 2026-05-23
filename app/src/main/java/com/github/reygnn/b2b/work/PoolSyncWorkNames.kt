package com.github.reygnn.b2b.work

/**
 * Unique-work name under which [PoolSyncWorker] is enqueued. Centralised so
 * the UI's "is any sync running?" indicator can subscribe to WorkInfo for
 * the same name that [com.github.reygnn.b2b.B2BApp] schedules.
 *
 * One lane only since ADR-0003: the trickle design refreshes a single
 * artist per periodic tick, so there is nothing useful that a separate
 * MANUAL lane could do that the next PERIODIC tick won't do within 15
 * minutes — and a parallel manual lane was the proximate cause of the
 * 2026-05-22/23 rate-limit incident.
 */
object PoolSyncWorkNames {
    const val PERIODIC = "pool_sync"

    val ALL: List<String> = listOf(PERIODIC)
}

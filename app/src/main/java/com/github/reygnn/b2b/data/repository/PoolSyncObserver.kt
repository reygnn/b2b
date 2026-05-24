package com.github.reygnn.b2b.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Reactive signals about the [com.github.reygnn.b2b.work.PoolSyncWorker]'s
 * lifecycle. Behind an interface so the HomeViewModel's test suite can
 * swap in a recording fake without dragging WorkManager into the JVM test
 * classpath.
 */
interface PoolSyncObserver {
    /** True while a periodic tick is in RUNNING state. */
    fun observeIsSyncing(): Flow<Boolean>

    /**
     * Wall-clock epoch in ms of the next scheduled tick, or `null` if the
     * periodic worker is not currently ENQUEUED with a finite next-run
     * time (i.e. nothing scheduled, or running right now — the latter
     * is signalled by [observeIsSyncing]).
     *
     * Backed by [androidx.work.WorkInfo.nextScheduleTimeMillis], which is
     * a *prediction* — Doze, the UNMETERED-network constraint, or battery
     * optimization can push the actual fire later. Treat the value as
     * best-effort UX, not a guarantee.
     */
    fun observeNextSyncEpochMs(): Flow<Long?>
}

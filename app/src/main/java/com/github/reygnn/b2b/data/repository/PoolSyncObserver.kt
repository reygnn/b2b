package com.github.reygnn.b2b.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Reactive "is a pool sync currently running?" signal across all three
 * WorkManager unique-work lanes (periodic / after-whitelist / manual).
 * Behind an interface so the WhitelistViewModel's test suite can swap in a
 * recording fake without dragging WorkManager into the JVM test classpath.
 */
interface PoolSyncObserver {
    fun observeIsSyncing(): Flow<Boolean>
}

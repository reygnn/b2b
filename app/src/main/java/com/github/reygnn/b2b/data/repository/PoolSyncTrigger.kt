package com.github.reygnn.b2b.data.repository

/**
 * Fires a one-shot [com.github.reygnn.b2b.work.PoolSyncWorker] when the
 * whitelist changes, so newly added artists land in the track pool
 * immediately rather than waiting up to 24 h for the periodic sync.
 *
 * Behind an interface so repository tests can swap in a recording fake
 * without dragging WorkManager into the JVM test suite.
 */
interface PoolSyncTrigger {
    fun triggerAfterWhitelistChange()
}

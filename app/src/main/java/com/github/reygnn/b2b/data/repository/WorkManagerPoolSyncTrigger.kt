package com.github.reygnn.b2b.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.github.reygnn.b2b.work.PoolSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [PoolSyncTrigger] backed by WorkManager. Uses KEEP so rapid
 * sequential whitelist edits coalesce into a single sync run rather than
 * queueing one worker per edit. The user-initiated manual sync from
 * Settings uses a separate unique-work name with REPLACE — see
 * [com.github.reygnn.b2b.ui.settings.SettingsViewModel].
 */
@Singleton
class WorkManagerPoolSyncTrigger @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PoolSyncTrigger {
    override fun triggerAfterWhitelistChange() {
        val request = OneTimeWorkRequestBuilder<PoolSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val WORK_NAME = "pool_sync_after_whitelist_change"
    }
}

package com.github.reygnn.b2b.data.repository

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.github.reygnn.b2b.work.PoolSyncWorkNames
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [PoolSyncObserver]. Combines the per-unique-work flows
 * for the three sync lanes ([PoolSyncWorkNames.ALL]) and emits `true` while
 * any of them has a [WorkInfo.State.RUNNING] entry.
 *
 * `ENQUEUED` is intentionally NOT treated as "syncing": a periodic worker
 * sits in `ENQUEUED` between its 24 h ticks, which would make the indicator
 * permanently on.
 */
@Singleton
class WorkManagerPoolSyncObserver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PoolSyncObserver {

    override fun observeIsSyncing(): Flow<Boolean> {
        val wm = WorkManager.getInstance(context)
        val perLane = PoolSyncWorkNames.ALL.map { name ->
            wm.getWorkInfosForUniqueWorkFlow(name)
        }
        return combine(perLane) { lanes ->
            lanes.any { workInfos ->
                workInfos.any { it.state == WorkInfo.State.RUNNING }
            }
        }.distinctUntilChanged()
    }
}

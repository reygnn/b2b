package com.github.reygnn.b2b.data.repository

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.github.reygnn.b2b.work.PoolSyncWorkNames
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [PoolSyncObserver]. Reads the live [WorkInfo] flow for
 * [PoolSyncWorkNames.ALL] and projects it into the signals the Home screen
 * needs.
 *
 * `ENQUEUED` is intentionally NOT treated as "syncing" in [observeIsSyncing]:
 * a periodic worker sits in `ENQUEUED` between its ticks, which would make
 * the indicator permanently on. [observeNextSyncEpochMs] uses the same
 * ENQUEUED state as its source — that's where `nextScheduleTimeMillis`
 * holds a meaningful value.
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

    override fun observeNextSyncEpochMs(): Flow<Long?> {
        val wm = WorkManager.getInstance(context)
        // Periodic work has its next-run time on the ENQUEUED instance.
        // RUNNING / SUCCEEDED / FAILED entries either have no useful value
        // or carry [Long.MAX_VALUE] as a sentinel — filter those out so the
        // flow emits `null` rather than a far-future placeholder.
        return wm.getWorkInfosForUniqueWorkFlow(PoolSyncWorkNames.PERIODIC)
            .map { workInfos ->
                workInfos
                    .firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                    ?.nextScheduleTimeMillis
                    ?.takeIf { it != Long.MAX_VALUE }
            }
            .distinctUntilChanged()
    }
}

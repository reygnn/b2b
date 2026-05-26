package com.github.reygnn.b2b.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sole owner of the periodic [PoolSyncWorker] schedule. Centralised so the
 * kill switch can cancel pending work the moment it is enabled (instead of
 * letting the worker fire every 15 min just to log "skipping tick" and
 * return) and re-arm it when the user toggles back off. Same handle is
 * used at app start by [com.github.reygnn.b2b.B2BApp].
 *
 * Constraint, cadence and backoff settings live here, not in `B2BApp`.
 *
 * 15 minutes is Android's hard floor for `PeriodicWorkRequest`
 * (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`). The trickle design
 * picks exactly one artist per tick, so any tick spacing above Spotify's
 * documented 30 s rolling rate-limit window is safe by construction —
 * 15 min gives a 30× safety factor without needing self-rescheduling
 * OneTimeWorkRequest plumbing.
 *
 * `NetworkType.CONNECTED` (not UNMETERED): the trickle fetches at most one
 * artist's track URIs per tick (~5-50 KB), so the cellular cost is
 * negligible. UNMETERED used to silently disable sync and M4 stats logging
 * during Android Auto / in-car sessions — the primary use case.
 *
 * 5-minute initial backoff covers `Result.retry()` (transient network or
 * the rare 429 that escapes the trickle's burst-free pacing). The next
 * periodic occurrence happens on the 15 min schedule regardless, so the
 * backoff exists only to space out a pathological single-tick retry.
 *
 * `ExistingPeriodicWorkPolicy.UPDATE`: if a periodic sync is already
 * enqueued under [PoolSyncWorkNames.PERIODIC], adopt the new spec —
 * interval / constraints / backoff — without cancelling the schedule.
 * This is what makes version upgrades take effect.
 */
@Singleton
class PoolSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<PoolSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PoolSyncWorkNames.PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(PoolSyncWorkNames.PERIODIC)
    }
}

package com.github.reygnn.b2b

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.reygnn.b2b.work.PoolSyncWorkNames
import com.github.reygnn.b2b.work.PoolSyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class B2BApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Failfast before any feature kicks off. An empty client id makes the
        // auth URL malformed (`client_id=`) and Spotify returns a cryptic 400
        // at OAuth time — better to fail loudly here so the broken build is
        // caught at app start, not at the first login attempt. Setup steps
        // are in the README.
        check(BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()) {
            "SPOTIFY_CLIENT_ID is empty. Set it in ~/.gradle/gradle.properties " +
                "(or the project-local gradle.properties); see README."
        }
        schedulePoolSync()
    }

    private fun schedulePoolSync() {
        // 15 minutes is Android's hard floor for PeriodicWorkRequest
        // (PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS). The trickle
        // design picks exactly one artist per tick, so any tick spacing
        // above Spotify's documented 30 s rolling rate-limit window is safe
        // by construction — 15 min gives a 30× safety factor without
        // needing self-rescheduling OneTimeWorkRequest plumbing.
        val request = PeriodicWorkRequestBuilder<PoolSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            // 5-minute initial backoff for Result.retry() (transient network
            // or the rare 429 that escapes the trickle's burst-free pacing).
            // The next periodic occurrence happens on the 15 min schedule
            // regardless, so the backoff exists only to space out a
            // pathological single-tick retry.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        // UPDATE (WorkManager 2.8+): if a periodic sync is already enqueued
        // under this unique name, adopt the new spec — interval / constraints
        // / backoff — without cancelling the schedule. This is what makes
        // version upgrades take effect: when 0.5.9 → 0.6.0 changed the
        // interval from 24 h to 15 min, KEEP silently ignored the new
        // interval because an existing schedule was present, leaving users
        // on the old 24 h cadence. UPDATE is the right policy for "a
        // single chain whose spec evolves with the app".
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PoolSyncWorkNames.PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

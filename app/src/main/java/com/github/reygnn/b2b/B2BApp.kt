package com.github.reygnn.b2b

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
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
        schedulePoolSync()
    }

    private fun schedulePoolSync() {
        val request = PeriodicWorkRequestBuilder<PoolSyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        // KEEP: if a periodic sync is already enqueued (e.g. across process
        // restarts), don't reset its schedule. The unique name guarantees a
        // single PoolSyncWorker chain regardless of how often onCreate runs.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PoolSyncWorkNames.PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

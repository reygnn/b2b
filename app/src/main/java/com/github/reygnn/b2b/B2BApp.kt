package com.github.reygnn.b2b

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.github.reygnn.b2b.data.repository.KillSwitchStore
import com.github.reygnn.b2b.work.PoolSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class B2BApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var killSwitchStore: KillSwitchStore
    @Inject lateinit var poolSyncScheduler: PoolSyncScheduler

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
        // Honor a persistent kill switch across restarts: if the user (or a
        // prior 429 auto-flip) left it on, do not arm the worker on this
        // launch. The next disable() will re-schedule via the scheduler.
        if (!killSwitchStore.state().value) {
            poolSyncScheduler.schedule()
        }
    }
}

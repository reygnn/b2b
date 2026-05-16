package com.github.reygnn.b2b.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.github.reygnn.b2b.R
import com.github.reygnn.b2b.di.DefaultDispatcher
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.PlaybackRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import com.github.reygnn.b2b.domain.usecase.PickNextTrackUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State machine.
 *
 *   Idle  -- start --> Connecting -- connected --> Running
 *   Running -- noPremium --> ErrorBanner
 *   Running -- noActiveDevice --> Idle
 *   Running -- trackEnding --> Running (after enqueue)
 *
 * Spotify App Remote SDK wiring is not included here — it must be implemented
 * once the SDK aar is on the classpath. The contract is:
 *
 *   onPlayerStateChanged(state) ->
 *     if (state.track.duration - state.playbackPosition <= TRIGGER_MS &&
 *         !alreadyQueuedForThisTrack) enqueueNext()
 *
 * Tests cover the enqueue trigger via injected fakes (no real App Remote).
 */
@AndroidEntryPoint
class PlaybackOrchestratorService : Service() {

    @Inject lateinit var pickNext: PickNextTrackUseCase
    @Inject lateinit var playback: PlaybackRepository
    @Inject lateinit var recents: RecentlyPlayedRepository
    @Inject @DefaultDispatcher lateinit var dispatcher: CoroutineDispatcher

    private val supervisor = SupervisorJob()
    private lateinit var scope: CoroutineScope
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(dispatcher + supervisor)
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_running)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loopJob?.cancel()
        loopJob = scope.launch {
            // TODO: subscribe to Spotify App Remote PlayerState updates here.
            //       For each state with `track.duration - position < TRIGGER_MS`
            //       and we haven't enqueued for this track id yet, call enqueueNext().
        }
        return START_STICKY
    }

    suspend fun enqueueNext(antiRepeatWindow: Int) {
        when (val premium = playback.isPremium()) {
            is Outcome.Success -> if (!premium.value) {
                updateNotification(getString(R.string.notif_free_tier)); return
            }
            is Outcome.Error -> { /* keep running; transient */ }
        }
        val device = when (val d = playback.activeDeviceId()) {
            is Outcome.Success -> d.value
            is Outcome.Error -> { updateNotification(getString(R.string.notif_idle_no_device)); return }
        }
        if (device == null) { updateNotification(getString(R.string.notif_idle_no_device)); return }
        val pick = pickNext(antiRepeatWindow)
        if (pick is Outcome.Success) {
            playback.enqueue(pick.value.uri, device)
            recents.record(pick.value.uri)
            recents.trim(antiRepeatWindow * 2)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.playback_channel_description) }
        )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val TRIGGER_MS = 15_000L
        private const val CHANNEL_ID = "playback_orchestrator"
        private const val NOTIF_ID = 1001
    }
}

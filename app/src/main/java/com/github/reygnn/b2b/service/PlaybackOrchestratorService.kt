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
import com.github.reygnn.b2b.playback.OrchestratorStatus
import com.github.reygnn.b2b.playback.OrchestratorStatusHolder
import com.github.reygnn.b2b.playback.PlaybackOrchestrator
import com.github.reygnn.b2b.playback.PlayerStateHolder
import com.github.reygnn.b2b.playback.PreviewTrackHolder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service shell around [PlaybackOrchestrator]. The orchestrator
 * holds the state-machine logic; this class only:
 *  - manages the foreground notification + channel,
 *  - launches/cancels the orchestrator's collect-loop on START / DESTROY,
 *  - translates [OrchestratorStatus] events into notification text updates,
 *  - mirrors the latest status into [OrchestratorStatusHolder] for the UI.
 *
 * Spotify App Remote access lives behind
 * [com.github.reygnn.b2b.playback.PlayerStateSource], bound to
 * [com.github.reygnn.b2b.playback.AppRemotePlayerStateSource].
 */
@AndroidEntryPoint
class PlaybackOrchestratorService : Service() {

    @Inject lateinit var orchestrator: PlaybackOrchestrator
    @Inject lateinit var serviceState: ServiceState
    @Inject lateinit var statusHolder: OrchestratorStatusHolder
    @Inject lateinit var playerStateHolder: PlayerStateHolder
    @Inject lateinit var previewHolder: PreviewTrackHolder
    @Inject @DefaultDispatcher lateinit var dispatcher: CoroutineDispatcher

    private val supervisor = SupervisorJob()
    private lateinit var scope: CoroutineScope
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(dispatcher + supervisor)
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_running)))
        serviceState.setRunning(true)
        scope.launch {
            orchestrator.status.collect { status ->
                statusHolder.record(status)
                updateNotification(notificationTextFor(status))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loopJob?.cancel()
        loopJob = scope.launch {
            orchestrator.run(ANTI_REPEAT_WINDOW)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        serviceState.setRunning(false)
        statusHolder.reset()
        playerStateHolder.reset()
        previewHolder.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notificationTextFor(status: OrchestratorStatus): String = when (status) {
        OrchestratorStatus.Idle -> getString(R.string.notif_running)
        is OrchestratorStatus.Listening -> getString(R.string.notif_running)
        is OrchestratorStatus.Enqueued -> getString(R.string.notif_running)
        OrchestratorStatus.FreeTier -> getString(R.string.notif_free_tier)
        OrchestratorStatus.NoActiveDevice -> getString(R.string.notif_idle_no_device)
        is OrchestratorStatus.SpotifyUnavailable ->
            getString(R.string.notif_spotify_unavailable, status.reason)
    }

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
        private const val ANTI_REPEAT_WINDOW = 50
        private const val CHANNEL_ID = "playback_orchestrator"
        private const val NOTIF_ID = 1001
    }
}

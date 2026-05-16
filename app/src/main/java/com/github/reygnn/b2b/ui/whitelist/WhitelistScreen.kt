package com.github.reygnn.b2b.ui.whitelist

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.SpaceBetween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.reygnn.b2b.R
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.playback.OrchestratorStatus
import com.github.reygnn.b2b.playback.OrchestratorStatusSnapshot
import com.github.reygnn.b2b.playback.PlaybackOrchestrator
import com.github.reygnn.b2b.playback.PlayerStateSnapshot
import com.github.reygnn.b2b.service.PlaybackOrchestratorService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(
    onOpenArtists: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: WhitelistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val serviceRunning by vm.isServiceRunning.collectAsState()
    val statusSnapshot by vm.orchestratorStatus.collectAsState()
    val playerSnapshot by vm.playerState.collectAsState()
    val nextPick by vm.nextPick.collectAsState()
    val poolCount by vm.poolTrackCount.collectAsState()
    val lastSync by vm.lastSyncEpochMs.collectAsState()
    val isSyncing by vm.isSyncing.collectAsState()

    LaunchedEffect(Unit) {
        vm.serviceCommand.collect { cmd ->
            val intent = Intent(context, PlaybackOrchestratorService::class.java)
            when (cmd) {
                ServiceCommand.Start -> ContextCompat.startForegroundService(context, intent)
                ServiceCommand.Stop -> context.stopService(intent)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.whitelist_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Text("⚙")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            StatusCard(
                serviceRunning = serviceRunning,
                statusSnapshot = statusSnapshot,
                playerSnapshot = playerSnapshot,
                nextPick = nextPick,
                onSkipNext = { vm.skipNext() },
                poolCount = poolCount,
                lastSyncEpochMs = lastSync,
                isSyncing = isSyncing,
            )

            Button(
                onClick = { vm.toggleService() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(
                    if (serviceRunning) stringResource(R.string.service_stop)
                    else stringResource(R.string.service_start)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            OutlinedButton(
                onClick = onOpenArtists,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.whitelist_manage_artists))
            }
        }
    }
}

@Composable
private fun StatusCard(
    serviceRunning: Boolean,
    statusSnapshot: OrchestratorStatusSnapshot,
    playerSnapshot: PlayerStateSnapshot?,
    nextPick: Track?,
    onSkipNext: () -> Unit,
    poolCount: Int,
    lastSyncEpochMs: Long?,
    isSyncing: Boolean,
) {
    // Tick clock once per second so "Xs ago" labels and the position-line
    // countdown stay live without tearing down the rest of the screen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${stringResource(R.string.status_label)}: " +
                    statusLine(serviceRunning, statusSnapshot, now),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (nextPick != null) {
                NextPickRow(track = nextPick, onSkip = onSkipNext)
            }
            if (playerSnapshot != null) {
                Text(
                    text = positionLine(playerSnapshot, now),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "${stringResource(R.string.pool_label)}: " +
                    poolLine(poolCount, lastSyncEpochMs, isSyncing, now),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NextPickRow(track: Track, onSkip: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${stringResource(R.string.next_label)}: " +
                stringResource(R.string.next_pick, track.name, track.artistName),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onSkip) {
            Text(stringResource(R.string.skip_next_glyph))
        }
    }
}

@Composable
private fun statusLine(
    serviceRunning: Boolean,
    snapshot: OrchestratorStatusSnapshot,
    now: Long,
): String = when (val status = snapshot.status) {
    OrchestratorStatus.Idle ->
        if (serviceRunning) stringResource(R.string.status_running)
        else stringResource(R.string.status_idle)
    is OrchestratorStatus.Listening ->
        stringResource(R.string.status_listening, status.trackName, status.artistName)
    is OrchestratorStatus.Enqueued -> {
        val base = stringResource(R.string.status_enqueued, status.trackName, status.artistName)
        "✓ $base (${formatAgo(snapshot.atEpochMs, now)})"
    }
    OrchestratorStatus.NoActiveDevice -> "⚠ " + stringResource(R.string.status_no_device)
    OrchestratorStatus.FreeTier -> "⚠ " + stringResource(R.string.status_free_tier)
    is OrchestratorStatus.SpotifyUnavailable ->
        "⚠ " + stringResource(R.string.status_spotify_unavailable, status.reason)
}

@Composable
private fun positionLine(snapshot: PlayerStateSnapshot, now: Long): String {
    val state = snapshot.state
    // Extrapolate position only while playing. Paused PlayerStates freeze
    // the position field; advancing the clock would silently overshoot.
    val extrapolatedPos = if (state.isPaused) {
        state.positionMs
    } else {
        (state.positionMs + (now - snapshot.capturedAtEpochMs).coerceAtLeast(0))
            .coerceIn(0L, state.durationMs)
    }
    val tail = when {
        state.isPaused -> stringResource(R.string.track_paused)
        else -> {
            val remainingToTrigger =
                (state.durationMs - extrapolatedPos - PlaybackOrchestrator.TRIGGER_MS)
                    .coerceAtLeast(0)
            if (remainingToTrigger == 0L) stringResource(R.string.push_window)
            else stringResource(R.string.next_push_in, formatMmSs(remainingToTrigger))
        }
    }
    return stringResource(
        R.string.track_position,
        formatMmSs(extrapolatedPos),
        formatMmSs(state.durationMs),
        tail,
    )
}

@Composable
private fun poolLine(
    count: Int,
    lastSyncEpochMs: Long?,
    isSyncing: Boolean,
    now: Long,
): String = when {
    isSyncing -> stringResource(R.string.pool_syncing)
    count == 0 -> stringResource(R.string.pool_empty)
    lastSyncEpochMs == null ->
        stringResource(R.string.pool_summary_never, count)
    else ->
        stringResource(R.string.pool_summary, count, formatAgo(lastSyncEpochMs, now))
}

@Composable
private fun formatAgo(epochMs: Long, now: Long): String {
    val deltaSeconds = ((now - epochMs).coerceAtLeast(0)) / 1_000
    return when {
        deltaSeconds < 60 -> stringResource(R.string.ago_seconds, deltaSeconds.toInt())
        deltaSeconds < 3_600 -> stringResource(R.string.ago_minutes, (deltaSeconds / 60).toInt())
        deltaSeconds < 86_400 -> stringResource(R.string.ago_hours, (deltaSeconds / 3_600).toInt())
        else -> stringResource(R.string.ago_days, (deltaSeconds / 86_400).toInt())
    }
}

private fun formatMmSs(ms: Long): String {
    val totalSec = ms / 1_000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

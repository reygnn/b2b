package com.github.reygnn.b2b.ui.home

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.SpaceBetween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.reygnn.b2b.BuildConfig
import com.github.reygnn.b2b.R
import com.github.reygnn.b2b.diagnostics.LogEntry
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.playback.OrchestratorStatus
import com.github.reygnn.b2b.playback.OrchestratorStatusSnapshot
import com.github.reygnn.b2b.playback.PlaybackOrchestrator
import com.github.reygnn.b2b.playback.PlayerStateSnapshot
import com.github.reygnn.b2b.service.PlaybackOrchestratorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenArtists: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val serviceRunning by vm.isServiceRunning.collectAsState()
    val statusSnapshot by vm.orchestratorStatus.collectAsState()
    val playerSnapshot by vm.playerState.collectAsState()
    val nextPick by vm.nextPick.collectAsState()
    val poolCount by vm.poolTrackCount.collectAsState()
    val lastSync by vm.lastSyncEpochMs.collectAsState()
    val isSyncing by vm.isSyncing.collectAsState()
    val logEntries by vm.logEntries.collectAsState()
    val traceEnabled by vm.traceEnabled.collectAsState()

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
                title = { Text("${stringResource(R.string.home_title)} ${BuildConfig.VERSION_NAME}") },
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
                Text(stringResource(R.string.home_manage_artists))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            LogPanel(
                entries = logEntries,
                onClear = { vm.clearLog() },
                traceEnabled = traceEnabled,
                onSetTraceEnabled = { vm.setTraceEnabled(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun LogPanel(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    traceEnabled: Boolean,
    onSetTraceEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(0)
    }
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val context = LocalContext.current
    val copyContentDescription = stringResource(R.string.logs_copy_cd)
    val clearContentDescription = stringResource(R.string.logs_clear_cd)
    // State-aware A11y description so TalkBack reads the current toggle
    // state ("on"/"off"); the platform appends "Double-tap to toggle"
    // automatically — don't bake it into the string here.
    val traceContentDescription = stringResource(
        if (traceEnabled) R.string.logs_trace_cd_active else R.string.logs_trace_cd_inactive
    )
    val copiedToast = stringResource(R.string.logs_copied_toast)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.logs_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Trace toggle. When on, the orchestrator and repository
                // emit high-frequency diagnostic lines (state/arm/cancel/
                // fire/http). Off by default; the buffer would otherwise be
                // crowded with state-dump traffic that isn't useful for
                // normal sessions.
                //
                // Visual state: two distinct glyphs (📝 normal log /
                // 🐛 trace on). The IconToggleButton's own checked/
                // unchecked background is too subtle for a single-character
                // emoji to read clearly on a phone screen, so the glyph
                // itself carries the state.
                IconToggleButton(
                    checked = traceEnabled,
                    onCheckedChange = onSetTraceEnabled,
                    modifier = Modifier.semantics { contentDescription = traceContentDescription },
                ) {
                    Text(
                        text = if (traceEnabled) "🐛" else "📝",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(
                    onClick = {
                        // Build oldest-first dump so the user can paste a
                        // chronologically ordered transcript — even though
                        // the list view itself is reverseLayout (newest on
                        // top, easier to read live).
                        val dump = entries.joinToString("\n") { entry ->
                            "${LOG_TIME_FORMAT.format(Date(entry.epochMs))}  ${entry.message}"
                        }
                        // LocalClipboard.setClipEntry is suspend (the
                        // platform exposes the system clipboard service
                        // asynchronously). Launch in the composable's
                        // remembered scope; the Toast moves inside the
                        // launch so it fires only after the entry was
                        // actually published — strictly correct, also a
                        // few-microsecond delay in practice.
                        clipboardScope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("b2b log", dump))
                            )
                            Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = entries.isNotEmpty(),
                    modifier = Modifier.semantics { contentDescription = copyContentDescription },
                ) {
                    Text("📋", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(
                    onClick = onClear,
                    enabled = entries.isNotEmpty(),
                    modifier = Modifier.semantics { contentDescription = clearContentDescription },
                ) {
                    Text("🗑", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.logs_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            // No SelectionContainer here: with the high-frequency state-event
            // logs feeding new rows, long-press text selection trips a known
            // Compose SelectionManager bug (NoSuchElementException in
            // getSelectionLayout). The 📋 icon above is the supported
            // copy-out path; per-row selection is redundant.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries.asReversed(), key = { it.id }) {
                    Text(
                        text = "${LOG_TIME_FORMAT.format(Date(it.epochMs))}  ${it.message}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

private val LOG_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

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

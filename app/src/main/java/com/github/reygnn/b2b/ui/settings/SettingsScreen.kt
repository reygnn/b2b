package com.github.reygnn.b2b.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.reygnn.b2b.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val rateLimit by vm.rateLimit.collectAsState()
    var showRateLimitDialog by remember { mutableStateOf(false) }

    // 1 Hz tick so the rate-limit-active check (and the dialog's remaining-
    // time text, when it's open) refresh as the wait counts down.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val remainingSeconds = rateLimit?.remainingSecondsAt(now) ?: 0
    val isRateLimited = remainingSeconds > 0
    // Auto-dismiss the dialog if the rate-limit elapsed while it was open.
    if (showRateLimitDialog && !isRateLimited) showRateLimitDialog = false

    LaunchedEffect(Unit) {
        vm.toastEvents.collect { resId ->
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Two paths intentionally. No rate-limit → straight to enqueue.
            // Rate-limit active → open warning dialog; the user can
            // dismiss (no-op) or force-sync past the skip — see
            // SettingsViewModel.manualSync(force).
            Button(
                onClick = {
                    if (isRateLimited) showRateLimitDialog = true
                    else vm.manualSync()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_manual_sync))
            }

            OutlinedButton(
                onClick = { vm.cancelSync() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_cancel_sync))
            }

            OutlinedButton(
                onClick = { vm.logout() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_logout))
            }

            Button(
                onClick = onBack,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_back))
            }
        }
    }

    if (showRateLimitDialog) {
        AlertDialog(
            onDismissRequest = { showRateLimitDialog = false },
            title = { Text(stringResource(R.string.rate_limit_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.rate_limit_dialog_text,
                        formatHhMmSs(remainingSeconds),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRateLimitDialog = false
                    vm.manualSync(force = true)
                }) {
                    Text(stringResource(R.string.rate_limit_dialog_force))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRateLimitDialog = false }) {
                    Text(stringResource(R.string.rate_limit_dialog_cancel))
                }
            },
        )
    }
}

private fun formatHhMmSs(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return "%02d:%02d:%02d".format(hh, mm, ss)
}

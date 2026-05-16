package com.github.reygnn.b2b.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.reygnn.b2b.R
import com.github.reygnn.b2b.diagnostics.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onBack: () -> Unit,
    vm: LogViewModel = hiltViewModel(),
) {
    val entries by vm.entries.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to the newest entry whenever the list grows. With
    // `reverseLayout = true` the newest is at index 0, so we scroll there.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.logs_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(entries.asReversed(), key = { "${it.epochMs}-${it.message.hashCode()}" }) {
                        LogRow(it)
                    }
                }
            }

            OutlinedButton(
                onClick = { vm.clear() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.logs_clear))
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.logs_back))
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Text(
        text = "${TIME_FORMAT.format(Date(entry.epochMs))}  ${entry.message}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

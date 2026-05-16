package com.github.reygnn.b2b.ui.whitelist

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.reygnn.b2b.R
import com.github.reygnn.b2b.service.PlaybackOrchestratorService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(
    onOpenSettings: () -> Unit,
    vm: WhitelistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val whitelist by vm.whitelisted.collectAsState()
    val results by vm.searchResults.collectAsState()
    val serviceRunning by vm.isServiceRunning.collectAsState()
    var query by remember { mutableStateOf("") }

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
            Button(
                onClick = { vm.toggleService() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (serviceRunning) stringResource(R.string.service_stop)
                    else stringResource(R.string.service_start)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.search(it)
                },
                label = { Text("Search artists") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (results.isNotEmpty()) {
                Text("Results", modifier = Modifier.padding(top = 16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results, key = { "r-${it.id}" }) { artist ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(artist.name)
                            Button(onClick = { vm.add(artist) }) { Text("Add") }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Whitelisted (${whitelist.size})")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(whitelist, key = { "w-${it.id}" }) { artist ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(artist.name)
                        Button(onClick = { vm.remove(artist.id) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

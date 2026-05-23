package com.github.reygnn.b2b.ui.artists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.reygnn.b2b.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistsScreen(
    onBack: () -> Unit,
    vm: ArtistsViewModel = hiltViewModel(),
) {
    val rows by vm.displayedArtists.collectAsState()
    val isSearching by vm.isSearching.collectAsState()
    val searchError by vm.searchError.collectAsState()
    val deletedSnapshot by vm.deletedSnapshot.collectAsState()

    var query by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.artists_undo)
    val deletedTemplate = stringResource(R.string.artists_deleted_snackbar)

    // When the VM publishes a deletion snapshot, show a snackbar with Undo.
    // The snackbar's lifetime is bound to the snapshot's lifetime in the VM
    // (5 s timeout there), so we pass [SnackbarDuration.Indefinite] and let
    // the VM drive expiration via [deletedSnapshot] going null. That keeps
    // a single timer authoritative — Material's own duration handling
    // would otherwise race the VM's timer and could clear the snackbar
    // while the snapshot is still restorable, or vice versa.
    LaunchedEffect(deletedSnapshot) {
        val snapshot = deletedSnapshot ?: run {
            // Snapshot expired (timeout) or was consumed by undoDelete. Pull
            // the current snackbar so the next deletion's snackbar isn't
            // stacked on top.
            snackbarHostState.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        val result = snackbarHostState.showSnackbar(
            message = deletedTemplate.format(snapshot.artist.name),
            actionLabel = undoLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.artists_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val keyboard = LocalSoftwareKeyboardController.current
            val submit: () -> Unit = {
                vm.submitSearch(query)
                keyboard?.hide()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        vm.onQueryChange(it)
                    },
                    label = { Text(stringResource(R.string.artists_search_label)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                )
                IconButton(onClick = submit) { Text("🔍") }
            }

            if (isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            searchError?.let { reason ->
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { it.artist.id }) { row ->
                    when (row) {
                        is ArtistRow.Whitelisted -> WhitelistedRow(
                            row = row,
                            onSetActive = { active -> vm.setActive(row.artist, active) },
                            onDelete = { vm.deleteArtist(row.artist) },
                        )
                        is ArtistRow.SearchResult -> SearchResultRow(
                            row = row,
                            onAdd = { vm.addToWhitelist(row.artist) },
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.artists_back))
            }
        }
    }
}

@Composable
private fun WhitelistedRow(
    row: ArtistRow.Whitelisted,
    onSetActive: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val activeCd = stringResource(
        if (row.isActive) R.string.artists_active_cd_on else R.string.artists_active_cd_off
    )
    val deleteCd = stringResource(R.string.artists_delete_cd, row.artist.name)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = row.isActive,
            onCheckedChange = onSetActive,
            modifier = Modifier.semantics { contentDescription = activeCd },
        )
        Text(
            text = row.artist.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.semantics { contentDescription = deleteCd },
        ) {
            Text("🗑", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SearchResultRow(
    row: ArtistRow.SearchResult,
    onAdd: () -> Unit,
) {
    val addCd = stringResource(R.string.artists_add_cd, row.artist.name)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.artist.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onAdd,
            modifier = Modifier.semantics { contentDescription = addCd },
        ) {
            Text("➕", style = MaterialTheme.typography.titleMedium)
        }
    }
}

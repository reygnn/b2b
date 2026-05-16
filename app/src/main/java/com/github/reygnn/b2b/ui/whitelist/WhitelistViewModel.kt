package com.github.reygnn.b2b.ui.whitelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.data.repository.PoolSyncObserver
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.playback.OrchestratorStatusHolder
import com.github.reygnn.b2b.playback.OrchestratorStatusSnapshot
import com.github.reygnn.b2b.playback.PlaybackOrchestrator
import com.github.reygnn.b2b.playback.PlayerStateHolder
import com.github.reygnn.b2b.playback.PlayerStateSnapshot
import com.github.reygnn.b2b.playback.PreviewTrackHolder
import com.github.reygnn.b2b.service.ServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WhitelistViewModel @Inject constructor(
    private val artistRepo: ArtistRepository,
    private val orchestrator: PlaybackOrchestrator,
    poolRepo: PoolRepository,
    poolSyncObserver: PoolSyncObserver,
    statusHolder: OrchestratorStatusHolder,
    playerStateHolder: PlayerStateHolder,
    previewHolder: PreviewTrackHolder,
    serviceState: ServiceState,
) : ViewModel() {

    val whitelisted: StateFlow<List<Artist>> =
        artistRepo.observeWhitelist().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _searchResults = MutableStateFlow<List<Artist>>(emptyList())
    val searchResults: StateFlow<List<Artist>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // Single in-flight search job. A new submit cancels the previous one so
    // a slow request doesn't trample fresh results.
    private var searchJob: Job? = null

    val isServiceRunning: StateFlow<Boolean> = serviceState.running

    // Status-card backing flows. Each one is its own StateFlow so Compose can
    // recompose only the row that actually changed (e.g. pool count ticks
    // without disturbing the orchestrator status row).
    val orchestratorStatus: StateFlow<OrchestratorStatusSnapshot> = statusHolder.snapshot
    val playerState: StateFlow<PlayerStateSnapshot?> = playerStateHolder.snapshot
    val nextPick: StateFlow<Track?> = previewHolder.track
    val poolTrackCount: StateFlow<Int> = poolRepo.observeTrackCount().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )
    val lastSyncEpochMs: StateFlow<Long?> = poolRepo.observeLatestSyncEpochMs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )
    val isSyncing: StateFlow<Boolean> = poolSyncObserver.observeIsSyncing().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    private val _serviceCommand = Channel<ServiceCommand>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val serviceCommand: Flow<ServiceCommand> = _serviceCommand.receiveAsFlow()

    /**
     * Explicit search trigger — wired to the magnifying-glass button and the
     * keyboard IME "Search" action. Replaces the earlier 300 ms-debounce
     * behaviour: every distinct pause longer than the debounce window was a
     * fresh /search call, and at scale that contributed to Spotify
     * rate-limit penalties. Now Spotify sees exactly one call per user
     * submit.
     *
     * Blank query short-circuits: it clears results without hitting the API.
     */
    fun submitSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            _searchError.value = null
            return
        }
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null
            when (val r = artistRepo.searchArtists(query)) {
                is Outcome.Success -> {
                    _searchResults.value = r.value
                }
                is Outcome.Error -> {
                    _searchResults.value = emptyList()
                    _searchError.value = describeError(r)
                }
            }
            _isSearching.value = false
        }
    }

    fun add(artist: Artist) = viewModelScope.launch { artistRepo.addToWhitelist(artist) }
    fun remove(id: String) = viewModelScope.launch { artistRepo.removeFromWhitelist(id) }

    fun toggleService() {
        val cmd = if (isServiceRunning.value) ServiceCommand.Stop else ServiceCommand.Start
        _serviceCommand.trySend(cmd)
    }

    fun skipNext() {
        viewModelScope.launch { orchestrator.skipPreview() }
    }

    private fun describeError(error: Outcome.Error): String = when (error) {
        is Outcome.Error.Network -> "Network error"
        is Outcome.Error.Unauthenticated -> "Session expired — sign in again"
        is Outcome.Error.NotPremium -> "Spotify Premium required"
        is Outcome.Error.NoActiveDevice -> "No active Spotify device"
        is Outcome.Error.RateLimited -> "Rate limited — retry in ${error.retryAfterSeconds}s"
        is Outcome.Error.Unknown -> error.message ?: "Unknown error"
    }

}

enum class ServiceCommand { Start, Stop }

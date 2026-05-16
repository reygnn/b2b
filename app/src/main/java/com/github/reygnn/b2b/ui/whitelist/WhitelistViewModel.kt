package com.github.reygnn.b2b.ui.whitelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.service.ServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class WhitelistViewModel @Inject constructor(
    private val artistRepo: ArtistRepository,
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

    private val _query = MutableStateFlow("")

    val isServiceRunning: StateFlow<Boolean> = serviceState.running

    private val _serviceCommand = Channel<ServiceCommand>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val serviceCommand: Flow<ServiceCommand> = _serviceCommand.receiveAsFlow()

    init {
        viewModelScope.launch {
            _query
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                        _isSearching.value = false
                        return@collectLatest
                    }
                    _isSearching.value = true
                    when (val r = artistRepo.searchArtists(query)) {
                        is Outcome.Success -> _searchResults.value = r.value
                        is Outcome.Error -> _searchResults.value = emptyList()
                    }
                    _isSearching.value = false
                }
        }
    }

    fun search(query: String) {
        _query.value = query
    }

    fun add(artist: Artist) = viewModelScope.launch { artistRepo.addToWhitelist(artist) }
    fun remove(id: String) = viewModelScope.launch { artistRepo.removeFromWhitelist(id) }

    fun toggleService() {
        val cmd = if (isServiceRunning.value) ServiceCommand.Stop else ServiceCommand.Start
        _serviceCommand.trySend(cmd)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

enum class ServiceCommand { Start, Stop }

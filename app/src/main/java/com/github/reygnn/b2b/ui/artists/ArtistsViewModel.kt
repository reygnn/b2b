package com.github.reygnn.b2b.ui.artists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Row in the artists list: an [Artist] plus a flag for whether it currently
 * sits in the whitelist. The UI maps this to a checkbox state.
 */
data class ArtistRow(
    val artist: Artist,
    val isWhitelisted: Boolean,
)

/**
 * Backs the dedicated "manage artists" screen. Merges the persistent
 * whitelist (from the repo) with the most recent Spotify search results
 * into a single list with checkbox semantics — whitelisted entries are
 * pinned on top, search hits follow underneath, and search hits that are
 * already in the whitelist are deduplicated out so we don't render the
 * same artist twice.
 */
@HiltViewModel
class ArtistsViewModel @Inject constructor(
    private val artistRepo: ArtistRepository,
) : ViewModel() {

    // Eagerly collected so the combined `displayedArtists` always has a
    // current value as soon as the VM is created — important for the
    // checkbox screen, where the user expects the existing whitelist to
    // appear immediately on open, not after the WhileSubscribed kick-in.
    // viewModelScope cancels when the VM is cleared, so no leak.
    private val whitelisted: StateFlow<List<Artist>> =
        artistRepo.observeWhitelist().stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    private val _searchResults = MutableStateFlow<List<Artist>>(emptyList())

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    /**
     * Merged display list. Re-derived from the two source flows; the UI
     * just renders, no further logic needed.
     */
    val displayedArtists: StateFlow<List<ArtistRow>> = combine(
        whitelisted, _searchResults,
    ) { wl, sr ->
        val whitelistedIds = wl.mapTo(mutableSetOf()) { it.id }
        val whitelistedRows = wl.map { ArtistRow(it, isWhitelisted = true) }
        val searchRows = sr
            .filter { it.id !in whitelistedIds }
            .map { ArtistRow(it, isWhitelisted = false) }
        whitelistedRows + searchRows
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    private var searchJob: Job? = null

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

    /**
     * Toggled by the checkbox. `checked = true` adds to the whitelist (which
     * also kicks off a one-shot pool sync); `false` removes and prunes the
     * removed artist's pool slice inline.
     */
    fun setWhitelisted(artist: Artist, checked: Boolean) {
        viewModelScope.launch {
            if (checked) artistRepo.addToWhitelist(artist)
            else artistRepo.removeFromWhitelist(artist.id)
        }
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

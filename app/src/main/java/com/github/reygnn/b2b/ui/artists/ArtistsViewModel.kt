package com.github.reygnn.b2b.ui.artists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One row in the manage-artists list. The two subtypes carry different
 * affordances:
 *  - [Whitelisted] renders a checkbox bound to [Whitelisted.isActive] (toggle
 *    "use for random pick") and a trash button (remove from the whitelist
 *    entirely, with undo).
 *  - [SearchResult] renders a "+" button that adds the artist to the
 *    whitelist (always as active).
 *
 * Search hits whose id is already in the whitelist are filtered out upstream
 * — only one row per artist is ever shown.
 */
sealed interface ArtistRow {
    val artist: Artist

    data class Whitelisted(override val artist: Artist, val isActive: Boolean) : ArtistRow
    data class SearchResult(override val artist: Artist) : ArtistRow
}

/**
 * Snapshot of an artist + their pool tracks at the moment the user tapped
 * the trash button. Held in [ArtistsViewModel.deletedSnapshot] for the
 * duration of the undo snackbar; cleared either by [ArtistsViewModel.undoDelete]
 * (which re-inserts the snapshot) or by the snackbar timeout firing.
 */
data class DeletedArtistSnapshot(val artist: Artist, val tracks: List<Track>)

/**
 * Backs the dedicated "manage artists" screen. Two row types are rendered
 * (whitelisted with an active-checkbox + trash, search-results with a plus
 * button); see [ArtistRow].
 *
 * Search is fired via two paths:
 *   - [onQueryChange] from `TextField.onValueChange` runs through a
 *     [SEARCH_DEBOUNCE_MS] debounce so we don't hit Spotify on every
 *     keystroke;
 *   - [submitSearch] from the IME Search action / search button bypasses
 *     the debounce for an immediate hit.
 *
 * Trash-with-undo: [deleteArtist] snapshots the artist + their pool tracks,
 * removes them from the repo, and exposes the snapshot via
 * [deletedSnapshot]. The UI subscribes to that flow to render a snackbar;
 * tapping Undo calls [undoDelete] which re-upserts. After
 * [UNDO_SNACKBAR_MS] the snapshot clears itself and the undo is no longer
 * possible (the worker's next prune will remove the now-orphan tracks
 * either way).
 */
@OptIn(FlowPreview::class) // debounce(Long) is still @FlowPreview in coroutines 1.10.2.
@HiltViewModel
class ArtistsViewModel @Inject constructor(
    private val artistRepo: ArtistRepository,
    private val poolRepo: PoolRepository,
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

    // Drives the debounced search-as-you-type path. The UI writes here via
    // [onQueryChange] on every TextField change; we collect with a debounce
    // so only the final value of a typing burst hits the network.
    private val _queryInput = MutableStateFlow("")

    // Anchor for de-duplicating the debounced search pipeline against the
    // query that was last actually issued to the repo. See the patched-in
    // documentation in [runSearch] and the rule table in [shouldResetAnchorOn]
    // for the reset policy.
    private var lastSearchedQuery: String? = null

    private val _deletedSnapshot = MutableStateFlow<DeletedArtistSnapshot?>(null)
    /** Non-null while the undo snackbar should be visible. */
    val deletedSnapshot: StateFlow<DeletedArtistSnapshot?> = _deletedSnapshot.asStateFlow()
    private var undoTimerJob: Job? = null

    init {
        _queryInput
            // Drop the initial "" — `debounce` would otherwise emit it after
            // the timeout and clobber any submitSearch that fired before.
            .drop(1)
            .debounce(SEARCH_DEBOUNCE_MS)
            // Blank inputs are handled synchronously in onQueryChange (cancel
            // + state clear, no debounce). The anchor check additionally
            // drops re-emissions of the most recently searched query.
            .filter { it.isNotBlank() && it != lastSearchedQuery }
            .onEach { runSearch(it) }
            .launchIn(viewModelScope)
    }

    /**
     * Merged display list. Whitelisted rows on top (most recently added
     * first), then search hits that aren't already in the whitelist.
     */
    val displayedArtists: StateFlow<List<ArtistRow>> = combine(
        whitelisted, _searchResults,
    ) { wl, sr ->
        val whitelistedIds = wl.mapTo(mutableSetOf()) { it.id }
        val whitelistedRows = wl.map { ArtistRow.Whitelisted(it, it.isActive) }
        val searchRows = sr
            .filter { it.id !in whitelistedIds }
            .map { ArtistRow.SearchResult(it) }
        whitelistedRows + searchRows
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    private var searchJob: Job? = null

    /** Forward a TextField change. Blank input clears state synchronously. */
    fun onQueryChange(query: String) {
        if (query.isBlank()) {
            searchJob?.cancel()
            lastSearchedQuery = null
            _searchResults.value = emptyList()
            _isSearching.value = false
            _searchError.value = null
        }
        _queryInput.value = query
    }

    /** Fire a search immediately, bypassing the debounce. */
    fun submitSearch(query: String) {
        if (query.isBlank()) {
            onQueryChange(query)
            return
        }
        _queryInput.value = query
        runSearch(query)
    }

    private fun runSearch(query: String) {
        lastSearchedQuery = query
        searchJob?.cancel()
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
                    if (shouldResetAnchorOn(r)) {
                        lastSearchedQuery = null
                    }
                }
            }
            _isSearching.value = false
        }
    }

    /** Search-result row "+" button: add as active. */
    fun addToWhitelist(artist: Artist) {
        viewModelScope.launch { artistRepo.addToWhitelist(artist) }
    }

    /** Whitelist-row checkbox: toggle whether the random picker uses this artist. */
    fun setActive(artist: Artist, isActive: Boolean) {
        viewModelScope.launch { artistRepo.setActive(artist.id, isActive) }
    }

    /**
     * Whitelist-row trash button. Snapshots the artist + tracks, removes from
     * the repo, opens the undo window. A second [deleteArtist] within the
     * window finalises the previous deletion (cancels its undo) before
     * starting a new one — we don't queue multiple snapshots.
     */
    fun deleteArtist(artist: Artist) {
        // Finalise any pending snapshot so its tracks don't shadow the new
        // delete's undo state. Cancelling the timer is enough; the previous
        // snapshot's tracks are already gone from the repo.
        undoTimerJob?.cancel()
        _deletedSnapshot.value = null

        viewModelScope.launch {
            val tracks = poolRepo.tracksForArtist(artist.id)
            artistRepo.removeFromWhitelist(artist.id)
            _deletedSnapshot.value = DeletedArtistSnapshot(artist, tracks)
            undoTimerJob = launch {
                delay(UNDO_SNACKBAR_MS)
                // Only clear if it's still the same snapshot we set above.
                // If undoDelete fired in the meantime, the field is already
                // null and we don't want to clobber a future snapshot.
                if (_deletedSnapshot.value?.artist?.id == artist.id) {
                    _deletedSnapshot.value = null
                }
            }
        }
    }

    /**
     * Reinsert the most recently deleted artist + their tracks. No-op if the
     * undo window has already elapsed (the snapshot is null in that case).
     */
    fun undoDelete() {
        val snapshot = _deletedSnapshot.value ?: return
        _deletedSnapshot.value = null
        undoTimerJob?.cancel()
        viewModelScope.launch {
            // Restore the artist as active. The original active state isn't
            // re-applied: deletion is rare enough that "restore" defaults to
            // "ready to use again" feel right; if the user wanted the
            // restored artist inactive, the checkbox is one tap away.
            artistRepo.addToWhitelist(snapshot.artist)
            poolRepo.upsertTracks(snapshot.tracks)
        }
    }

    private fun shouldResetAnchorOn(error: Outcome.Error): Boolean = when (error) {
        is Outcome.Error.Network -> true
        is Outcome.Error.Unknown -> true
        is Outcome.Error.RateLimited -> false
        is Outcome.Error.Unauthenticated -> false
        is Outcome.Error.NotPremium -> false
        is Outcome.Error.NoActiveDevice -> false
    }

    private fun describeError(error: Outcome.Error): String = when (error) {
        is Outcome.Error.Network -> "Network error"
        is Outcome.Error.Unauthenticated -> "Session expired — sign in again"
        is Outcome.Error.NotPremium -> "Spotify Premium required"
        is Outcome.Error.NoActiveDevice -> "No active Spotify device"
        is Outcome.Error.RateLimited -> "Rate limited — retry in ${error.retryAfterSeconds}s"
        is Outcome.Error.Unknown -> error.message ?: "Unknown error"
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val UNDO_SNACKBAR_MS = 5_000L
    }
}

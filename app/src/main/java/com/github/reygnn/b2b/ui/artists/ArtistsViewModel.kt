package com.github.reygnn.b2b.ui.artists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
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
 *
 * Search is fired via two paths:
 *   - [onQueryChange] from `TextField.onValueChange` runs through a
 *     [SEARCH_DEBOUNCE_MS] debounce so we don't hit Spotify on every
 *     keystroke;
 *   - [submitSearch] from the IME Search action / search button bypasses
 *     the debounce for an immediate hit.
 */
@OptIn(FlowPreview::class) // debounce(Long) is still @FlowPreview in coroutines 1.10.2.
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

    // Drives the debounced search-as-you-type path. The UI writes here via
    // [onQueryChange] on every TextField change; we collect with a debounce
    // so only the final value of a typing burst hits the network.
    private val _queryInput = MutableStateFlow("")

    // Anchor for de-duplicating the debounced search pipeline against the
    // query that was last actually issued to the repo. Three cases this
    // guards against, and one case where it deliberately gets reset:
    //
    //  1. "type abc, type d, delete d" within a single debounce window —
    //     debounce emits "abc" after the silence; without this filter we'd
    //     re-issue the same search we already ran.
    //  2. "submitSearch('abc') then a delayed debounce of 'abc' lands 300 ms
    //     later" — submitSearch sets the anchor synchronously, the debounce
    //     emit is then filtered out.
    //  3. "type abc → search → select-all → retype same abc" — no clear
    //     happened, so the anchor still holds and the second emit is
    //     dropped. Re-running the same search without user intent is
    //     wasted work.
    //
    // Reset paths (anchor goes back to null so the next identical query
    // does re-fire):
    //  - onQueryChange("") — the user explicitly cleared.
    //  - Search returned Outcome.Error AND the error type passes
    //    [shouldResetAnchorOn] — currently Network or Unknown, i.e. failures
    //    where retrying the same query has a realistic chance of succeeding
    //    without external action. RateLimited and Unauthenticated do NOT
    //    reset; see [shouldResetAnchorOn] for the rationale.
    //
    // We deliberately do NOT use `distinctUntilChanged` after `debounce`: that
    // operator keeps a private "last emitted" state that we cannot reset, and
    // it would incorrectly suppress the legitimate "clear → retype same
    // query" case. The anchor pattern lets us reset on demand.
    //
    // Single-threaded access: viewModelScope dispatches on Main.immediate;
    // tests share the same dispatcher via MainDispatcherRule. No @Volatile
    // needed.
    private var lastSearchedQuery: String? = null

    init {
        _queryInput
            // Drop the initial "" — `debounce` would otherwise emit it after
            // the timeout and clobber any submitSearch that fired before.
            .drop(1)
            .debounce(SEARCH_DEBOUNCE_MS)
            // Blank inputs are handled synchronously in onQueryChange (cancel
            // + state clear, no debounce). The anchor check additionally
            // drops re-emissions of the most recently searched query — see
            // [lastSearchedQuery] for the two scenarios this covers.
            .filter { it.isNotBlank() && it != lastSearchedQuery }
            .onEach { runSearch(it) }
            .launchIn(viewModelScope)
    }

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

    /**
     * Forward an updated query string. Empty/blank input clears search
     * results synchronously; non-blank input is debounced before hitting
     * Spotify (see [SEARCH_DEBOUNCE_MS]).
     */
    fun onQueryChange(query: String) {
        if (query.isBlank()) {
            // Cancel any in-flight or pending search and clear state
            // immediately — no debounce on the clear path. Also reset the
            // de-dupe anchor so a "clear → retype identical query" sequence
            // re-runs the search (the user signalled intent by clearing).
            searchJob?.cancel()
            lastSearchedQuery = null
            _searchResults.value = emptyList()
            _isSearching.value = false
            _searchError.value = null
        }
        _queryInput.value = query
    }

    /**
     * Fire a search immediately, bypassing the debounce. Used by the IME
     * Search action / explicit submit button so the user is not held up
     * by the debounce window when they've signalled intent.
     */
    fun submitSearch(query: String) {
        if (query.isBlank()) {
            onQueryChange(query)
            return
        }
        // Sync the debounced flow's value with the submitted query. Two cases
        // this guards against:
        //
        //  1. Typing-then-submit: prior keystrokes left a debounce timer
        //     armed for an older value (e.g. "anni" while we submit "annie").
        //     Without this assignment, the timer would fire "anni" 300 ms
        //     later and the anchor filter ("annie") would NOT drop it →
        //     stale duplicate search. With the assignment, the timer is
        //     re-armed with "annie", and when it fires the anchor catches it.
        //
        //  2. Programmatic submit without typing: a hypothetical "search
        //     these artists" deep-link or saved-query shortcut calls
        //     submitSearch without _queryInput being in sync. Same fix.
        //
        // When _queryInput is already at `query`, the assignment is a no-op
        // (StateFlow dedupes), but the anchor set in runSearch below still
        // catches any pre-armed timer firing the same value.
        _queryInput.value = query
        runSearch(query)
    }

    private fun runSearch(query: String) {
        // Anchor the de-dupe filter on the query we are about to issue. Must
        // be set BEFORE launching the coroutine so that a delayed debounce
        // emit that lands while the HTTP call is still in flight is filtered
        // out, not re-fired.
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
                    // Selective anchor reset on failure. The reset enables
                    // retry-by-retyping (the user keeps the query in the
                    // field, types a char and deletes it; the next debounce
                    // fires again because the anchor is null). But that is
                    // only the right UX for failures where retrying the
                    // identical query *might* succeed — i.e. transient or
                    // opaque ones.
                    //
                    // We deliberately do NOT reset on:
                    //   - RateLimited: Spotify just told us the exact wait
                    //     time; re-firing on every keystroke would prolong
                    //     the penalty window. The error message surfaces
                    //     `retryAfterSeconds`; the user can wait and use the
                    //     submit button (which bypasses the anchor) when
                    //     ready.
                    //   - Unauthenticated: re-typing won't fix a broken
                    //     auth state. The user needs to re-login (the
                    //     AuthInterceptor / nav graph handles routing on
                    //     401 in practice). Hammering /search with the same
                    //     stale token just burns 401 round-trips.
                    //
                    // The narrow race for Network/Unknown — user types the
                    // same query within the 300 ms debounce window while
                    // the failure is still settling — would cause at most
                    // one duplicate searchArtists call. Acceptable trade
                    // for the better retry UX.
                    if (shouldResetAnchorOn(r)) {
                        lastSearchedQuery = null
                    }
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

    /**
     * Whether a failed search should drop the de-dupe anchor and let the
     * user retry by simply re-typing the same query. True only for failure
     * modes where the next attempt for the identical query has a realistic
     * chance of succeeding without external action; see the `runSearch`
     * error branch for the per-case rationale.
     */
    private fun shouldResetAnchorOn(error: Outcome.Error): Boolean = when (error) {
        is Outcome.Error.Network -> true
        is Outcome.Error.Unknown -> true
        // Spotify told us how long to wait — don't undermine that by
        // re-firing on every keystroke.
        is Outcome.Error.RateLimited -> false
        // Auth needs a re-login, not a re-type.
        is Outcome.Error.Unauthenticated -> false
        // Player-state errors; not produced by /search in practice, but
        // exhaustive for the sealed hierarchy. Treat conservatively.
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
    }
}

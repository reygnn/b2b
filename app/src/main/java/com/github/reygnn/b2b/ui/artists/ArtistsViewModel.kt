package com.github.reygnn.b2b.ui.artists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.data.repository.KillSwitchStore
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    /**
     * @property trackCount Number of pool rows currently associated with
     * this artist. Surfaced as a "name (N)" suffix on the row. `0` is a
     * meaningful value — it signals "trickle hasn't filled this artist
     * yet" (newly added) rather than "no data."
     */
    data class Whitelisted(
        override val artist: Artist,
        val isActive: Boolean,
        val trackCount: Int,
    ) : ArtistRow
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
 * Search is explicit only: typing in the text field never hits Spotify. The
 * search button (🔍) and the IME Search action call [submitSearch] to fire
 * the query. The screen still routes blank-edits through [onQueryChange] so
 * results clear synchronously when the user wipes the field — no network
 * round-trip is involved on that path.
 *
 * Trash-with-undo: [deleteArtist] snapshots the artist + their pool tracks,
 * removes them from the repo, and exposes the snapshot via
 * [deletedSnapshot]. The UI subscribes to that flow to render a snackbar;
 * tapping Undo calls [undoDelete] which re-upserts. After
 * [UNDO_SNACKBAR_MS] the snapshot clears itself and the undo is no longer
 * possible (the worker's next prune will remove the now-orphan tracks
 * either way).
 *
 * There is no "Sync now" button here anymore: ADR-0003 retired the manual
 * sync lane. Newly-added artists are picked up by the 15 min trickle tick
 * automatically — see [com.github.reygnn.b2b.work.PoolSyncWorker].
 */
@HiltViewModel
class ArtistsViewModel @Inject constructor(
    private val artistRepo: ArtistRepository,
    private val poolRepo: PoolRepository,
    killSwitchStore: KillSwitchStore,
) : ViewModel() {

    /**
     * Mirror of the global kill-switch state. When `true`, [submitSearch]
     * declines to issue an HTTP call and surfaces a "search disabled"
     * message via [searchError]; the screen also uses this flow to
     * disable the 🔍 button. Read-only — toggling lives on the Home
     * status card.
     */
    val killSwitchActive: StateFlow<Boolean> = killSwitchStore.state()

    /**
     * Wall-clock source for the search cache TTL and min-interval guard.
     * Production reads `System.currentTimeMillis()`; tests overwrite this
     * with a controlled lambda to make the time-sensitive assertions
     * deterministic without dragging Robolectric or a real clock advance
     * into the JVM unit test path.
     */
    @androidx.annotation.VisibleForTesting
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private data class CachedSearchResult(
        val results: List<Artist>,
        val timestamp: Long,
    )

    private val searchCache = mutableMapOf<String, CachedSearchResult>()
    private var lastSearchAtMs: Long = 0L

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

    private val trackCountsByArtist: StateFlow<Map<String, Int>> =
        poolRepo.observeTrackCountByArtist().stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap(),
        )

    private val _searchResults = MutableStateFlow<List<Artist>>(emptyList())

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _deletedSnapshot = MutableStateFlow<DeletedArtistSnapshot?>(null)
    /** Non-null while the undo snackbar should be visible. */
    val deletedSnapshot: StateFlow<DeletedArtistSnapshot?> = _deletedSnapshot.asStateFlow()
    private var undoTimerJob: Job? = null

    /**
     * Merged display list. Whitelisted rows on top (most recently added
     * first) with their current pool track count, then search hits that
     * aren't already in the whitelist. An artist absent from the counts
     * map renders as `0` — the trickle has not filled its slice yet.
     */
    val displayedArtists: StateFlow<List<ArtistRow>> = combine(
        whitelisted, _searchResults, trackCountsByArtist,
    ) { wl, sr, counts ->
        val whitelistedIds = wl.mapTo(mutableSetOf()) { it.id }
        val whitelistedRows = wl.map {
            ArtistRow.Whitelisted(
                artist = it,
                isActive = it.isActive,
                trackCount = counts[it.id] ?: 0,
            )
        }
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

    /**
     * Forward a TextField change. Only one path uses this: blank-edits clear
     * the search results synchronously so the list snaps back to the
     * whitelist-only view when the field is wiped. Non-blank typing is
     * intentionally ignored — Spotify is only consulted via [submitSearch]
     * (the 🔍 button / IME Search action).
     */
    fun onQueryChange(query: String) {
        if (query.isBlank()) {
            searchJob?.cancel()
            _searchResults.value = emptyList()
            _isSearching.value = false
            _searchError.value = null
        }
    }

    /**
     * Explicit search: 🔍 button or IME Search action. Two HTTP-saving
     * guards sit in front of [ArtistRepository.searchArtists]:
     *
     *  - **Per-query cache** with a 5 min TTL keyed on the trimmed,
     *    lowercased query. Re-submitting the same string returns the
     *    cached hits without an API call — covers the common iteration
     *    pattern of typing a query, hitting search, refining, hitting
     *    search again with the same final text.
     *  - **Min-interval guard** of 500 ms between any two HTTP-emitting
     *    submits. Swallows accidental double-taps of the 🔍 button
     *    that would otherwise emit two identical API calls.
     *
     * NEW-ARTISTS.md H1 motivates both: unguarded search is the most
     * plausible unprotected vector for a Spotify rate-limit hit during
     * artist-add sessions.
     */
    fun submitSearch(query: String) {
        searchJob?.cancel()
        val normalized = query.trim()
        if (normalized.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            _searchError.value = null
            return
        }
        // Kill-switch gate. Surfaces the error so the user gets feedback
        // (the 🔍 button is also disabled in the UI when the gate is on,
        // but an IME-action / programmatic call would otherwise reach
        // here silently).
        if (killSwitchActive.value) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            _searchError.value = SEARCH_DISABLED_MESSAGE
            return
        }
        val cacheKey = normalized.lowercase()
        val now = clock()

        // Cache hit: serve immediately, no HTTP.
        searchCache[cacheKey]?.let { cached ->
            if (now - cached.timestamp < SEARCH_CACHE_TTL_MS) {
                _searchResults.value = cached.results
                _isSearching.value = false
                _searchError.value = null
                return
            }
            // Stale: evict and fall through to a fresh fetch.
            searchCache.remove(cacheKey)
        }

        // Double-tap guard. Swallow this submit if the previous one
        // fired less than [MIN_SEARCH_INTERVAL_MS] ago. The cache check
        // above already handled the "same query twice" case; this
        // catches "rapidly different queries" rate-limit pressure.
        if (now - lastSearchAtMs < MIN_SEARCH_INTERVAL_MS) return
        lastSearchAtMs = now

        searchJob = viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null
            when (val r = artistRepo.searchArtists(query)) {
                is Outcome.Success -> {
                    _searchResults.value = r.value
                    searchCache[cacheKey] = CachedSearchResult(r.value, now)
                }
                is Outcome.Error -> {
                    _searchResults.value = emptyList()
                    _searchError.value = describeError(r)
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

    private fun describeError(error: Outcome.Error): String = when (error) {
        is Outcome.Error.Network -> "Network error"
        is Outcome.Error.Unauthenticated -> "Session expired — sign in again"
        is Outcome.Error.NotPremium -> "Spotify Premium required"
        is Outcome.Error.NoActiveDevice -> "No active Spotify device"
        is Outcome.Error.RateLimited -> "Rate limited — retry in ${error.retryAfterSeconds}s"
        is Outcome.Error.Unknown -> error.message ?: "Unknown error"
    }

    private companion object {
        const val UNDO_SNACKBAR_MS = 5_000L

        // Per-query cache TTL. Spotify search results don't churn fast
        // and the user typically refines a query within seconds; 5 min
        // is well above the realistic re-search window without serving
        // genuinely stale catalog data.
        const val SEARCH_CACHE_TTL_MS = 5L * 60 * 1000

        // Minimum wall-clock interval between HTTP-emitting submits.
        // Sized to swallow accidental double-taps of the 🔍 button
        // without getting in the way of a deliberate "search again"
        // after a typo correction.
        const val MIN_SEARCH_INTERVAL_MS = 500L

        // Surfaced in [_searchError] when the kill switch is on. Plain
        // English so an IME-Search-action triggered call still gets
        // useful feedback even if the visual disabled state of the 🔍
        // button is obscured.
        const val SEARCH_DISABLED_MESSAGE = "Search paused by kill switch"
    }
}

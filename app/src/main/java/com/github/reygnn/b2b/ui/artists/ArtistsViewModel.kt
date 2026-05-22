package com.github.reygnn.b2b.ui.artists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.github.reygnn.b2b.R
import com.github.reygnn.b2b.data.repository.PoolSyncObserver
import com.github.reygnn.b2b.data.repository.RateLimitState
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.domain.model.Artist
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.work.PoolSyncWorkNames
import com.github.reygnn.b2b.work.PoolSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
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
 */
@HiltViewModel
class ArtistsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val artistRepo: ArtistRepository,
    private val poolRepo: PoolRepository,
    poolSyncObserver: PoolSyncObserver,
    rateLimitStore: RateLimitStore,
) : ViewModel() {

    /**
     * Same source the home screen uses for the countdown line; here we
     * use it to hard-block the "Sync now" button while the wait is still
     * open. Settings has a softer override path (warning dialog) — see
     * [com.github.reygnn.b2b.ui.settings.SettingsViewModel.manualSync].
     */
    val rateLimit: StateFlow<RateLimitState?> = rateLimitStore.state()

    /**
     * Mirrors the home screen's sync indicator so the user can see whether
     * a "Sync now" tap is still in flight (and to disable the button while
     * it is). The "Cancel running sync" affordance lives in Settings — we
     * don't duplicate it on this screen.
     */
    val isSyncing: StateFlow<Boolean> = poolSyncObserver.observeIsSyncing().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    private val _toastEvents = Channel<Int>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val toastEvents: Flow<Int> = _toastEvents.receiveAsFlow()

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

    private val _deletedSnapshot = MutableStateFlow<DeletedArtistSnapshot?>(null)
    /** Non-null while the undo snackbar should be visible. */
    val deletedSnapshot: StateFlow<DeletedArtistSnapshot?> = _deletedSnapshot.asStateFlow()
    private var undoTimerJob: Job? = null

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

    /** Explicit search: 🔍 button or IME Search action. */
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

    /** Search-result row "+" button: add as active. */
    fun addToWhitelist(artist: Artist) {
        viewModelScope.launch { artistRepo.addToWhitelist(artist) }
    }

    /**
     * "Sync now" button. Mirrors the manual-sync action in Settings so the
     * user can fill the pool right after adding artists without leaving
     * this screen. Adds no longer auto-trigger a sync (rate-limit risk
     * during multi-artist sessions); this is the explicit replacement.
     *
     * Uses [ExistingWorkPolicy.REPLACE] under the [PoolSyncWorkNames.MANUAL]
     * lane so a stuck in-flight run is superseded immediately.
     */
    fun manualSync() {
        val request = OneTimeWorkRequestBuilder<PoolSyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PoolSyncWorkNames.MANUAL,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        _toastEvents.trySend(R.string.sync_enqueued)
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
    }
}

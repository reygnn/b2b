package com.github.reygnn.b2b.playback

import com.github.reygnn.b2b.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The track the orchestrator currently plans to enqueue at the next
 * trigger-fire. Surfaced to the UI as a preview ("Next: <Track>") with
 * a skip control that re-picks a fresh random pool track.
 *
 * Lifecycle:
 *  - Orchestrator pre-picks on each track change (Listening event) and
 *    [set]s the value; UI shows the upcoming enqueue.
 *  - User taps skip → orchestrator's `skipPreview()` re-picks and [set]s
 *    the new value; UI updates.
 *  - On a successful enqueue: orchestrator [reset]s; UI hides the row
 *    until the next track-change pre-pick.
 *  - On service destroy: [reset] so the UI does not display stale state.
 *
 * Null = no pending pick (service not running, pool empty, or the trigger
 * just fired and the next track hasn't started yet).
 */
@Singleton
class PreviewTrackHolder @Inject constructor() {
    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()

    fun set(track: Track) {
        _track.value = track
    }

    fun reset() {
        _track.value = null
    }
}

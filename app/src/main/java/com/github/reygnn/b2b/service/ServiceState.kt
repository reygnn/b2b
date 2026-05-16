package com.github.reygnn.b2b.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether [PlaybackOrchestratorService] is currently running. The
 * service itself flips this in `onCreate` / `onDestroy`; UI ViewModels read
 * it to render the start/stop button correctly.
 *
 * Android's `ActivityManager.getRunningServices` is deprecated and limited
 * to the caller's own services on newer SDKs; a singleton state holder is
 * the simpler, accurate alternative.
 */
@Singleton
class ServiceState @Inject constructor() {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun setRunning(running: Boolean) {
        _running.value = running
    }
}

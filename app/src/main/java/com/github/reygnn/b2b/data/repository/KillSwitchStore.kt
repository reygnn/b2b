package com.github.reygnn.b2b.data.repository

import android.content.Context
import com.github.reygnn.b2b.work.PoolSyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-controlled kill switch that blocks the two non-playback Spotify
 * surfaces — pool sync and artist search — while leaving the playback
 * orchestrator (`/me/player/queue`) and the token endpoint alone.
 *
 * Motivated by the May 2026 rate-limit incident series (see
 * [project_b2b_rate_limit_pattern_2026_05.md] in the memory notes plus
 * NEW-ARTISTS.md). Once enabled, only music playback keeps talking to
 * Spotify; everything else stays silent until the user flips it back off.
 *
 * Auto-enabled on every [RateLimitStore.record] call (any 429 from any
 * surface flips the switch), but the user can manually disable it at any
 * time — even mid-penalty. The manual disable is intentional: if the
 * user knows what they are doing and wants to search anyway, the gate
 * gets out of the way. The trickle's own active-skip will still block
 * `PoolSyncWorker` during the announced wait regardless.
 *
 * Both [enable] and [disable] have a side effect on the periodic
 * [PoolSyncWorker] schedule via [PoolSyncScheduler]: enable cancels
 * pending work so the worker does not wake every 15 min just to log
 * "skipping tick", disable re-arms the schedule. The worker's own
 * kill-switch check stays as a defense against in-flight ticks racing
 * the cancel.
 *
 * Persistence: SharedPreferences, same plain (un-encrypted) pattern as
 * [RateLimitStore]. The boolean is not sensitive; the AndroidKeystore
 * overhead would buy nothing.
 */
interface KillSwitchStore {
    fun state(): StateFlow<Boolean>
    fun enable()
    fun disable()
}

@Singleton
class KillSwitchStoreImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scheduler: PoolSyncScheduler,
) : KillSwitchStore {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _state: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    }

    override fun state(): StateFlow<Boolean> = _state.asStateFlow()

    override fun enable() {
        if (_state.value) return
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        _state.value = true
        // Cancel pending periodic work so the worker does not fire every
        // 15 min only to log "skipping tick" and return. The worker keeps
        // its own kill-switch check as a defense against races between a
        // freshly-cancelled tick and one already mid-flight.
        scheduler.cancel()
    }

    override fun disable() {
        if (!_state.value) return
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        _state.value = false
        // Re-arm the periodic schedule. `ExistingPeriodicWorkPolicy.UPDATE`
        // inside the scheduler makes this idempotent if work was somehow
        // still pending.
        scheduler.schedule()
    }

    private companion object {
        const val PREFS_NAME = "kill_switch"
        const val KEY_ENABLED = "enabled"
    }
}

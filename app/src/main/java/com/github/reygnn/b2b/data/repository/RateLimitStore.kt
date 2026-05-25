package com.github.reygnn.b2b.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What [PoolSyncWorker] saw the last time Spotify said "wait". We persist
 * both the announced wait *and* the wall-clock instant it was announced —
 * the UI subtracts the elapsed time to render a live countdown, which
 * stays meaningful across app restarts. A 16 h Retry-After (Spotify has
 * shipped those) survives the process death the user causes by closing
 * the app, so the next launch still shows them why nothing is syncing.
 *
 * In-memory state hangs off a [StateFlow]; the [android.content.SharedPreferences]
 * mirror is loaded lazily on first observe.
 *
 * @property retryAfterSeconds the value Spotify returned in the
 *   `Retry-After` header (or our default of 1 s if absent).
 * @property recordedAtEpochMs wall clock at the moment the rate-limit was
 *   observed by the worker.
 */
data class RateLimitState(
    val retryAfterSeconds: Int,
    val recordedAtEpochMs: Long,
) {
    /**
     * Seconds still on the clock at [nowMs]. Reaches `0` and stays there
     * once the rate-limit has expired; the UI then renders nothing.
     *
     * All arithmetic stays in `Long` until the final `coerceAtLeast(0L)`
     * — clamping in Int would overflow at e.g. `Long.MAX_VALUE / 1000`.
     * The clamped result is bounded above by [retryAfterSeconds] (an Int)
     * and below by 0, so the final `toInt()` is always safe.
     */
    fun remainingSecondsAt(nowMs: Long): Int {
        val elapsedSeconds = (nowMs - recordedAtEpochMs).coerceAtLeast(0L) / 1000L
        return (retryAfterSeconds.toLong() - elapsedSeconds).coerceAtLeast(0L).toInt()
    }
}

interface RateLimitStore {
    fun state(): StateFlow<RateLimitState?>
    fun record(retryAfterSeconds: Int, atEpochMs: Long = System.currentTimeMillis())
    fun clear()
}

/**
 * Plain (un-encrypted) [android.content.SharedPreferences]-backed
 * [RateLimitStore]. The rate-limit numbers are not sensitive, so we avoid
 * the AndroidKeystore overhead of [androidx.security.crypto.EncryptedSharedPreferences].
 *
 * Side-effect on [record]: also enables the [KillSwitchStore]. The May
 * 2026 incidents (three penalties in four days) made it clear that a
 * fresh 429 should silence sync + search across the whole app until the
 * user explicitly opts back in — orchestrator queue calls stay live but
 * are not gated here (intentionally, see [KillSwitchStore] KDoc).
 */
@Singleton
class RateLimitStoreImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val killSwitch: KillSwitchStore,
) : RateLimitStore {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _state: MutableStateFlow<RateLimitState?> by lazy {
        val seconds = prefs.getInt(KEY_SECONDS, -1)
        val epoch = prefs.getLong(KEY_AT, -1L)
        // We persist both record() inputs verbatim. A stale entry (the
        // process was killed mid-wait) is recognised on load by the
        // remaining-seconds calc dropping to 0 — but we keep it in the
        // flow so the UI can render the final "0 s" tick and the clear
        // path stays unambiguous (only [clear] mutates to null).
        val loaded = if (seconds > 0 && epoch > 0L) RateLimitState(seconds, epoch) else null
        MutableStateFlow(loaded)
    }

    override fun state(): StateFlow<RateLimitState?> = _state

    override fun record(retryAfterSeconds: Int, atEpochMs: Long) {
        val state = RateLimitState(retryAfterSeconds, atEpochMs)
        prefs.edit()
            .putInt(KEY_SECONDS, retryAfterSeconds)
            .putLong(KEY_AT, atEpochMs)
            .apply()
        _state.value = state
        // Auto-enable the kill switch on any 429 from any surface. The user
        // can manually disable it again from the Home status card if they
        // explicitly want to search during the penalty.
        killSwitch.enable()
    }

    override fun clear() {
        prefs.edit().clear().apply()
        _state.value = null
    }

    private companion object {
        const val PREFS_NAME = "rate_limit"
        const val KEY_SECONDS = "retry_after_seconds"
        const val KEY_AT = "recorded_at_epoch_ms"
    }
}

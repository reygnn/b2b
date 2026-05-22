package com.github.reygnn.b2b.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.github.reygnn.b2b.R
import com.github.reygnn.b2b.data.auth.TokenStore
import com.github.reygnn.b2b.data.repository.RateLimitState
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.work.PoolSyncWorkNames
import com.github.reygnn.b2b.work.PoolSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenStore: TokenStore,
    rateLimitStore: RateLimitStore,
) : ViewModel() {

    val rateLimit: StateFlow<RateLimitState?> = rateLimitStore.state()

    private val _toastEvents = Channel<Int>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val toastEvents: Flow<Int> = _toastEvents.receiveAsFlow()

    fun logout() {
        viewModelScope.launch { tokenStore.clear() }
    }

    /**
     * Schedule a one-shot sync. When [force] is true the worker skips its
     * rate-limit short-circuit — only meaningful on the Settings screen,
     * where the user has just dismissed the warning dialog acknowledging
     * the risk of an extended Spotify penalty.
     */
    fun manualSync(force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<PoolSyncWorker>()
            .setInputData(Data.Builder().putBoolean(PoolSyncWorker.KEY_FORCE, force).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PoolSyncWorkNames.MANUAL,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        _toastEvents.trySend(R.string.sync_enqueued)
    }

    /**
     * Cancels any in-flight or queued sync across all three lanes
     * (periodic / after-whitelist / manual). Escape hatch when a sync gets
     * stuck — without this, the only options were "wait", `adb shell cmd
     * jobscheduler cancel …" or "clear app data".
     */
    fun cancelSync() {
        val wm = WorkManager.getInstance(context)
        PoolSyncWorkNames.ALL.forEach { wm.cancelUniqueWork(it) }
        _toastEvents.trySend(R.string.sync_cancelled)
    }
}

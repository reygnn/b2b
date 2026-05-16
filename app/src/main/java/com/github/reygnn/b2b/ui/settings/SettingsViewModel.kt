package com.github.reygnn.b2b.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.github.reygnn.b2b.R
import com.github.reygnn.b2b.data.auth.TokenStore
import com.github.reygnn.b2b.work.PoolSyncWorkNames
import com.github.reygnn.b2b.work.PoolSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _toastEvents = Channel<Int>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val toastEvents: Flow<Int> = _toastEvents.receiveAsFlow()

    fun logout() {
        viewModelScope.launch { tokenStore.clear() }
    }

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

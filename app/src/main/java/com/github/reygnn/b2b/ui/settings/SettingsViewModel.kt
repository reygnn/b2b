package com.github.reygnn.b2b.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.github.reygnn.b2b.R
import com.github.reygnn.b2b.data.auth.TokenStore
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
        val request = OneTimeWorkRequestBuilder<PoolSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        _toastEvents.trySend(R.string.sync_enqueued)
    }

    private companion object {
        const val MANUAL_SYNC_WORK = "manual_pool_sync"
    }
}

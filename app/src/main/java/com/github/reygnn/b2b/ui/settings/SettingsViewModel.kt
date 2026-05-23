package com.github.reygnn.b2b.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.data.auth.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Settings screen. Since ADR-0003 the manual / force-sync paths
 * are gone — the trickle worker handles all syncing on its own 15 min
 * cadence — so this VM has only one job left: clearing the auth tokens.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenStore: TokenStore,
) : ViewModel() {

    fun logout() {
        viewModelScope.launch { tokenStore.clear() }
    }
}

package com.github.reygnn.b2b.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.data.auth.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Top-level state owner for the nav graph. Holds the only piece of state
 * the nav host actually needs — authenticated vs not — so the nav graph
 * can pick its start destination and react to login/logout.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    tokenStore: TokenStore,
) : ViewModel() {

    val isAuthenticated: StateFlow<Boolean> = tokenStore.authState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = tokenStore.authState().value,
        )
}

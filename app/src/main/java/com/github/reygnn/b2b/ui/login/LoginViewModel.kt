package com.github.reygnn.b2b.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.b2b.data.auth.AuthEvent
import com.github.reygnn.b2b.data.auth.AuthEventBus
import com.github.reygnn.b2b.data.auth.PkceAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val pkce: PkceAuthManager,
    authEvents: AuthEventBus,
) : ViewModel() {

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * One-shot launches the OAuth authorization URI in a Custom Tab. Compose
     * collects this and routes through `Intent.ACTION_VIEW`; doing the
     * Intent dispatch here would require an Activity-scoped Context that a
     * ViewModel shouldn't hold.
     */
    private val _launchUrl = Channel<String>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val launchUrl: Flow<String> = _launchUrl.receiveAsFlow()

    init {
        viewModelScope.launch {
            authEvents.events.collect { event ->
                _lastError.value = when (event) {
                    is AuthEvent.LoginFailed -> event.reason
                    is AuthEvent.LoginSucceeded -> null
                }
            }
        }
    }

    fun startLogin() {
        _lastError.value = null
        val url = pkce.buildAuthorizationUri(SCOPES)
        _launchUrl.trySend(url)
    }

    private companion object {
        val SCOPES = listOf(
            "app-remote-control",
            "user-modify-playback-state",
            "user-read-playback-state",
            "user-read-currently-playing",
        )
    }
}

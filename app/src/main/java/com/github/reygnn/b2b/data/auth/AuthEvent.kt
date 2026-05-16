package com.github.reygnn.b2b.data.auth

/**
 * Transient auth-flow events emitted by [PkceAuthManager] and consumed by
 * the UI (LoginViewModel surfaces them as Snackbars / inline errors).
 */
sealed interface AuthEvent {
    data object LoginSucceeded : AuthEvent
    data class LoginFailed(val reason: String) : AuthEvent
}

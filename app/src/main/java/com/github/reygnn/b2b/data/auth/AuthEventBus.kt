package com.github.reygnn.b2b.data.auth

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide bus for auth-flow events. Producers: [PkceAuthManager], [MainActivity]
 * (on OAuth callback errors). Consumers: [LoginViewModel].
 *
 * Backed by a [MutableSharedFlow] with DROP_OLDEST and an explicit buffer —
 * per `TESTING_CONVENTIONS.kt §3`, the default `SUSPEND` policy would deadlock
 * an emit while no subscriber is active (e.g. callback arrives before the
 * login screen subscribes).
 */
@Singleton
class AuthEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<AuthEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    suspend fun emit(event: AuthEvent) = _events.emit(event)
}

package com.github.reygnn.b2b.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production-default binding while the Spotify App Remote SDK is not yet on
 * the classpath. Emits no events; the orchestrator's collect terminates
 * immediately and the service idles.
 *
 * Replace with an SDK-backed implementation once the App Remote aar is
 * installed (see README setup step 3). The orchestrator logic does not
 * change — only the @Binds in BindsModule.
 */
@Singleton
class NoopPlayerStateSource @Inject constructor() : PlayerStateSource {
    override fun states(): Flow<PlayerState> = emptyFlow()
}

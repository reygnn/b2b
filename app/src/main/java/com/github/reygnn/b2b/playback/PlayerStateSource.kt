package com.github.reygnn.b2b.playback

import kotlinx.coroutines.flow.Flow

/**
 * Emits the current Spotify player state as Spotify App Remote reports it.
 * The production binding (once the App Remote SDK is on the classpath)
 * subscribes to `SpotifyAppRemote.playerApi.subscribeToPlayerState` and
 * republishes events as [PlayerState]. The current binding is
 * [NoopPlayerStateSource], which emits nothing — the orchestrator stays
 * idle until the SDK lands and is wired in.
 */
interface PlayerStateSource {
    fun states(): Flow<PlayerState>
}

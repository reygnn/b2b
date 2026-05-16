package com.github.reygnn.b2b.playback

import kotlinx.coroutines.flow.Flow

/**
 * Emits the current Spotify player state as Spotify App Remote reports it.
 * The production binding is [AppRemotePlayerStateSource], which wraps
 * `SpotifyAppRemote.playerApi.subscribeToPlayerState`. Tests substitute
 * a hand-rolled fake — see `PlaybackOrchestratorTest`.
 */
interface PlayerStateSource {
    fun states(): Flow<PlayerState>
}

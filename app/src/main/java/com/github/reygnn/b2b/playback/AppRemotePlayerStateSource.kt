package com.github.reygnn.b2b.playback

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

class SpotifyNotInstalledException : IOException("Spotify app is not installed on this device")

/**
 * Spotify App Remote-backed [PlayerStateSource]. Each call to [states]
 * connects to the locally installed Spotify app, subscribes to PlayerState,
 * and republishes events as our domain-typed [PlayerState]. The connection
 * lifetime is bound to the flow collection: cancelling the collector cancels
 * the subscription and disconnects the remote.
 *
 * Errors from the SDK (connection failures, subscription errors, Spotify
 * being killed mid-session) close the flow with the throwable; the
 * orchestrator's collect terminates and the foreground service can decide
 * to restart it. START_STICKY on the service gives us OS-level recreation
 * on its own; explicit retry policy can be added here once we see real-
 * device behaviour.
 *
 * Requires the `<queries>` element in `AndroidManifest.xml` so PackageManager
 * can see Spotify on Android 11+.
 */
@Singleton
class AppRemotePlayerStateSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Named("spotifyClientId") private val clientId: String,
    @param:Named("spotifyRedirectUri") private val redirectUri: String,
) : PlayerStateSource {

    override fun states(): Flow<PlayerState> = callbackFlow {
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            close(SpotifyNotInstalledException())
            awaitClose { /* nothing to clean up */ }
            return@callbackFlow
        }
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(false)
            .build()

        var appRemote: SpotifyAppRemote? = null
        var subscription: Subscription<com.spotify.protocol.types.PlayerState>? = null

        val listener = object : Connector.ConnectionListener {
            override fun onConnected(remote: SpotifyAppRemote) {
                appRemote = remote
                val sub = remote.playerApi.subscribeToPlayerState()
                    .setEventCallback { sdkState ->
                        val track = sdkState.track ?: return@setEventCallback
                        trySend(
                            PlayerState(
                                trackUri = track.uri,
                                positionMs = sdkState.playbackPosition,
                                durationMs = track.duration,
                                isPaused = sdkState.isPaused,
                            )
                        )
                    }
                sub.setErrorCallback { throwable -> close(throwable) }
                subscription = sub
            }

            override fun onFailure(throwable: Throwable) {
                close(throwable)
            }
        }

        SpotifyAppRemote.connect(context, params, listener)

        awaitClose {
            subscription?.cancel()
            appRemote?.let { SpotifyAppRemote.disconnect(it) }
        }
    }
}

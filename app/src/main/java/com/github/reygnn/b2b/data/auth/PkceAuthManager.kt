package com.github.reygnn.b2b.data.auth

import com.github.reygnn.b2b.data.remote.SpotifyAccountsApi
import com.github.reygnn.b2b.di.IoDispatcher
import com.github.reygnn.b2b.domain.model.Outcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * OAuth2 PKCE flow for Spotify.
 *
 * Flow:
 *  1. [buildAuthorizationUri] → launch in a Custom Tab.
 *  2. User authenticates; Spotify redirects to b2b://callback?code=…
 *  3. MainActivity catches the intent and calls [exchangeAuthorizationCode].
 *  4. [refresh] is called from AuthInterceptor on 401.
 *
 * Token HTTP calls go through [SpotifyAccountsApi], whose retrofit/OkHttp
 * stack is annotated with `@AccountsClient` and does NOT carry
 * [com.github.reygnn.b2b.data.remote.AuthInterceptor]. The token endpoint
 * does not accept Bearer auth; routing a 401 from it through the main
 * AuthInterceptor would trigger refresh-on-refresh recursion.
 */
@Singleton
class PkceAuthManager @Inject constructor(
    private val tokenStore: TokenStore,
    private val accountsApi: SpotifyAccountsApi,
    private val authEvents: AuthEventBus,
    @param:Named("spotifyClientId") private val clientId: String,
    @param:Named("spotifyRedirectUri") private val redirectUri: String,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    @Volatile private var pendingVerifier: String? = null

    fun buildAuthorizationUri(scopes: List<String>): String {
        val verifier = randomVerifier()
        pendingVerifier = verifier
        val challenge = s256(verifier)
        val scope = scopes.joinToString(" ")
        return "https://accounts.spotify.com/authorize" +
            "?response_type=code" +
            "&client_id=$clientId" +
            "&redirect_uri=${redirectUri.encode()}" +
            "&code_challenge_method=S256" +
            "&code_challenge=$challenge" +
            "&scope=${scope.encode()}"
    }

    suspend fun exchangeAuthorizationCode(code: String): Outcome<Unit> = withContext(io) {
        val outcome = doExchange(code)
        when (outcome) {
            is Outcome.Success -> authEvents.emit(AuthEvent.LoginSucceeded)
            is Outcome.Error -> authEvents.emit(AuthEvent.LoginFailed(outcome.describe()))
        }
        outcome
    }

    private suspend fun doExchange(code: String): Outcome<Unit> {
        val verifier = pendingVerifier
            ?: return Outcome.Error.Unknown("No pending PKCE verifier")
        pendingVerifier = null
        return try {
            val response = accountsApi.exchangeAuthorizationCode(
                code = code,
                redirectUri = redirectUri,
                clientId = clientId,
                codeVerifier = verifier,
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                return Outcome.Error.Unknown("HTTP ${response.code()}")
            }
            val refresh = body.refreshToken
                ?: return Outcome.Error.Unknown("No refresh_token in token response")
            tokenStore.store(
                accessToken = body.accessToken,
                refreshToken = refresh,
                expiresAtEpochMs = System.currentTimeMillis() + body.expiresInSeconds * 1000L,
            )
            Outcome.Success(Unit)
        } catch (_: IOException) {
            Outcome.Error.Network
        }
    }

    private fun Outcome.Error.describe(): String = when (this) {
        is Outcome.Error.Network -> "Network error"
        is Outcome.Error.Unauthenticated -> "Unauthenticated"
        is Outcome.Error.NotPremium -> "Premium required"
        is Outcome.Error.NoActiveDevice -> "No active device"
        is Outcome.Error.RateLimited -> "Rate limited (retry in ${retryAfterSeconds}s)"
        is Outcome.Error.Unknown -> message ?: "Unknown error"
    }

    /**
     * Called by [AuthInterceptor] on 401. Returns the new access token, or
     * null if no refresh token is stored or the refresh request failed. On
     * null, the interceptor surfaces the original 401 to the caller.
     */
    suspend fun refresh(): String? = withContext(io) {
        val currentRefresh = tokenStore.refreshToken() ?: return@withContext null
        try {
            val response = accountsApi.refreshToken(
                refreshToken = currentRefresh,
                clientId = clientId,
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) return@withContext null
            // Spotify may or may not rotate the refresh token; if it does, store
            // the new one, otherwise keep the existing one.
            val newRefresh = body.refreshToken ?: currentRefresh
            tokenStore.store(
                accessToken = body.accessToken,
                refreshToken = newRefresh,
                expiresAtEpochMs = System.currentTimeMillis() + body.expiresInSeconds * 1000L,
            )
            body.accessToken
        } catch (_: IOException) {
            null
        }
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return urlEncoder.encodeToString(digest)
    }

    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private companion object {
        // RFC 4648 URL-safe Base64 without padding — matches Spotify's PKCE
        // expectations and is JVM-native (no Android dependency, so tests
        // run without Robolectric).
        val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}

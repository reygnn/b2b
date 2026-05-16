package com.github.reygnn.b2b.data.auth

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
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
 * TODO: implement HTTP calls to https://accounts.spotify.com/api/token using a
 *       dedicated OkHttp client (without AuthInterceptor — that would recurse).
 */
@Singleton
class PkceAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: TokenStore,
) {
    @Volatile private var pendingVerifier: String? = null

    fun buildAuthorizationUri(clientId: String, redirectUri: String, scopes: List<String>): String {
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

    suspend fun exchangeAuthorizationCode(code: String, clientId: String, redirectUri: String) {
        val verifier = pendingVerifier ?: error("No pending PKCE verifier")
        pendingVerifier = null
        // TODO: POST x-www-form-urlencoded to accounts.spotify.com/api/token
        //   grant_type=authorization_code, code, redirect_uri, client_id, code_verifier=$verifier
        //   then tokenStore.store(...)
    }

    suspend fun refresh(): String? {
        val refresh = tokenStore.refreshToken() ?: return null
        // TODO: POST grant_type=refresh_token, refresh_token=$refresh, client_id=...
        //   then tokenStore.store(...) and return the new access token.
        return null
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")
}

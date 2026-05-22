package com.github.reygnn.b2b.data.remote

import com.github.reygnn.b2b.data.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Bearer token to every Spotify Web API request. On 401, triggers a
 * single refresh attempt via [TokenStore.refresh] (which coalesces concurrent
 * callers; see [com.github.reygnn.b2b.data.auth.PkceAuthManager.refresh])
 * and retries the request once with the new token.
 *
 * On a failed refresh, the original 401 response is returned unchanged
 * instead of being re-requested with the stale token — re-requesting would
 * burn a guaranteed-401 round-trip and a slot in the OkHttp pool while
 * delivering the same result.
 */
class AuthInterceptor(
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = runBlocking { tokenStore.accessToken() }
            ?: return chain.proceed(original)

        val authed = original.newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()

        val response = chain.proceed(authed)
        if (response.code != 401) return response

        // Pass the stale token as the coalescing anchor: if another thread
        // refreshed in the meantime, PkceAuthManager.refresh returns the
        // current access token without an HTTP request.
        val refreshed = runBlocking { tokenStore.refresh(staleAccessToken = token) }
            ?: return response // refresh failed — surface the original 401 unchanged

        response.close()
        val retried = original.newBuilder()
            .addHeader("Authorization", "Bearer $refreshed")
            .build()
        return chain.proceed(retried)
    }
}

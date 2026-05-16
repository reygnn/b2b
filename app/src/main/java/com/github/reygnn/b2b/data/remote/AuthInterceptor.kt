package com.github.reygnn.b2b.data.remote

import com.github.reygnn.b2b.data.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Bearer token to every Spotify Web API request. On 401, triggers a
 * single refresh attempt via TokenStore and retries the request once.
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

        // Refresh and retry once.
        response.close()
        val refreshed = runBlocking { tokenStore.refresh() } ?: return chain.proceed(authed)
        val retried = original.newBuilder()
            .addHeader("Authorization", "Bearer $refreshed")
            .build()
        return chain.proceed(retried)
    }
}

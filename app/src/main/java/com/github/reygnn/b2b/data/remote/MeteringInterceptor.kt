package com.github.reygnn.b2b.data.remote

import com.github.reygnn.b2b.diagnostics.SpotifyCallCounter
import com.github.reygnn.b2b.diagnostics.SpotifyCallFamily
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp **network** interceptor that increments [SpotifyCallCounter] for
 * every actual HTTP round-trip we send to Spotify. Used by both the Web API
 * client and the accounts (token) client.
 *
 * Wired as a network interceptor (not an application interceptor) so an
 * [AuthInterceptor] 401-retry counts as two calls — which is what Spotify's
 * side actually sees. Application-level interceptors are called once per
 * call chain and would under-report by exactly the retries that AuthInterceptor
 * silently issues.
 *
 * Counts on every response, success or failure. A 429 still costs a slot in
 * Spotify's rolling window and is therefore part of the volume we want to
 * track — see ADR-0003 and NEW-ARTISTS.md.
 */
class MeteringInterceptor @Inject constructor(
    private val counter: SpotifyCallCounter,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        counter.record(SpotifyCallFamily.fromPath(response.request.url.encodedPath))
        return response
    }
}

package com.github.reygnn.b2b.data.remote

import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.diagnostics.SpotifyCallCounter
import com.github.reygnn.b2b.diagnostics.SpotifyCallFamily
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp **network** interceptor that increments [SpotifyCallCounter] for
 * every actual HTTP round-trip we send to Spotify, and emits one log line
 * per call into the in-app [LogSink] so the call stream is visible without
 * an external network proxy. Used by both the Web API client and the
 * accounts (token) client.
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
 *
 * The log line is `log()` (not `trace()`) on purpose: penalty triggers tend
 * to be invisible at the time and only become interesting later, so we want
 * the HTTP stream captured even when the trace toggle is off. At realistic
 * b2b volume (~200 calls/day) the 500-entry LogBuffer still holds 2+ days
 * of history.
 */
class MeteringInterceptor @Inject constructor(
    private val counter: SpotifyCallCounter,
    private val log: LogSink,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        counter.record(SpotifyCallFamily.fromPath(response.request.url.encodedPath))
        log.log("http: ${request.method} ${response.request.url.encodedPath} → ${response.code}")
        return response
    }
}

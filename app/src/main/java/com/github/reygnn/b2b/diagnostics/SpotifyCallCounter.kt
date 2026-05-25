package com.github.reygnn.b2b.diagnostics

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Rolling 24 h counter of outbound Spotify HTTP calls, broken down by
 * endpoint family. In-memory only — the buffer is wiped on process death,
 * which is acceptable: this is diagnostic telemetry, not state we trust to
 * survive a crash. The cadence at which we surface a snapshot
 * ([PoolSyncWorker] logs one stats line per tick) is what makes the
 * numbers durable inside the [LogBuffer].
 *
 * Design context (NEW-ARTISTS.md, mitigation M4): after the 2026-05-24
 * rate-limit penalty we had no measurement of our own call volume during
 * the add-session that triggered it. This counter gives us one half of the
 * eventually-needed equation — Spotify's per-app quota stays undocumented,
 * but at least our own emission rate is observable.
 *
 * Thread-safety: a single `synchronized(lock)` around every mutation and
 * every read snapshot. The internal deque is never exposed.
 */
@Singleton
class SpotifyCallCounter @Inject constructor() {
    private val lock = Any()
    private val entries = ArrayDeque<TimedCall>()

    /**
     * Wall-clock source. Production reads `System.currentTimeMillis()`;
     * tests overwrite this with a controlled lambda to make rolling-window
     * assertions deterministic without dragging Robolectric or a real
     * clock advance into the JVM unit test path. (Same pattern as
     * `ArtistsViewModel.clock`.) Not exposed as a constructor parameter
     * because Hilt treats a default-valued `@Inject` constructor as two
     * inject constructors and refuses to compile.
     */
    @androidx.annotation.VisibleForTesting
    internal var clock: () -> Long = { System.currentTimeMillis() }

    fun record(family: SpotifyCallFamily) {
        synchronized(lock) {
            val now = clock()
            entries.addLast(TimedCall(now, family))
            pruneOlderThan(now - MAX_RETENTION_MS)
        }
    }

    /**
     * Per-family count of calls recorded within [window] back from
     * [nowMs]. Families with zero hits in the window are omitted from the
     * returned map; the caller defaults missing keys to 0 when formatting.
     */
    fun stats(window: Duration, nowMs: Long = clock()): Map<SpotifyCallFamily, Int> {
        val cutoff = nowMs - window.inWholeMilliseconds
        return synchronized(lock) {
            // Opportunistic prune: keeps the deque from growing without
            // bound during a long-lived process even if `record` happens
            // to drop quiet for a while.
            pruneOlderThan(nowMs - MAX_RETENTION_MS)
            entries.asSequence()
                .filter { it.epochMs >= cutoff }
                .groupingBy { it.family }
                .eachCount()
        }
    }

    /** Caller already holds [lock]. */
    private fun pruneOlderThan(cutoffMs: Long) {
        while (entries.isNotEmpty() && entries.first().epochMs < cutoffMs) {
            entries.removeFirst()
        }
    }

    private data class TimedCall(val epochMs: Long, val family: SpotifyCallFamily)

    private companion object {
        // We never need calls beyond 24 h for the per-day metric. Anything
        // older gets pruned eagerly so the deque size stays bounded by the
        // realistic daily call volume (low thousands at most).
        val MAX_RETENTION_MS = 24.hours.inWholeMilliseconds
    }
}

enum class SpotifyCallFamily {
    SEARCH,
    ARTISTS,
    ALBUMS,
    QUEUE,
    ME,
    TOKEN,
    OTHER,
    ;

    companion object {
        /**
         * Classify a Spotify URL path into a family. Unknown paths fall
         * into [OTHER] rather than throwing — a new endpoint added without
         * updating this enum should be diagnosable in the log, not crash
         * the interceptor.
         *
         * The `/v1/me/player/queue` check has to come before the `/v1/me`
         * check because the queue path is a prefix of the profile path.
         */
        fun fromPath(path: String): SpotifyCallFamily = when {
            path.startsWith("/v1/search") -> SEARCH
            path.startsWith("/v1/artists/") -> ARTISTS
            path.startsWith("/v1/albums/") -> ALBUMS
            path.startsWith("/v1/me/player/queue") -> QUEUE
            path == "/v1/me" -> ME
            path == "/api/token" -> TOKEN
            else -> OTHER
        }
    }
}

package com.github.reygnn.b2b.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

/**
 * Periodic sync: walk every whitelisted artist, fetch all album tracks, upsert
 * into the pool, then prune tracks from removed artists.
 *
 * Triggers:
 *  - Periodic, every 24h, on UNMETERED network.
 *  - One-shot when the user adds an artist.
 *
 * Rate-limit handling: on 429, honour the Retry-After header in-run via
 * `delay(...)` rather than returning `Result.retry()`. Spotify's rate-limit
 * window is ~30 s while WorkManager's exponential backoff starts at 30 s
 * and grows — staying in-run is strictly cheaper and preserves the artist-
 * by-artist progress already accumulated. A per-artist budget caps how
 * many consecutive 429s we wait through; once exceeded, we hand off to
 * WorkManager's backoff via `Result.retry()`.
 */
@HiltWorker
class PoolSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val artistRepo: ArtistRepository,
    private val poolRepo: PoolRepository,
    private val whitelistDao: WhitelistDao,
    private val log: LogSink,
    private val rateLimitStore: RateLimitStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Settings → "Sync now" can pass force=true after the user dismisses
        // the rate-limit warning dialog. Every other trigger (periodic 24 h,
        // Artists "Sync now" button) leaves the flag false and respects the
        // skip below.
        val force = inputData.getBoolean(KEY_FORCE, false)
        // If we're still inside a rate-limit window that Spotify previously
        // announced, do not hit the API again — Spotify can interpret a
        // request during the announced wait as hammering and extend the
        // penalty. The countdown lives on the home screen; the user can
        // see why the run was a no-op.
        //
        // Result.success() (not retry): WorkManager would otherwise re-fire
        // on its exponential backoff before the ban window expires. The
        // next regular trigger (periodic 24h or the user pressing "Sync
        // now" after the countdown reaches 0) will pick it up.
        if (!force) rateLimitStore.state().value?.let { state ->
            val remaining = state.remainingSecondsAt(System.currentTimeMillis())
            if (remaining > 0) {
                log.log("sync: rate-limited for ${remaining}s more, skipping run")
                return Result.success()
            }
        }
        // Last-resort cap so a runaway sync can never leave the worker stuck
        // in RUNNING forever — the symptom that triggered this fix: 1 h
        // "Syncing now…" because the fetchAllTrackUrisForArtist pagination
        // loop didn't terminate. With the per-artist + per-album safeguards
        // in ArtistRepositoryImpl this should never fire, but if Spotify
        // throttles us with 30 s delays across many artists we still want
        // a deterministic ceiling. On timeout: hand off to WorkManager's
        // exponential backoff via Result.retry().
        return withTimeoutOrNull(MAX_RUN_DURATION) { runSync() } ?: Result.retry()
    }

    private suspend fun runSync(): Result {
        // Two queries deliberately. `activeIds` drives the fetch loop —
        // inactive artists are paused and shouldn't burn Spotify API quota.
        // `allIds` drives the final prune so the inactive artists' pool
        // slices survive (they would otherwise be flagged as "not in the
        // current sync set" and wiped, defeating the lazy-stays design).
        val allIds = whitelistDao.allIds()
        if (allIds.isEmpty()) {
            poolRepo.deleteTracksForRemovedArtists(emptySet())
            log.log("sync: empty whitelist, done")
            rateLimitStore.clear()
            return Result.success()
        }
        val activeIds = whitelistDao.activeIds()
        log.log("sync: start, ${activeIds.size} active / ${allIds.size} whitelisted")

        for (id in activeIds) {
            log.log("sync: $id fetching…")
            var rateLimitAttempts = 0
            while (true) {
                when (val tracks = artistRepo.fetchAllTrackUrisForArtist(id)) {
                    is Outcome.Success -> {
                        log.log("sync: $id → ${tracks.value.size} tracks")
                        // Atomic swap of this artist's slice of the pool.
                        // The DAO wraps delete + upsert in a single SQLite
                        // transaction; a worker kill in the middle leaves the
                        // old slice intact rather than briefly empty (which
                        // would let a parallel pickNext miss the artist).
                        poolRepo.replaceTracksForArtist(id, tracks.value)
                        break
                    }
                    is Outcome.Error.RateLimited -> {
                        // Spotify has been seen returning Retry-After values
                        // in the tens of thousands (hours) when an account
                        // hit a hard penalty. Sleeping in-run for that long
                        // is useless — we'd never wake up before the
                        // 10-minute worker timeout. Cap: anything above
                        // MAX_RATE_LIMIT_WAIT_SECONDS hands off to
                        // WorkManager's exponential backoff via retry(),
                        // which will try again on a fresh schedule.
                        val wait = tracks.retryAfterSeconds
                        // Surface the wait to the UI in every rate-limit
                        // branch (in-run delay, cap-exceed, budget-exceed),
                        // so the user sees a countdown instead of a silent
                        // "sync did nothing". The clear() at the success-
                        // exit below resets it once syncing succeeds.
                        rateLimitStore.record(wait)
                        if (wait > MAX_RATE_LIMIT_WAIT_SECONDS) {
                            log.log("sync: $id rate-limit ${wait}s exceeds cap, deferring → retry")
                            return Result.retry()
                        }
                        if (++rateLimitAttempts >= MAX_RATE_LIMIT_ATTEMPTS) {
                            log.log("sync: $id rate-limit budget exceeded → retry")
                            return Result.retry()
                        }
                        log.log("sync: $id rate-limited, sleeping ${wait}s")
                        delay(wait * 1000L)
                    }
                    is Outcome.Error.Network -> {
                        log.log("sync: $id network error → retry")
                        return Result.retry()
                    }
                    is Outcome.Error -> {
                        log.log("sync: $id error → failure")
                        return Result.failure()
                    }
                }
            }
        }

        poolRepo.deleteTracksForRemovedArtists(allIds.toSet())
        log.log("sync: complete")
        // The sync got through every active artist without a terminal
        // rate-limit; any previously recorded wait is now stale (the
        // ban window has plainly elapsed, or Spotify lifted it).
        rateLimitStore.clear()
        return Result.success()
    }

    companion object {
        /**
         * Input-data key for forcing a sync past the rate-limit skip
         * (Settings → "Sync now" after the user confirms the warning).
         * Default false everywhere else.
         */
        const val KEY_FORCE = "force"

        private const val MAX_RATE_LIMIT_ATTEMPTS = 3

        // 10 minutes is generous for a few artists with normal-sized
        // discographies even when Spotify is rate-limiting (each 429
        // burns up to 30 s). Beyond this it's not throttling — it's
        // pathology, and we'd rather hand off to WorkManager retry.
        private val MAX_RUN_DURATION = 10.minutes

        // Spotifys typical Retry-After is 1–30 s. Real-world we've seen
        // outliers in the tens of thousands of seconds after sustained
        // hammering — those want WorkManager backoff, not an in-run wait.
        private const val MAX_RATE_LIMIT_WAIT_SECONDS = 120
    }
}

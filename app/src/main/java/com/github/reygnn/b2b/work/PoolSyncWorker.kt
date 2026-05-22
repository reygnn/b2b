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
 *  - Manual via the Artists / Settings screen.
 *
 * Rate-limit handling: on 429, honour the Retry-After header in-run via
 * `delay(...)` rather than returning `Result.retry()`. Spotify's rate-limit
 * window is ~30 s while WorkManager's exponential backoff starts at 30 s
 * and grows — staying in-run is strictly cheaper and preserves the artist-
 * by-artist progress already accumulated. A per-artist budget caps how
 * many consecutive 429s we wait through; once exceeded, we hand off to
 * WorkManager's backoff via `Result.retry()`.
 *
 * Two-tier timeout: each artist gets its own [MAX_PER_ARTIST_DURATION]
 * budget. A pathologically slow artist therefore can't stall the whole
 * run — it gets skipped (slice keeps its previous data) and the next
 * artist takes its turn. [MAX_RUN_DURATION] is the outer safety net for
 * non-fetch hangs (DB, infinite loops outside the fetch path).
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
        return withTimeoutOrNull(MAX_RUN_DURATION) { runSync(force) } ?: Result.retry()
    }

    private suspend fun runSync(force: Boolean): Result {
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

        // Tracks whether we've already issued an actual fetch this run.
        // Skipped artists (freshness) don't count — the cooldown only
        // makes sense between artists that actually hit the API.
        var hasFetchedThisRun = false
        for (id in activeIds) {
            // Freshness skip: if this artist's slice was already refreshed
            // recently we don't need to re-fetch — Spotify's quota is per
            // 30 s window, and a manual sync run minutes after the periodic
            // one would otherwise burn the same hundreds of requests for
            // zero gain. The Settings-override force=true bypasses this
            // (alongside the rate-limit skip in doWork). New artists have
            // no rows yet → lastSync == null → falls through to fetch.
            if (!force) {
                val lastSync = poolRepo.lastSyncedEpochMsForArtist(id)
                if (lastSync != null) {
                    val ageMs = System.currentTimeMillis() - lastSync
                    if (ageMs in 0 until FRESH_THRESHOLD_MS) {
                        log.log("sync: $id skip (synced ${ageMs / 60_000}min ago)")
                        continue
                    }
                }
            }
            // Inter-artist cooldown: wait long enough that Spotify's 30 s
            // rate-limit window is empty before we issue the next burst.
            // The first fetch of the run is exempt — we don't want to
            // delay the only thing that's about to happen. Force=true
            // does NOT bypass this: hitting the API rapid-fire would
            // re-create the very problem the override is meant to recover
            // from.
            if (hasFetchedThisRun) {
                log.log("sync: cooldown ${INTER_ARTIST_DELAY_MS / 1000}s before next artist")
                delay(INTER_ARTIST_DELAY_MS)
            }
            hasFetchedThisRun = true

            // Per-artist budget. A runaway pagination loop (or one
            // pathological artist) used to risk the entire run via the
            // outer MAX_RUN_DURATION; now it just costs that artist's
            // turn. Their pool slice keeps whatever it had — atomic-
            // replacement only applies on a clean fetch.
            val outcome = withTimeoutOrNull(MAX_PER_ARTIST_DURATION) {
                fetchAndStoreArtist(id)
            }
            when (outcome) {
                null -> {
                    log.log("sync: $id timed out after " +
                        "${MAX_PER_ARTIST_DURATION.inWholeSeconds}s, skipping")
                    // continue to next artist
                }
                ArtistOutcome.Ok -> Unit
                ArtistOutcome.Retry -> return Result.retry()
                ArtistOutcome.Fail -> return Result.failure()
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

    /**
     * Fetch [id]'s tracks and atomically swap them into the pool.
     * Returns a per-artist outcome — the caller decides whether to keep
     * going, retry the run, or fail. The per-artist [MAX_PER_ARTIST_DURATION]
     * timeout wraps this call from the outside, so a stuck fetch surfaces
     * as `withTimeoutOrNull(...) == null` to [runSync].
     */
    private suspend fun fetchAndStoreArtist(id: String): ArtistOutcome {
        log.log("sync: $id fetching…")
        var rateLimitAttempts = 0
        while (true) {
            when (val tracks = artistRepo.fetchAllTrackUrisForArtist(id)) {
                is Outcome.Success -> {
                    log.log("sync: $id → ${tracks.value.size} tracks")
                    // Atomic swap of this artist's slice of the pool.
                    // The DAO wraps delete + upsert in a single SQLite
                    // transaction; a worker kill in the middle leaves
                    // the old slice intact rather than briefly empty
                    // (which would let a parallel pickNext miss the
                    // artist).
                    poolRepo.replaceTracksForArtist(id, tracks.value)
                    return ArtistOutcome.Ok
                }
                is Outcome.Error.RateLimited -> {
                    // Spotify has been seen returning Retry-After values
                    // in the tens of thousands (hours) when an account
                    // hit a hard penalty. Sleeping in-run for that long
                    // is useless. Cap: anything above
                    // MAX_RATE_LIMIT_WAIT_SECONDS hands off to WorkManager's
                    // exponential backoff via retry().
                    val wait = tracks.retryAfterSeconds
                    // Surface the wait to the UI in every rate-limit
                    // branch (in-run delay, cap-exceed, budget-exceed),
                    // so the user sees a countdown instead of a silent
                    // "sync did nothing". The clear() at runSync's
                    // success-exit resets it once syncing succeeds.
                    rateLimitStore.record(wait)
                    if (wait > MAX_RATE_LIMIT_WAIT_SECONDS) {
                        log.log("sync: $id rate-limit ${wait}s exceeds cap, deferring → retry")
                        return ArtistOutcome.Retry
                    }
                    if (++rateLimitAttempts >= MAX_RATE_LIMIT_ATTEMPTS) {
                        log.log("sync: $id rate-limit budget exceeded → retry")
                        return ArtistOutcome.Retry
                    }
                    log.log("sync: $id rate-limited, sleeping ${wait}s")
                    delay(wait * 1000L)
                }
                is Outcome.Error.Network -> {
                    log.log("sync: $id network error → retry")
                    return ArtistOutcome.Retry
                }
                is Outcome.Error -> {
                    log.log("sync: $id error → failure")
                    return ArtistOutcome.Fail
                }
            }
        }
    }

    private sealed interface ArtistOutcome {
        object Ok : ArtistOutcome
        object Retry : ArtistOutcome
        object Fail : ArtistOutcome
    }

    companion object {
        /**
         * Input-data key for forcing a sync past the rate-limit skip
         * (Settings → "Sync now" after the user confirms the warning).
         * Default false everywhere else.
         */
        const val KEY_FORCE = "force"

        private const val MAX_RATE_LIMIT_ATTEMPTS = 3

        // Per-artist budget. Generous enough for a normal Spotify
        // discography (a few seconds of API + the in-run 429 delays
        // capped at MAX_RATE_LIMIT_WAIT_SECONDS × MAX_RATE_LIMIT_ATTEMPTS),
        // but well short of "this artist is broken, move on". The whole
        // point is that this scales with the number of artists, not the
        // size of the worst one — see [MAX_RUN_DURATION].
        private val MAX_PER_ARTIST_DURATION = 2.minutes

        // Outer safety net for non-fetch hangs (DB stall, infinite loop
        // outside the artist fetch path). With per-artist budgeting
        // doing the heavy lifting, this should never fire in practice;
        // 30 minutes is the "obviously something is wrong" upper bound.
        private val MAX_RUN_DURATION = 30.minutes

        // Spotifys typical Retry-After is 1–30 s. Real-world we've seen
        // outliers in the tens of thousands of seconds after sustained
        // hammering — those want WorkManager backoff, not an in-run wait.
        private const val MAX_RATE_LIMIT_WAIT_SECONDS = 120

        // Per-artist freshness threshold. 18 h sits below the 24 h
        // periodic interval so the next scheduled run always re-fetches
        // (the slice's age has exceeded the threshold by then), while a
        // manual sync minutes-to-hours after the periodic short-circuits
        // each artist whose slice is still fresh — protecting Spotify's
        // 30 s rate-limit window from the next burst.
        private const val FRESH_THRESHOLD_MS = 18L * 60 * 60 * 1000

        // Wait between artists that actually hit the API. Sized to fully
        // clear Spotify's 30 s rolling rate-limit window before the next
        // burst starts, so two artists never share a window. The first
        // fetch of a run skips this; see [hasFetchedThisRun].
        private const val INTER_ARTIST_DELAY_MS = 30_000L
    }
}

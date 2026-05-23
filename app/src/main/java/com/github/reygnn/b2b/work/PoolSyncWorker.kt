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
import kotlin.time.Duration.Companion.hours

/**
 * Trickle sync: each periodic tick refreshes at most **one** active artist.
 *
 * The previous design walked every active artist in a single run, separated
 * by a 30 s inter-artist cooldown. That worked for a handful of artists but
 * produced bursts that, combined with WorkManager backoff retries preserving
 * a `force=true` input flag, ran the app into Spotify's penalty mode in the
 * 2026-05-22/23 incident (see ADR-0003). The trickle design eliminates the
 * burst at the architectural root: with one artist per tick, two artist
 * fetches can never share Spotify's documented 30 s rolling rate-limit
 * window — see [B2BApp.schedulePoolSync] for the 15 min cadence.
 *
 * Selection happens in SQL via [WhitelistDao.pickNextToSync]:
 *  - active artists only,
 *  - never-synced artists (no `pool_track` row) first, ordered by their
 *    `addedAtEpochMs` so the user's add-order is honored,
 *  - then the artist whose slice has aged past [FRESHNESS_FLOOR_MS],
 *    stalest first.
 *
 * When nothing is eligible (everything fresher than the floor) the tick is
 * a no-op `Result.success()` — no API call, no log clutter beyond a single
 * "idle" line.
 *
 * Rate-limit handling shrinks to nothing structural: a 429 is recorded in
 * the [RateLimitStore] so the UI shows a countdown, then we hand off to
 * WorkManager's exponential backoff via `Result.retry()`. There is no
 * in-run delay, no cap, no per-artist budget, no force flag — none of
 * those are reachable with one fetch per tick.
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
        val floor = System.currentTimeMillis() - FRESHNESS_FLOOR_MS
        val nextId = whitelistDao.pickNextToSync(floor)
        if (nextId == null) {
            log.log("sync: nothing eligible, idle")
            return Result.success()
        }
        log.log("sync: $nextId fetching…")
        return when (val tracks = artistRepo.fetchAllTrackUrisForArtist(nextId)) {
            is Outcome.Success -> {
                log.log("sync: $nextId → ${tracks.value.size} tracks")
                poolRepo.replaceTracksForArtist(nextId, tracks.value)
                // Prune rows whose artist was removed from the whitelist
                // since the last successful tick. Cheap (DELETE…WHERE NOT
                // IN) and idempotent.
                poolRepo.deleteTracksForRemovedArtists(whitelistDao.allIds().toSet())
                // A successful tick proves Spotify is talking to us again;
                // any stored penalty is stale.
                rateLimitStore.clear()
                Result.success()
            }
            is Outcome.Error.RateLimited -> {
                // Should be vanishingly rare at one fetch per 15 min, but if
                // it happens we keep the UI honest with a countdown and let
                // WorkManager's exponential backoff space the retries.
                rateLimitStore.record(tracks.retryAfterSeconds)
                log.log("sync: $nextId rate-limited ${tracks.retryAfterSeconds}s → retry")
                Result.retry()
            }
            is Outcome.Error.Network -> {
                log.log("sync: $nextId network error → retry")
                Result.retry()
            }
            is Outcome.Error -> {
                log.log("sync: $nextId error → failure")
                Result.failure()
            }
        }
    }

    companion object {
        /**
         * Artists whose latest pool row is younger than this are skipped on
         * this tick — they don't need a refresh yet. 24 h sits comfortably
         * below the time between meaningful Spotify catalog changes for any
         * one artist and well above the 15 min tick cadence, so steady-state
         * runs idle whenever the whitelist is small enough that everything
         * has been refreshed recently.
         */
        val FRESHNESS_FLOOR_MS = 24.hours.inWholeMilliseconds
    }
}

package com.github.reygnn.b2b.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        // Last-resort cap so a runaway sync can never leave the worker stuck
        // in RUNNING forever — the symptom that triggered this fix: 1 h
        // "Syncing now…" because the fetchAllTrackUrisForArtist pagination
        // loop didn't terminate. With the per-artist + per-album safeguards
        // in ArtistRepositoryImpl this should never fire, but if Spotify
        // throttles us with 30 s delays across many artists we still want
        // a deterministic ceiling. On timeout: hand off to WorkManager's
        // exponential backoff via Result.retry().
        withTimeoutOrNull(MAX_RUN_DURATION) { runSync() } ?: Result.retry()

    private suspend fun runSync(): Result {
        val artistIds = whitelistDao.allIds()
        if (artistIds.isEmpty()) {
            poolRepo.deleteTracksForRemovedArtists(emptySet())
            return Result.success()
        }

        for (id in artistIds) {
            var rateLimitAttempts = 0
            while (true) {
                when (val tracks = artistRepo.fetchAllTrackUrisForArtist(id)) {
                    is Outcome.Success -> {
                        // Replace this artist's slice of the pool wholesale.
                        // `upsertTracks` only replaces rows that collide on
                        // URI — leftover tracks from a previous (buggier)
                        // sync would otherwise linger forever under this
                        // artist's id. Deleting first guarantees the pool
                        // matches the current Spotify view of the artist.
                        poolRepo.deleteTracksForArtist(id)
                        poolRepo.upsertTracks(tracks.value)
                        break
                    }
                    is Outcome.Error.RateLimited -> {
                        if (++rateLimitAttempts >= MAX_RATE_LIMIT_ATTEMPTS) {
                            return Result.retry()
                        }
                        delay(tracks.retryAfterSeconds * 1000L)
                    }
                    is Outcome.Error.Network -> return Result.retry()
                    is Outcome.Error -> return Result.failure()
                }
            }
        }

        poolRepo.deleteTracksForRemovedArtists(artistIds.toSet())
        return Result.success()
    }

    private companion object {
        const val MAX_RATE_LIMIT_ATTEMPTS = 3

        // 10 minutes is generous for a few artists with normal-sized
        // discographies even when Spotify is rate-limiting (each 429
        // burns up to 30 s). Beyond this it's not throttling — it's
        // pathology, and we'd rather hand off to WorkManager retry.
        val MAX_RUN_DURATION = 10.minutes
    }
}

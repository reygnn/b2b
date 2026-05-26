package com.github.reygnn.b2b.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.repository.KillSwitchStore
import com.github.reygnn.b2b.data.repository.RateLimitStore
import com.github.reygnn.b2b.diagnostics.LogSink
import com.github.reygnn.b2b.diagnostics.SpotifyCallCounter
import com.github.reygnn.b2b.diagnostics.SpotifyCallFamily
import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.time.Duration.Companion.days
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
 * Two layers of rate-limit handling, both lightweight:
 *  - **Active-skip:** at `doWork` entry, if Spotify has announced a wait
 *    and the [RateLimitStore] countdown is still running, exit
 *    `Result.success()` without hitting the API. Spotify's documented
 *    guidance is to respect `Retry-After`; sending requests during the
 *    announced wait is the documented path to penalty extension. Safe
 *    here because the trickle has no `force` flag — every doWork
 *    invocation honors the gate, no exception, no parameter to override.
 *  - **On a fresh 429**: record the announced wait to the store (so the
 *    UI countdown stays accurate AND the next tick's active-skip fires)
 *    and return `Result.retry()` to delegate spacing to WorkManager's
 *    exponential backoff. No in-run delay.
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
    private val killSwitchStore: KillSwitchStore,
    private val callCounter: SpotifyCallCounter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Kill switch wins over everything else. User-driven gate that
        // silences sync (and search, gated separately in ArtistsViewModel)
        // until manually disabled from the Home status card. Auto-enabled
        // by [RateLimitStore.record] so any 429 from any surface trips it;
        // the user can flip it back off mid-penalty if they want.
        //
        // Stats emission is suppressed here for the same reason as on the
        // rate-limit-skip branch below: this branch fires every 15 min
        // until the user disables the switch and identical stats lines
        // would crowd out the entries we actually want to see.
        if (killSwitchStore.state().value) {
            log.log("sync: kill-switch on, skipping tick")
            return Result.success()
        }
        // Active-skip: respect Spotify's announced wait. The previous
        // design exposed a `force=true` bypass to this gate, which
        // WorkManager preserved across backoff retries and the
        // 2026-05-22/23 incident exploited. The trickle has no such
        // override, so this check cannot be bypassed by any code path
        // currently reachable from the UI or the worker config.
        //
        // Stats emission is deliberately skipped on this branch: during a
        // multi-hour penalty WorkManager fires every 15 min, and emitting
        // an identical stats line ~80 times in a 20 h window would crowd
        // out the entries we actually want to see (NEW-ARTISTS.md M4 is
        // about diagnosing add-session bursts, not about wallpapering the
        // log during a known idle wait).
        rateLimitStore.state().value?.let { state ->
            val remaining = state.remainingSecondsAt(System.currentTimeMillis())
            if (remaining > 0) {
                log.log("sync: rate-limited for ${remaining}s, skipping tick")
                return Result.success()
            }
        }
        val result = runTick()
        emitStatsIfAny()
        return result
    }

    private suspend fun runTick(): Result {
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

    /**
     * NEW-ARTISTS.md M4: surface the per-family Spotify call volume so a
     * post-mortem after the next rate-limit penalty can answer the
     * "what was our daily call rate at the time?" question without a
     * dedicated debug screen. Skipped when nothing happened in the last
     * 24 h to avoid a flood of `total=0` lines on quiet days.
     */
    private fun emitStatsIfAny() {
        val stats = callCounter.stats(window = 24.hours)
        val total = stats.values.sum()
        if (total == 0) return
        fun f(family: SpotifyCallFamily) = stats[family] ?: 0
        log.log(
            "stats 24h: " +
                "search=${f(SpotifyCallFamily.SEARCH)} " +
                "artists=${f(SpotifyCallFamily.ARTISTS)} " +
                "albums=${f(SpotifyCallFamily.ALBUMS)} " +
                "queue=${f(SpotifyCallFamily.QUEUE)} " +
                "me=${f(SpotifyCallFamily.ME)} " +
                "token=${f(SpotifyCallFamily.TOKEN)} " +
                "other=${f(SpotifyCallFamily.OTHER)} " +
                "total=$total",
        )
    }

    companion object {
        /**
         * Artists whose latest pool row is younger than this are skipped on
         * this tick — they don't need a refresh yet.
         *
         * History: was 24 h until 2026-05-26. The 24 h floor produced a
         * predictable daily burst — every whitelist artist re-staled
         * roughly simultaneously every 24 h, and the trickle worked through
         * them at one per 15 min until done. For an 11-artist whitelist
         * this meant ~11 `/v1/artists/{id}/tracks` calls clustered in a
         * ~2-3 h window every day. In post-penalty mode Spotify's ARTISTS
         * quota tolerates ~10 calls per 24 h, so the 11th call in the
         * cluster reliably tripped a fresh 23 h penalty. See ADR-0003 and
         * `project_b2b_rate_limit_pattern_2026_05.md`.
         *
         * 7 d makes the steady-state burst weekly instead of daily, dropping
         * ARTISTS volume by 7× while keeping pool freshness well within the
         * "Spotify catalog changes per artist" timescale. Newly-added
         * artists still get priority via the never-synced branch of
         * [WhitelistDao.pickNextToSync] and are fetched on the very next
         * tick — the floor only governs re-sync of already-synced artists.
         */
        val FRESHNESS_FLOOR_MS = 7.days.inWholeMilliseconds
    }
}

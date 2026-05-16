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

/**
 * Periodic sync: walk every whitelisted artist, fetch all album tracks, upsert
 * into the pool, then prune tracks from removed artists.
 *
 * Triggers:
 *  - Periodic, every 24h, on UNMETERED network.
 *  - One-shot when the user adds an artist.
 */
@HiltWorker
class PoolSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val artistRepo: ArtistRepository,
    private val poolRepo: PoolRepository,
    private val whitelistDao: WhitelistDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val artistIds = whitelistDao.allIds()
        if (artistIds.isEmpty()) {
            poolRepo.deleteTracksForRemovedArtists(emptySet())
            return Result.success()
        }

        for (id in artistIds) {
            when (val tracks = artistRepo.fetchAllTrackUrisForArtist(id)) {
                is Outcome.Success -> poolRepo.upsertTracks(tracks.value)
                is Outcome.Error.RateLimited -> return Result.retry()
                is Outcome.Error.Network -> return Result.retry()
                is Outcome.Error -> return Result.failure()
            }
        }

        poolRepo.deleteTracksForRemovedArtists(artistIds.toSet())
        return Result.success()
    }
}

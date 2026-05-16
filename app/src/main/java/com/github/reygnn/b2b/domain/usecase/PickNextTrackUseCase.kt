package com.github.reygnn.b2b.domain.usecase

import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import javax.inject.Inject

/**
 * Picks the next track to enqueue. Pure domain logic — no Android, no Retrofit,
 * no Room. Pool and recency are injected via repository interfaces.
 *
 * Algorithm:
 * 1. Take the last [antiRepeatWindow] played URIs.
 * 2. Ask the pool repo for a random track NOT in that set.
 * 3. If the pool is exhausted vs the window (small whitelist), fall back to
 *    any random track in the pool.
 * 4. If the pool is empty, return Outcome.Error.Unknown("empty pool").
 */
class PickNextTrackUseCase @Inject constructor(
    private val pool: PoolRepository,
    private val recents: RecentlyPlayedRepository,
) {
    suspend operator fun invoke(antiRepeatWindow: Int): Outcome<Track> {
        val excluded = recents.recent(antiRepeatWindow).toSet()
        val pick = pool.randomTrackExcluding(excluded)
            ?: pool.randomTrackExcluding(excludedUris = emptySet())
            ?: return Outcome.Error.Unknown("empty pool")
        return Outcome.Success(pick)
    }
}

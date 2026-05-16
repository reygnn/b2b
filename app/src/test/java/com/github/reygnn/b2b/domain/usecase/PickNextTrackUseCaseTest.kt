package com.github.reygnn.b2b.domain.usecase

import com.github.reygnn.b2b.domain.model.Outcome
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Canonical test for this codebase:
 *   - Single dispatcher via [MainDispatcherRule] (see TESTING_CONVENTIONS.kt).
 *   - `runTest(mainRule.testScheduler)` so virtual clock is shared.
 *   - MockK for all collaborators, no Mockito, no hand-rolled fakes here.
 *   - No `TestScope` or `StandardTestDispatcher` instantiated inside tests.
 */
class PickNextTrackUseCaseTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val pool: PoolRepository = mockk()
    private val recents: RecentlyPlayedRepository = mockk()
    private val sut = PickNextTrackUseCase(pool, recents)

    @Test fun `returns track when pool has non-recent candidate`() =
        runTest(mainRule.testScheduler) {
            val track = trackOf("spotify:track:a")
            coEvery { recents.recent(50) } returns listOf("spotify:track:x")
            coEvery { pool.randomTrackExcluding(setOf("spotify:track:x")) } returns track

            val result = sut(antiRepeatWindow = 50)

            assertThat(result).isEqualTo(Outcome.Success(track))
            coVerify(exactly = 0) { pool.randomTrackExcluding(emptySet()) }
        }

    @Test fun `when first pick exhausted then falls back to unfiltered pool`() =
        runTest(mainRule.testScheduler) {
            val fallback = trackOf("spotify:track:f")
            coEvery { recents.recent(50) } returns listOf("a", "b", "c")
            coEvery { pool.randomTrackExcluding(setOf("a", "b", "c")) } returns null
            coEvery { pool.randomTrackExcluding(emptySet()) } returns fallback

            val result = sut(antiRepeatWindow = 50)

            assertThat(result).isEqualTo(Outcome.Success(fallback))
        }

    @Test fun `when pool is empty then returns error`() =
        runTest(mainRule.testScheduler) {
            coEvery { recents.recent(any()) } returns emptyList()
            coEvery { pool.randomTrackExcluding(any()) } returns null

            val result = sut(antiRepeatWindow = 50)

            assertThat(result).isInstanceOf(Outcome.Error.Unknown::class.java)
        }

    private fun trackOf(uri: String) = Track(
        uri = uri,
        name = "n",
        artistId = "ar",
        artistName = "an",
        durationMs = 200_000,
    )
}

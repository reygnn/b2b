package com.github.reygnn.b2b.playback

import app.cash.turbine.test
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class OrchestratorStatusHolderTest {

    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun `initial snapshot is Idle with zero timestamp`() =
        runTest(mainRule.testScheduler) {
            val sut = OrchestratorStatusHolder()

            sut.snapshot.test {
                val initial = awaitItem()
                assertThat(initial.status).isEqualTo(OrchestratorStatus.Idle)
                assertThat(initial.atEpochMs).isEqualTo(0L)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `record propagates status and timestamp`() =
        runTest(mainRule.testScheduler) {
            val sut = OrchestratorStatusHolder()

            sut.snapshot.test {
                awaitItem() // initial Idle
                sut.record(
                    status = OrchestratorStatus.Enqueued("Track", "Artist", "spotify:track:1"),
                    atEpochMs = 1_000L,
                )
                val next = awaitItem()
                assertThat(next.status).isEqualTo(
                    OrchestratorStatus.Enqueued("Track", "Artist", "spotify:track:1")
                )
                assertThat(next.atEpochMs).isEqualTo(1_000L)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `reset returns to Idle`() = runTest(mainRule.testScheduler) {
        val sut = OrchestratorStatusHolder()
        sut.record(OrchestratorStatus.FreeTier, atEpochMs = 42L)

        sut.snapshot.test {
            assertThat(awaitItem().status).isEqualTo(OrchestratorStatus.FreeTier)
            sut.reset()
            val resetSnapshot = awaitItem()
            assertThat(resetSnapshot.status).isEqualTo(OrchestratorStatus.Idle)
            assertThat(resetSnapshot.atEpochMs).isEqualTo(0L)
            cancelAndConsumeRemainingEvents()
        }
    }
}

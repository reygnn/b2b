package com.github.reygnn.b2b.playback

import app.cash.turbine.test
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class PlayerStateHolderTest {

    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun `initial snapshot is null`() = runTest(mainRule.testScheduler) {
        val sut = PlayerStateHolder()
        sut.snapshot.test {
            assertThat(awaitItem()).isNull()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `record stores state and timestamp`() = runTest(mainRule.testScheduler) {
        val sut = PlayerStateHolder()
        val state = PlayerState(
            trackUri = "spotify:track:1",
            trackName = "T",
            artistName = "A",
            positionMs = 1_234,
            durationMs = 200_000,
            isPaused = false,
        )

        sut.snapshot.test {
            awaitItem() // null
            sut.record(state, capturedAtEpochMs = 7L)
            val next = awaitItem()
            assertThat(next).isNotNull()
            assertThat(next!!.state).isEqualTo(state)
            assertThat(next.capturedAtEpochMs).isEqualTo(7L)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `reset returns to null`() = runTest(mainRule.testScheduler) {
        val sut = PlayerStateHolder()
        sut.record(
            PlayerState("u", "T", "A", 0, 1, false),
            capturedAtEpochMs = 1L,
        )

        sut.snapshot.test {
            assertThat(awaitItem()).isNotNull()
            sut.reset()
            assertThat(awaitItem()).isNull()
            cancelAndConsumeRemainingEvents()
        }
    }
}

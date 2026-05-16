package com.github.reygnn.b2b.playback

import app.cash.turbine.test
import com.github.reygnn.b2b.domain.model.Track
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class PreviewTrackHolderTest {

    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun `initial track is null`() = runTest(mainRule.testScheduler) {
        val sut = PreviewTrackHolder()
        sut.track.test {
            assertThat(awaitItem()).isNull()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `set propagates the track`() = runTest(mainRule.testScheduler) {
        val sut = PreviewTrackHolder()
        val track = Track(
            uri = "spotify:track:1",
            name = "Name",
            artistId = "ar",
            artistName = "Artist",
            durationMs = 100,
        )
        sut.track.test {
            awaitItem() // initial null
            sut.set(track)
            assertThat(awaitItem()).isEqualTo(track)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `reset returns to null`() = runTest(mainRule.testScheduler) {
        val sut = PreviewTrackHolder()
        sut.set(Track("u", "n", "ar", "a", 0))

        sut.track.test {
            assertThat(awaitItem()).isNotNull()
            sut.reset()
            assertThat(awaitItem()).isNull()
            cancelAndConsumeRemainingEvents()
        }
    }
}

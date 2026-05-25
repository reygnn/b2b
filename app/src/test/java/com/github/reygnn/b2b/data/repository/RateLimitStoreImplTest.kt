package com.github.reygnn.b2b.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers two things the previous test surface didn't:
 *
 *  - The auto-enable wiring from [RateLimitStoreImpl.record] into
 *    [KillSwitchStore], introduced with the May 2026 incident response
 *    so that any 429 from any surface puts the whole app into "Spotify
 *    silent" mode until the user opts back in.
 *  - The persistence round-trip of `(retryAfterSeconds, recordedAtEpochMs)`
 *    through SharedPreferences — important because the 16 h+ Retry-After
 *    values Spotify has shipped must survive the process death the user
 *    causes by closing the app.
 */
@RunWith(RobolectricTestRunner::class)
class RateLimitStoreImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `record enables the kill switch`() {
        val killSwitch = mockk<KillSwitchStore>(relaxed = true)
        val sut = RateLimitStoreImpl(context, killSwitch)

        sut.record(retryAfterSeconds = 60, atEpochMs = 123_456L)

        verify(exactly = 1) { killSwitch.enable() }
    }

    @Test fun `record persists the announced wait verbatim`() {
        val killSwitch = mockk<KillSwitchStore>(relaxed = true)
        val sut = RateLimitStoreImpl(context, killSwitch)

        sut.record(retryAfterSeconds = 22_316, atEpochMs = 9_876_543_210L)

        // New instance == process restart for SharedPreferences semantics.
        val reborn = RateLimitStoreImpl(context, killSwitch)
        val state = reborn.state().value
        assertThat(state).isNotNull()
        assertThat(state!!.retryAfterSeconds).isEqualTo(22_316)
        assertThat(state.recordedAtEpochMs).isEqualTo(9_876_543_210L)
    }

    @Test fun `clear removes the state and does not touch the kill switch`() {
        // Pins the asymmetric coupling: record() ENABLES the kill switch,
        // but clear() does NOT disable it. Whether the kill switch stays
        // on after the penalty clears is the user's decision, not the
        // store's.
        val killSwitch = mockk<KillSwitchStore>(relaxed = true)
        val sut = RateLimitStoreImpl(context, killSwitch)
        sut.record(retryAfterSeconds = 60, atEpochMs = 0L)

        sut.clear()

        assertThat(sut.state().value).isNull()
        verify(exactly = 0) { killSwitch.disable() }
    }
}

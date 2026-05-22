package com.github.reygnn.b2b.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM unit tests for [RateLimitState.remainingSecondsAt]. The
 * SharedPreferences-backed [RateLimitStoreImpl] needs Robolectric and is
 * covered separately; the arithmetic on the data class itself does not.
 */
class RateLimitStateTest {

    @Test fun `remaining matches retryAfter when no time has passed`() {
        val s = RateLimitState(retryAfterSeconds = 30, recordedAtEpochMs = 1_000L)
        assertThat(s.remainingSecondsAt(1_000L)).isEqualTo(30)
    }

    @Test fun `remaining shrinks as the clock advances`() {
        val s = RateLimitState(retryAfterSeconds = 60, recordedAtEpochMs = 0L)
        assertThat(s.remainingSecondsAt(10_000L)).isEqualTo(50)
        assertThat(s.remainingSecondsAt(59_000L)).isEqualTo(1)
    }

    @Test fun `remaining clamps to zero once the wait has elapsed`() {
        // The UI uses `remaining > 0` as the render condition. Past the
        // wait, the value must be 0 (not negative) so the conditional
        // short-circuits cleanly and the row disappears.
        val s = RateLimitState(retryAfterSeconds = 5, recordedAtEpochMs = 0L)
        assertThat(s.remainingSecondsAt(5_000L)).isEqualTo(0)
        assertThat(s.remainingSecondsAt(10_000L)).isEqualTo(0)
        assertThat(s.remainingSecondsAt(Long.MAX_VALUE)).isEqualTo(0)
    }

    @Test fun `clock running backwards relative to recordedAt is treated as no time passed`() {
        // Defensive: device clock can jump backwards (NTP correction, user
        // changing time, app restored from backup with a future-dated
        // record). Negative elapsed time would inflate remaining beyond
        // retryAfterSeconds, which would never tick down — coerce to 0.
        val s = RateLimitState(retryAfterSeconds = 60, recordedAtEpochMs = 10_000L)
        assertThat(s.remainingSecondsAt(0L)).isEqualTo(60)
    }
}

package com.github.reygnn.b2b.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.b2b.work.PoolSyncScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KillSwitchStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `initial state is off`() {
        val sut = KillSwitchStoreImpl(context, mockk(relaxed = true))
        assertThat(sut.state().value).isFalse()
    }

    @Test fun `enable flips the state to true`() {
        val sut = KillSwitchStoreImpl(context, mockk(relaxed = true))
        sut.enable()
        assertThat(sut.state().value).isTrue()
    }

    @Test fun `disable flips the state back to false`() {
        val sut = KillSwitchStoreImpl(context, mockk(relaxed = true))
        sut.enable()
        sut.disable()
        assertThat(sut.state().value).isFalse()
    }

    @Test fun `enabled state survives a fresh instance`() {
        // Pins the persistence contract: a process restart (modeled here as
        // a new instance reading the same SharedPreferences) must preserve
        // the toggle. Without persistence the user would have to re-arm the
        // switch after every Doze kill, defeating its defensive purpose.
        KillSwitchStoreImpl(context, mockk(relaxed = true)).enable()

        val reborn = KillSwitchStoreImpl(context, mockk(relaxed = true))
        assertThat(reborn.state().value).isTrue()
    }

    @Test fun `repeated enable does not toggle the state needlessly`() {
        // Pins the "no-op when already enabled" branch — a subscriber
        // listening to state() should not observe a phantom emit when
        // enable() is called twice in a row.
        val sut = KillSwitchStoreImpl(context, mockk(relaxed = true))
        sut.enable()
        val firstSnapshot = sut.state().value

        sut.enable()
        assertThat(sut.state().value).isEqualTo(firstSnapshot)
        assertThat(sut.state().value).isTrue()
    }

    @Test fun `enable cancels pending periodic work`() {
        // Pins the side effect: an active kill switch should not leave a
        // wasted "fire every 15 min just to skip" tick alive in WorkManager.
        val scheduler = mockk<PoolSyncScheduler>(relaxed = true)
        val sut = KillSwitchStoreImpl(context, scheduler)
        sut.enable()
        verify(exactly = 1) { scheduler.cancel() }
    }

    @Test fun `disable re-arms the periodic schedule`() {
        val scheduler = mockk<PoolSyncScheduler>(relaxed = true)
        val sut = KillSwitchStoreImpl(context, scheduler)
        sut.enable()
        sut.disable()
        verify(exactly = 1) { scheduler.schedule() }
    }

    @Test fun `repeated enable does not re-cancel`() {
        // Same idempotence contract as `repeated enable does not toggle the
        // state needlessly`, extended to the scheduler side effect: the
        // second enable() must not nudge WorkManager again.
        val scheduler = mockk<PoolSyncScheduler>(relaxed = true)
        val sut = KillSwitchStoreImpl(context, scheduler)
        sut.enable()
        sut.enable()
        verify(exactly = 1) { scheduler.cancel() }
    }
}

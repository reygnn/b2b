package com.github.reygnn.b2b.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KillSwitchStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `initial state is off`() {
        val sut = KillSwitchStoreImpl(context)
        assertThat(sut.state().value).isFalse()
    }

    @Test fun `enable flips the state to true`() {
        val sut = KillSwitchStoreImpl(context)
        sut.enable()
        assertThat(sut.state().value).isTrue()
    }

    @Test fun `disable flips the state back to false`() {
        val sut = KillSwitchStoreImpl(context)
        sut.enable()
        sut.disable()
        assertThat(sut.state().value).isFalse()
    }

    @Test fun `enabled state survives a fresh instance`() {
        // Pins the persistence contract: a process restart (modeled here as
        // a new instance reading the same SharedPreferences) must preserve
        // the toggle. Without persistence the user would have to re-arm the
        // switch after every Doze kill, defeating its defensive purpose.
        KillSwitchStoreImpl(context).enable()

        val reborn = KillSwitchStoreImpl(context)
        assertThat(reborn.state().value).isTrue()
    }

    @Test fun `repeated enable does not toggle the state needlessly`() {
        // Pins the "no-op when already enabled" branch — a subscriber
        // listening to state() should not observe a phantom emit when
        // enable() is called twice in a row.
        val sut = KillSwitchStoreImpl(context)
        sut.enable()
        val firstSnapshot = sut.state().value

        sut.enable()
        assertThat(sut.state().value).isEqualTo(firstSnapshot)
        assertThat(sut.state().value).isTrue()
    }
}

package com.github.reygnn.b2b.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AntiRepeatRingBufferTest {

    @Test fun `returns false when track never added`() {
        val sut = AntiRepeatRingBuffer(capacity = 3)
        assertThat(sut.contains("spotify:track:a")).isFalse()
    }

    @Test fun `contains returns true after add`() {
        val sut = AntiRepeatRingBuffer(capacity = 3)
        sut.add("spotify:track:a")
        assertThat(sut.contains("spotify:track:a")).isTrue()
    }

    @Test fun `when capacity exceeded then oldest is evicted`() {
        val sut = AntiRepeatRingBuffer(capacity = 2)
        sut.add("a"); sut.add("b"); sut.add("c")
        assertThat(sut.contains("a")).isFalse()
        assertThat(sut.contains("b")).isTrue()
        assertThat(sut.contains("c")).isTrue()
        assertThat(sut.size).isEqualTo(2)
    }

    @Test fun `re-adding existing uri moves it to tail`() {
        val sut = AntiRepeatRingBuffer(capacity = 2)
        sut.add("a"); sut.add("b"); sut.add("a"); sut.add("c")
        // Order is now b -> a -> c (cap 2 evicts b after c).
        assertThat(sut.contains("a")).isTrue()
        assertThat(sut.contains("b")).isFalse()
        assertThat(sut.contains("c")).isTrue()
    }

    @Test fun `snapshot returns full lookup set`() {
        val sut = AntiRepeatRingBuffer(capacity = 5)
        listOf("a", "b", "c").forEach(sut::add)
        assertThat(sut.snapshot()).containsExactly("a", "b", "c")
    }

    @Test fun `clear empties buffer`() {
        val sut = AntiRepeatRingBuffer(capacity = 3)
        sut.add("a"); sut.add("b")
        sut.clear()
        assertThat(sut.size).isEqualTo(0)
        assertThat(sut.contains("a")).isFalse()
    }
}

package com.github.reygnn.b2b.diagnostics

import app.cash.turbine.test
import com.github.reygnn.b2b.support.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LogBufferTest {

    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun `initial entries are empty`() = runTest(mainRule.testScheduler) {
        val sut = LogBuffer()
        sut.entries.test {
            assertThat(awaitItem()).isEmpty()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `log appends entries in order with timestamps`() =
        runTest(mainRule.testScheduler) {
            val sut = LogBuffer()
            sut.entries.test {
                awaitItem() // initial empty
                sut.log("first")
                val one = awaitItem()
                assertThat(one).hasSize(1)
                assertThat(one[0].message).isEqualTo("first")
                assertThat(one[0].epochMs).isGreaterThan(0)

                sut.log("second")
                val two = awaitItem()
                assertThat(two.map { it.message }).containsExactly("first", "second").inOrder()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test fun `ring buffer evicts oldest beyond 500 entries`() =
        runTest(mainRule.testScheduler) {
            val sut = LogBuffer()
            repeat(550) { sut.log("entry-$it") }
            val snapshot = sut.entries.value
            assertThat(snapshot).hasSize(500)
            // Oldest 50 should be gone; the surviving block starts at entry-50.
            assertThat(snapshot.first().message).isEqualTo("entry-50")
            assertThat(snapshot.last().message).isEqualTo("entry-549")
        }

    @Test fun `clear empties the buffer`() = runTest(mainRule.testScheduler) {
        val sut = LogBuffer()
        sut.log("noise")
        sut.entries.test {
            assertThat(awaitItem()).hasSize(1)
            sut.clear()
            assertThat(awaitItem()).isEmpty()
            cancelAndConsumeRemainingEvents()
        }
    }
}

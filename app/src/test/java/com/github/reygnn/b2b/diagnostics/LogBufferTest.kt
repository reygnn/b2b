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

    // ---- clear + undo --------------------------------------------------

    @Test fun `clear arms an undoable snapshot`() = runTest(mainRule.testScheduler) {
        val sut = LogBuffer()
        sut.log("a")
        sut.log("b")

        assertThat(sut.hasUndoableClear.value).isFalse()
        sut.clear()
        assertThat(sut.hasUndoableClear.value).isTrue()
    }

    @Test fun `undoClear restores the cleared entries verbatim`() =
        runTest(mainRule.testScheduler) {
            val sut = LogBuffer()
            sut.log("a")
            sut.log("b")
            sut.clear()
            assertThat(sut.entries.value).isEmpty()

            sut.undoClear()

            assertThat(sut.entries.value.map { it.message })
                .containsExactly("a", "b")
                .inOrder()
            assertThat(sut.hasUndoableClear.value).isFalse()
        }

    @Test fun `undoClear merges restored entries with any logs arriving in between`() =
        runTest(mainRule.testScheduler) {
            // Realistic timing: user clears, the worker fires a tick and
            // appends "sync: …", user taps Undo. The Undo must restore the
            // pre-clear entries without dropping the post-clear "sync:" line.
            val sut = LogBuffer()
            sut.log("a")
            sut.log("b")
            sut.clear()
            sut.log("sync: post-clear")

            sut.undoClear()

            assertThat(sut.entries.value.map { it.message })
                .containsExactly("a", "b", "sync: post-clear")
                .inOrder()
        }

    @Test fun `commitClear finalises the snapshot and disables undo`() =
        runTest(mainRule.testScheduler) {
            val sut = LogBuffer()
            sut.log("a")
            sut.clear()
            sut.commitClear()

            sut.undoClear()

            // Undo after commit is a no-op; buffer stays empty.
            assertThat(sut.entries.value).isEmpty()
            assertThat(sut.hasUndoableClear.value).isFalse()
        }

    @Test fun `second clear commits the previous snapshot, only the latest is undoable`() =
        runTest(mainRule.testScheduler) {
            // First batch [a, b] is cleared; user lingers, worker logs "c";
            // user clears again before tapping Undo. Per the
            // "one-undo-at-a-time" contract, the first snapshot is now
            // gone; only "c" comes back on Undo.
            val sut = LogBuffer()
            sut.log("a")
            sut.log("b")
            sut.clear()
            sut.log("c")
            sut.clear()

            sut.undoClear()

            assertThat(sut.entries.value.map { it.message }).containsExactly("c")
        }

    @Test fun `clear with no entries does not arm undo`() = runTest(mainRule.testScheduler) {
        // An empty buffer being cleared is a UX no-op; the snackbar should
        // not show ("there was nothing to undo"). [hasUndoableClear]
        // stays false so the Home screen does not flash a stale snackbar.
        val sut = LogBuffer()

        sut.clear()

        assertThat(sut.hasUndoableClear.value).isFalse()
    }
}

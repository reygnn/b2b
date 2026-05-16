package com.github.reygnn.b2b.support

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Single-dispatcher coroutine rule for unit tests. See TESTING_CONVENTIONS.kt.
 *
 * Default is [UnconfinedTestDispatcher] (eager execution, simplest semantics).
 * For tests that need explicit `advanceUntilIdle()` / `advanceTimeBy()` control,
 * pass a [kotlinx.coroutines.test.StandardTestDispatcher] instance.
 *
 * Expose [testDispatcher] to the SUT (constructor-inject it) and use
 * [testScheduler] when calling `runTest(testScheduler) { ... }` so the test
 * body shares the same virtual clock as the SUT.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    val testScheduler: TestCoroutineScheduler get() = testDispatcher.scheduler

    /** Use this where production code expects a `CoroutineDispatcher`. */
    val dispatcher: CoroutineDispatcher get() = testDispatcher

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

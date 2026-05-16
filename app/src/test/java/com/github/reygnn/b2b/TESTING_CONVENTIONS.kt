package com.github.reygnn.b2b

/**
 * # TESTING CONVENTIONS — read this before adding any test.
 *
 * This file is referenced from the project's userPreferences and is the single
 * source of truth for how unit tests are written in this codebase. The rules
 * below are enforced by review.
 *
 * ## 1. Dispatchers: ONE per test class, via MainDispatcherRule.
 *
 * Every test class that touches coroutines installs the shared
 * [com.github.reygnn.b2b.support.MainDispatcherRule] as a `@get:Rule`.
 *
 * The rule swaps `Dispatchers.Main` for a single `UnconfinedTestDispatcher`
 * (default) or `StandardTestDispatcher` (opt-in per test class) and **exposes
 * that same dispatcher** to the test body. The production code under test runs
 * on that dispatcher. The test body uses `runTest(rule.testScheduler) { ... }`.
 *
 * ### Do NOT:
 * - Instantiate a separate `TestScope`, `StandardTestDispatcher`, or
 *   `UnconfinedTestDispatcher` inside a test method.
 * - Pass a different dispatcher to the SUT than the one the rule uses.
 * - Call `Dispatchers.setMain(...)` manually — the rule does it.
 * - Inject a `CoroutineScope` into the SUT just for testing. Inject a
 *   `CoroutineDispatcher` (qualified via `@IoDispatcher` / `@DefaultDispatcher`
 *   / `@MainDispatcher`) and let production wire its own scopes.
 *
 * ### Do:
 * ```
 * class FooTest {
 *     @get:Rule val mainRule = MainDispatcherRule()
 *
 *     private lateinit var sut: Foo
 *
 *     @Before fun setUp() {
 *         sut = Foo(ioDispatcher = mainRule.testDispatcher)
 *     }
 *
 *     @Test fun `when bar then baz`() = runTest(mainRule.testScheduler) {
 *         // ...
 *     }
 * }
 * ```
 *
 * Why: a single dispatcher means `advanceUntilIdle()`, `advanceTimeBy(...)`,
 * and `runCurrent()` deterministically drain *all* coroutines under test.
 * Multiple dispatchers cause flakes that look like race conditions but are
 * just unscheduled work in a sibling scheduler.
 *
 * ## 2. Mocking: MockK only.
 *
 * - No Mockito, no PowerMock.
 * - Prefer `mockk<Foo>(relaxed = true)` only when verifying interactions; use
 *   strict mocks for return-value tests so missing stubs fail loudly.
 * - For `suspend` functions: `coEvery { ... } returns ...` and
 *   `coVerify { ... }`.
 * - For final Kotlin classes in production code: rely on `mockk-agent`
 *   (already on the test classpath). Don't open production classes for tests.
 *
 * ## 3. Flow assertions: Turbine.
 *
 * ```
 * sut.state.test {
 *     assertThat(awaitItem()).isEqualTo(State.Loading)
 *     // ...
 *     cancelAndConsumeRemainingEvents()
 * }
 * ```
 *
 * Do not collect into a `mutableListOf` and assert at the end — Turbine
 * surfaces ordering bugs that list-collection hides.
 *
 * ## 4. Test naming.
 *
 * Backticks, `when X then Y` for behavioural tests, `returns Y when X` for
 * pure functions. The class under test is implicit in the file name.
 *
 * ## 5. What every layer's tests must cover.
 *
 * - Repository: maps DTO → domain, surfaces auth failures as Outcome.Error.
 * - UseCase: pure logic, no Android imports, no Room, no Retrofit.
 * - ViewModel: state transitions via Turbine, side-effects via shared flows.
 * - Worker: input/output, retry policy, paging termination.
 * - Service / orchestrator: state-machine transitions on synthetic
 *   PlayerState events; no real App Remote.
 *
 * ## 6. What we do not test here.
 *
 * - The Spotify API itself (we mock it).
 * - Compose rendering (covered by separate `androidTest` instrumented tests,
 *   not in this source set).
 * - The real Spotify App Remote connection (instrumented test on device).
 */
internal object TestingConventions

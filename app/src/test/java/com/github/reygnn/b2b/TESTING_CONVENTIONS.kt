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
 * ## 2. WhileSubscribed: use `runCurrent()`, NOT `advanceUntilIdle()`.
 *
 * `WhitelistViewModel.whitelisted` (and any future StateFlow built with
 * `stateIn(scope, SharingStarted.WhileSubscribed(N), initial)`) installs a
 * subscription-timeout coroutine. `advanceUntilIdle()` runs the virtual clock
 * until ALL scheduled work is done — including that timeout. By the time the
 * test inspects the StateFlow, the upstream has already been dropped and the
 * value reverted to `initial`.
 *
 * Use `mainRule.testScheduler.runCurrent()` to drive only the work currently
 * scheduled (no clock advance). For Turbine on a `WhileSubscribed` flow,
 * subscribing via `.test { }` keeps the upstream alive for the test's
 * duration — that path is safe.
 *
 * ## 3. MutableSharedFlow in a ViewModel constructor: DROP_OLDEST or Turbine.
 *
 * Default `MutableSharedFlow()` has `replay = 0`, `extraBufferCapacity = 0`,
 * `onBufferOverflow = SUSPEND`. The first `emit` from a producer with no
 * active subscriber suspends forever — the test hangs until the runner
 * times it out, with no useful error.
 *
 * Two safe patterns:
 * - `MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
 *   for event flows that should not block production code.
 * - Subscribe via Turbine **before** the emit happens in the test:
 *   `sut.events.test { sut.doThing(); assertThat(awaitItem()) ... }`.
 *
 * ## 4. Mocking: MockK only.
 *
 * - No Mockito, no PowerMock.
 * - **`relaxed = true`** stubs every member to a default; use it only when
 *   verifying interactions (`coVerify { ... }`), not for return-value tests
 *   — silent defaults hide missing stubs.
 * - **`relaxUnitFun = true`** is the right tool for repositories with many
 *   `suspend fun ...: Unit` members (e.g. `mockk<PoolRepository>(relaxUnitFun = true)`
 *   in `PoolSyncWorkerTest`). It relaxes only Unit-returning members and
 *   keeps the rest strict.
 * - For `suspend` functions: `coEvery { ... } returns ...` and
 *   `coVerify { ... }`.
 * - **Stateful stubbing**: use `coEvery { foo() } answers { … }` (note the
 *   block form). There is no `coAnswers` keyword. The `answers` block runs
 *   on each call and has access to `args`, `nArgs`, `invocation`.
 * - For final Kotlin classes in production code: rely on `mockk-agent`
 *   (already on the test classpath). Don't open production classes for tests.
 *
 * ## 5. Flow assertions: Turbine.
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
 * For `MutableSharedFlow<Unit>` event signals, assert with `awaitItem()`
 * returning `Unit` — emit with `emit(Unit)`, not `emit(any())`; MockK's
 * `any()` is not a value, it's a matcher and panics outside a stubbing block.
 *
 * ## 6. Test naming.
 *
 * Backticks, `when X then Y` for behavioural tests, `returns Y when X` for
 * pure functions. The class under test is implicit in the file name.
 *
 * ## 7. What every layer's tests must cover.
 *
 * - Repository: maps DTO → domain, surfaces auth failures as Outcome.Error.
 * - UseCase: pure logic, no Android imports, no Room, no Retrofit.
 * - ViewModel: state transitions via Turbine, side-effects via shared flows.
 * - Worker: input/output, retry policy, paging termination.
 * - Service / orchestrator: state-machine transitions on synthetic
 *   PlayerState events; no real App Remote.
 *
 * ## 8. What we do not test here.
 *
 * - The Spotify API itself (we mock it).
 * - Compose rendering (covered by separate `androidTest` instrumented tests,
 *   not in this source set).
 * - The real Spotify App Remote connection (instrumented test on device).
 */
internal object TestingConventions

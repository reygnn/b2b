# ADR 0001 — Single dispatcher per test class via MainDispatcherRule

Status: Accepted
Date: 2026-05-16

## Context

Coroutine tests have two failure modes that are easy to introduce and hard to
debug:

1. **Multiple schedulers.** A test instantiates its own `TestScope` or
   `StandardTestDispatcher` and passes a different dispatcher to the SUT.
   `advanceUntilIdle()` then drains only one of the two schedulers, leaving
   coroutines pending in the other. Tests pass locally and fail on CI, or
   vice versa.
2. **Real Main dispatcher in JVM tests.** `Dispatchers.Main` throws on JVM
   unless replaced. `Dispatchers.setMain(...)` must be called and reset.

## Decision

Every unit test that touches coroutines uses
`com.github.reygnn.b2b.support.MainDispatcherRule` as a `@get:Rule`.

The rule:
- Creates exactly one `TestDispatcher` (default: `UnconfinedTestDispatcher`).
- Installs it as `Dispatchers.Main`.
- Exposes it as `mainRule.testDispatcher` and its scheduler as
  `mainRule.testScheduler`.

Production code receives dispatchers via Hilt-qualified injection
(`@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher`). Tests inject
`mainRule.testDispatcher` into all three qualifiers if needed.

Tests use `runTest(mainRule.testScheduler) { … }` so the test body and the
SUT share the same virtual clock.

## Consequences

- One mental model: there is one clock in the test.
- `advanceUntilIdle()`, `advanceTimeBy(...)`, `runCurrent()` work as expected.
- Tests that need explicit time control opt in by constructing the rule with
  `MainDispatcherRule(StandardTestDispatcher())`.
- Reviewers reject PRs that instantiate `TestScope` / `StandardTestDispatcher`
  inside test methods. The compiler does not enforce this — Code Review does.

# CLAUDE.md

Project conventions for **b2b** — a small Spotify-companion Android app that
keeps Spotify's playback queue filled with random tracks from a self-curated
artist whitelist. Built so the user can listen via Android Auto without
falling into Spotify's recommendation algorithm. Claude Code reads this file
automatically at session start. Keep it short and actionable.

> **Personal app.** Built for the maintainer's own device, not Play-Store
> distribution. Aggressive choices (minSdk = compileSdk = targetSdk = 36,
> Spotify Premium required at runtime, release-signing not configured yet)
> are deliberate — do not flag them as compatibility or "best-practice"
> issues.

For test details, see `app/src/test/java/com/github/reygnn/b2b/TESTING_CONVENTIONS.kt`.
For deprecated-endpoint policy, see `docs/adr/0002-spotify-api-deprecation-handling.md`.
For the dispatcher-rule rationale, see `docs/adr/0001-single-dispatcher-test-convention.md`.
b2b is a sibling to **chiaroscuro** (M3 / Android-16-only template) and
**Lobber** (toolchain + gradle skeleton); look there for patterns rather
than re-inventing.

---

## Stack

- Kotlin 2.2.21, Jetpack Compose + Material 3, Compose BOM 2026.04.01
- Min/target/compile SDK 36 — **Android 16 only**, no compatibility shims
- JDK 21 at build/test time (Robolectric 4.16.1 needs it for SDK 36)
- Hilt 2.57.2 for DI; Room 2.7.2 for local persistence; Retrofit 2.11 +
  OkHttp 4.12 + kotlinx-serialization 1.9 for the Spotify Web API
- WorkManager 2.10.4 for periodic pool sync; foreground `Service` for the
  in-session orchestrator
- Tests: JUnit 4, MockK 1.14.9, Turbine 1.2.1, kotlinx-coroutines-test,
  Robolectric 4.16.1, OkHttp MockWebServer

## Build & test

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

JDK 21 is auto-provisioned via the foojay toolchain resolver declared in
`settings.gradle.kts` — no `JAVA_HOME` prefix needed. AGP-generated Java
compile tasks are explicitly pinned to JDK 21 in `app/build.gradle.kts`;
without that pin, `hiltJavaCompileDebug` falls back to the system JDK and
fails with `invalid source release: 21`.

`SPOTIFY_CLIENT_ID` is read from `~/.gradle/gradle.properties` or the
project-local `gradle.properties` and inlined as a `BuildConfig` field.
Setup is in the README.

---

## Architecture

```
app/src/main/java/com/github/reygnn/b2b/
  B2BApp.kt                  Hilt entry point; Configuration.Provider for WM.
  MainActivity.kt            ComponentActivity, Compose host, catches the
                             b2b://callback OAuth redirect.
  di/AppModules.kt           Hilt modules: Dispatchers, Database, Network, Binds.
  domain/                    Pure-Kotlin layer: models, repository interfaces,
                             use cases. No Android, no Retrofit, no Room.
  data/
    remote/                  Retrofit SpotifyApi, DTOs, AuthInterceptor.
    local/                   Room AppDatabase, entities, DAOs.
    auth/                    PkceAuthManager (OAuth flow), TokenStore (encrypted
                             prefs).
    repository/              Concrete impls — DTO→domain mapping, IO dispatch.
  work/PoolSyncWorker.kt     Periodic 24h sync, whitelist→albums→tracks→pool.
  service/                   PlaybackOrchestratorService (foreground, mediaPlayback).
  playback/                  AntiRepeatRingBuffer (in-memory recency helper).
  ui/                        Compose screens + Hilt-injected ViewModels.
```

Cross-layer error transport: **`domain/model/Outcome.kt`**. Every fallible
operation returns `Outcome.Success<T>` or one of the enumerated
`Outcome.Error` cases (`Unauthenticated`, `NotPremium`, `NoActiveDevice`,
`Network`, `RateLimited(retryAfterSeconds)`, `Unknown`). HTTP status codes
are mapped centrally in `data/repository/Repositories.kt::toOutcome`. We
do not throw across layer boundaries.

---

## Hard rules

1. **Spotify Web API surface is closed.** `SpotifyApi.kt` is the single
   point of contact. The endpoints listed there (`/search`,
   `/artists/{id}/albums`, `/albums/{id}/tracks`, `/me/player/devices`,
   `/me/player/queue`, `/me`) are the entire allowed set. The endpoints in
   ADR-0002 (`/recommendations`, `/artists/{id}/related-artists`,
   `/audio-features`, `/audio-analysis`, `/browse/featured-playlists`,
   `/browse/categories/{id}/playlists`) were deprecated for new app
   registrations on 2024-11-27 and **MUST NOT** be added — Spotify will
   refuse them. New endpoints need an ADR amendment.

2. **`PkceAuthManager` token HTTP calls use a dedicated OkHttp client
   without `AuthInterceptor`.** The token endpoint at
   `https://accounts.spotify.com/api/token` does not accept Bearer auth.
   Reusing the Hilt-provided `OkHttpClient` (which has `AuthInterceptor`)
   would make a 401 from the token endpoint trigger `tokenStore.refresh()`
   → `PkceAuthManager.refresh()` → recursion. POST is
   `application/x-www-form-urlencoded`; param sets are
   `grant_type=authorization_code, code, redirect_uri, client_id,
   code_verifier` for the initial exchange and
   `grant_type=refresh_token, refresh_token, client_id` for the refresh.

3. **The orchestrator's per-track latch is non-negotiable.** Spotify App
   Remote emits `PlayerState` events at several Hz; the trigger condition
   `duration - position < TRIGGER_MS` is true for the whole last 15 s of
   every track. Without the `lastEnqueuedForTrackId` latch in
   `playback/PlaybackOrchestrator.kt` — which resets only when
   `state.trackUri` differs from the latched URI — enqueues fire dozens
   of times per track and the queue fills with hundreds of tracks per
   minute. Keep that latch in the orchestrator (pure logic, JVM-testable),
   not in any repository or in the foreground service shell.

   The orchestrator consumes a `PlayerStateSource` (interface,
   `playback/PlayerStateSource.kt`); the production binding is
   `AppRemotePlayerStateSource`, which wraps the Spotify App Remote SDK
   in a `callbackFlow`. The SDK is shipped as the local AAR
   `app/libs/spotify-app-remote-release-*.aar`. Do not route any other
   App Remote calls outside this class — every SDK touch lives behind
   the `PlayerStateSource` interface so the orchestrator stays
   Android-free and JVM-testable.

4. **`PoolSyncWorker` rate-limit handling: in-run `delay`, not
   `Result.retry()`.** When `fetchAllTrackUrisForArtist(...)` returns
   `Outcome.Error.RateLimited(retryAfterSeconds)`, prefer
   `delay(retryAfterSeconds * 1000L)` and continue in the same run.
   Spotify's rate-limit window is ~30 s; WorkManager's exponential
   backoff starts at 30 s and grows. `Result.retry()` is at best equal,
   usually slower, and drops the artist-by-artist progress accumulated
   so far. Reserve `Result.retry()` for `Outcome.Error.Network`.

5. **Dispatchers via Hilt qualifiers.** Production code injects
   `CoroutineDispatcher` parameters annotated with `@IoDispatcher`,
   `@DefaultDispatcher`, or `@MainDispatcher` — never references
   `Dispatchers.IO` / `.Default` / `.Main` directly. Two pitfalls when
   editing `di/AppModules.kt`:
   - Provider function names must not be Java keywords. `fun default()`
     compiled fine in Kotlin but blew up KSP code-gen with
     `not a valid name: default`. Use `fun defaultDispatcher()` etc.
   - On constructor val parameters the qualifier needs `@param:` —
     `@param:IoDispatcher private val io: CoroutineDispatcher`. Without
     the use-site target, Kotlin 2.2 (KT-73255) warns about a future
     change in default-target behaviour.

6. **Layer purity.**
   - `domain/` has no `import android.*`, no Retrofit, no Room, no Hilt
     beyond `@Inject`. Models are plain data classes.
   - `data/repository/` is the only place HTTP errors become `Outcome`
     values. Use `toOutcome { … }` from `data/repository/Repositories.kt`;
     do not re-map status codes elsewhere.
   - `ui/` consumes `Outcome` results from ViewModels; ViewModels collect
     from repositories. ViewModels never call Retrofit/Room directly.

7. **English in new comments and KDoc.** Existing code is English; keep
   it that way. UI strings live in `res/values/strings.xml`. No
   `values-de/` exists yet; add it (and update both) when localised
   strings appear.

---

## Test conventions (short)

The full reference is `app/src/test/java/com/github/reygnn/b2b/TESTING_CONVENTIONS.kt`,
backed by ADR-0001. The non-negotiable point:

```kotlin
@get:Rule val mainRule = MainDispatcherRule()

@Test fun whatever() = runTest(mainRule.testScheduler) {
    val sut = Foo(ioDispatcher = mainRule.testDispatcher)
    // ...
}
```

One dispatcher per test class. Never instantiate `TestScope` or a separate
`StandardTestDispatcher` inside a test method — `advanceUntilIdle()` then
drains only one of the two schedulers and tests become flaky. MockK for
all mocking (`mockk-agent` is on the classpath for final Kotlin classes).
Turbine for Flow assertions. `OkHttp MockWebServer` is on the test
classpath for `AuthInterceptor`-style HTTP tests.

**WhileSubscribed gotcha.** `WhitelistViewModel.whitelisted` uses
`SharingStarted.WhileSubscribed(5_000)`. In tests against that StateFlow,
`advanceUntilIdle()` runs the virtual clock past the 5 s subscription
timeout, drops the upstream, and reverts the value to `initial` — the
test then asserts against a stale value with no useful error. Use
`mainRule.testScheduler.runCurrent()` to drive only currently-scheduled
work, or subscribe via `Turbine.test { }` which keeps the upstream alive.
Same rule for any future `stateIn(scope, WhileSubscribed(...), ...)`.

---

## Versioning

`versionName` / `versionCode` live in `app/build.gradle.kts`. b2b is not
under semver — the README's "Status: Skelett" is the canonical state
description. When the two PKCE TODOs and the App Remote wiring land, bump
to `0.2.0`.

---

## Git workflow

Larger changes — bigger bugfixes, refactorings, new features, anything
that touches multiple files or could plausibly be reverted as a unit — go
on a dedicated branch, never directly on `main`. Trivial edits (typo fix,
single-line tweak, doc nit) can stay on the current branch.

When in doubt, **stop and ask before starting**. Confirming is cheap;
realising mid-implementation that the work is on the wrong branch is not.

Branch prefixes: `feature/`, `fix/`, `refactor/`, `chore/`, `test/`.

After a fast-forward merge into `main`: switch back to `main` and ask
before deleting the merged branch (locally and on the remote). Even after
a merge, an open PR or historical reference may still hang on the branch.

Commit messages: short subject in German is fine, body in English when
technical detail is involved. Trailer:
`Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.

---

## What this file is NOT

- Not a description of the project — the package names and filenames are
  the description; the README has the setup procedure.
- Not the full testing reference — see `TESTING_CONVENTIONS.kt`.
- Not the place to re-state ADR content — link to the ADR.
- Not the place for transient refactor notes — those belong in commit
  messages on short-lived feature branches.

Update this file when an architectural rule changes or a hard-won lesson
deserves to be future-proofed. Do not bloat it with details that are
obvious from reading the code.

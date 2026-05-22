# B2B — Spotify Whitelist Companion

Android companion app that nudges Spotify into playing only tracks from a
self-curated artist whitelist. When you listen to Spotify over Android
Auto, this app runs in the background on your phone and feeds Spotify's
queue with random tracks from your chosen artists — bypassing Spotify's
own recommendation algorithm.

## Status

**Real-device-validated, version 0.5.8 (versionCode 38).** End-to-end push
confirmed: Spotify plays a whitelisted track → 15 s before the track ends,
b2b enqueues the next pool track into Spotify's queue → clean handover.
PKCE OAuth against `accounts.spotify.com`, periodic + manual
`PoolSyncWorker` with multi-layered rate-limit protection (skip while a
Spotify penalty is still running, per-artist freshness skip, 30 s
inter-artist cooldown, per-artist timeout), Compose UI (Login → Whitelist
→ Artists → Settings) with status card, track-position countdown, skip
button for the preview pick, explicit artist search, and a 500-line log
panel. App Remote SDK integration via `AppRemotePlayerStateSource`
(Main-Looper-pinned). Material You dynamic color follows system dark mode.
Suite: 151 unit tests (JUnit + MockK + Turbine + MockWebServer +
Robolectric). Build clean, no warnings.

For architecture details, see the "Architecture" section below. For
personal-app conventions, see `CLAUDE.md`. For code-review fixes since
`b2b-main`, see `FIXES.md`.

## Setup

1. **Register a Spotify Developer app**: https://developer.spotify.com/dashboard
   - Create the app
   - Set redirect URI to `b2b://callback`
   - Copy the Client ID

2. **Plug in the Client ID**: Add to `~/.gradle/gradle.properties` (or the
   project-local `gradle.properties`):
   ```
   SPOTIFY_CLIENT_ID=your_client_id_here
   ```

3. **Spotify App Remote SDK**: Spotify does not publish the App Remote AAR
   to Maven. Download the latest release zip from
   https://github.com/spotify/android-sdk/releases and copy
   `spotify-app-remote-release-X.Y.Z.aar` into `app/libs/`. The
   `implementation(fileTree("libs") { include("*.aar") })` entry in
   `app/build.gradle.kts` picks it up automatically. One version
   (`0.8.0`) is already committed.

4. **Build**:
   ```bash
   ./gradlew :app:assembleDebug
   ```

5. **Tests**:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

## Device prerequisites

- Spotify app installed and logged in (App Remote talks to the local
  Spotify process, not the cloud directly)
- **Spotify Premium** (Web-API playback control doesn't work without
  Premium — the `enqueueNext` path otherwise yields
  `Outcome.Error.NotPremium` and the notification switches to "Premium
  required")
- During a session: Spotify must be a visible active device (app open or
  Android Auto connected)

## Usage

1. Launch the app → Login screen → "Sign in with Spotify" → Custom Tab
   opens → user authenticates. Scopes: `app-remote-control`,
   `user-modify-playback-state`, `user-read-playback-state`,
   `user-read-currently-playing`, `user-read-private` (the last one is
   required so that `/me` returns the `product` field — otherwise b2b
   sees every account as free tier).
2. The Spotify redirect `b2b://callback?code=...` arrives via intent
   filter in `MainActivity`, which exchanges the code through
   `PkceAuthManager`. Tokens are encrypted in
   `EncryptedSharedPreferences`.
3. The nav graph automatically switches to `HomeScreen` (gated on
   `TokenStore.authState()`).
4. **"Manage artists"** opens the dedicated artists screen. Search is
   explicit (🔍 button or IME Search action — typing does not issue a
   Spotify query; an empty field clears the last results locally).
   Search results have "+" buttons for adding. Whitelist entries at the
   top of the list have an active checkbox (whether the random picker
   currently uses them — toggling is local, no sync) and a 🗑 icon for
   permanent removal with a 5 s undo snackbar. Adds **no longer** trigger
   an automatic sync (that path used to run straight into Spotify rate
   limits when several artists were added in quick succession). Instead,
   the artists screen has an explicit **"Sync now"** button; the 24 h
   periodic worker still runs in any case. While a Spotify rate limit is
   active the button is hard-disabled (label: "Sync gesperrt —
   Rate-Limit aktiv") — the override lives in the Settings screen,
   behind a warning dialog. Inactive artists keep their pool tracks for
   fast reactivation; the next sync skips them (no API usage), and the
   picker filters them out via a JOIN on `whitelisted_artist.isActive = 1`.
5. "Start session" → `PlaybackOrchestratorService` starts as a foreground
   service, connects via App Remote to the local Spotify instance, and
   observes `PlayerState`. Position-based timer:
   `delay(durationMs - positionMs - 15_000)` arms per track event; when
   it elapses, the next pool track is pushed into Spotify's queue. This
   fires exactly once per track URI (per-track latch).
6. **Status card** on the home screen renders live:
   - Current `OrchestratorStatus` (`Currently:` / `✓ Last enqueued:` /
     `⚠ Spotify: <reason>` / `Not started`)
   - `Next: <Track> — <Artist> ↻` — the pre-picked pool entry that will
     be pushed at the next trigger. Tap `↻` to draw a fresh random from
     the pool without consuming anti-repeat history.
   - `Track: 1:23 / 3:45 · Next push in 0:42` — position extrapolated
     from the most recent SDK event (1 Hz tick), countdown to the
     trigger.
   - `Pool: N tracks · last sync 3h ago` or `Syncing now…` while a
     `PoolSyncWorker` is running. `N` counts only tracks belonging to
     active artists (same JOIN semantics as the picker — paused artists
     do not contribute to the display, even though their tracks remain
     in the DB).
   - `Artists: X aktiv von Y total` — the whitelist at a glance.
   - `Rate-Limit: HH:MM:SS` — visible only when Spotify has announced a
     wait to the `PoolSyncWorker` and the countdown is still running.
     Ticks every second, disappears at 0, and survives app restart
     (SharedPreferences-persisted `RateLimitStore`).
7. **Log panel** below the status card: a 500-line ring buffer
   (`LogBuffer`) with `HH:mm:ss  message` per entry, `reverseLayout`
   (newest on top). Sufficient for a "what just happened?" diagnosis
   without `adb logcat`. The buffer is wiped on process death — it is
   intentionally not a persistence mechanism.
8. Settings: "Sync pool now" (REPLACE policy, its own unique-work lane).
   If a Spotify rate limit is still running, the click first opens a
   warning dialog showing the remaining wait plus a "Trotzdem syncen"
   ("Sync anyway") button, which fires the worker with
   `inputData.force=true` past its rate-limit skip — deliberately the
   only override path, so an accidental tap cannot extend Spotify's
   penalty. "Cancel running sync" is the emergency exit when a worker
   is stuck (calls `WorkManager.cancelUniqueWork` on all lanes). "Sign
   out" calls `TokenStore.clear()` and the nav graph routes back to
   Login.

## Architecture

```
ui (Compose) ──> ViewModel ──> UseCase ──> Repository (interface, domain)
                                                │
                                                ▼
                                       Repository (impl, data)
                                                │
                                ┌───────────────┼──────────────┐
                                ▼               ▼              ▼
                          Retrofit/         Room DAO      EncryptedPrefs
                          OkHttp +          (Whitelist,   (Tokens)
                          AuthInter-        Pool, Recent)
                          ceptor
```

Cross-layer error transport: `domain/model/Outcome.kt`. Every fallible
operation returns `Outcome.Success<T>` or one of the enumerated error
cases. HTTP status codes are mapped centrally in
`data/repository/Repositories.kt::toOutcome`. We do not throw across
layer boundaries.

Background components:

- `PoolSyncWorker` (WorkManager): periodic every 24 h on UNMETERED
  network, plus manual runs from the artists and settings screens.
  Auto-sync after whitelist-add is deliberately gone — rapid adds used
  to run straight into Spotify rate limits. Walks the whitelist →
  `/artists/{id}/albums` → `/albums/{id}/tracks`, filters out tracks
  that don't list the requested artist (compilations / various-artists
  releases would otherwise contribute foreign tracks). Per artist, the
  pool slice is swapped atomically via
  `PoolTrackDao.replaceTracksForArtist` (`@Transaction` around
  `deleteByArtist` + `upsertAll`), so a worker kill between delete and
  insert cannot leave a briefly empty slice that a parallel `pickNext`
  might read. Hard caps prevent endless pagination (max 100 album
  pages, max 20 track pages per album, plus a break on `limit == 0`
  from pathological dev-mode responses).

  Four-tier rate-limit protection:

  1. **Active-skip:** at `doWork` entry the worker checks the
     `RateLimitStore`; if Spotify has announced a wait and the countdown
     is still running, exit with `Result.success()` and no API call.
     Issuing a request during the announced wait could be interpreted
     as hammering and extend the penalty. The Settings override lane
     sets `inputData.force=true` and bypasses this check.
  2. **Per-artist freshness skip:** before each artist's fetch,
     `lastSyncedEpochMsForArtist(id)` is compared against
     `FRESH_THRESHOLD_MS` (18 h). Fresh slices are left alone — a manual
     sync a few hours after the periodic run does not burn the entire
     API quota a second time. `force=true` bypasses this too.
  3. **Inter-artist cooldown:** between artists that actually hit the
     API, the worker waits `INTER_ARTIST_DELAY_MS` (30 s). This ensures
     Spotify's 30 s rolling rate-limit window is empty by the time the
     next burst starts — two artists never share a window. Force does
     NOT bypass the cooldown — the override should still treat the API
     respectfully.
  4. **In-run 429 handling:** for HTTP 429 with `Retry-After` ≤ 120 s
     the worker waits in-run via `delay()` (max 3 attempts per artist)
     and records `(retryAfterSeconds, now)` in the `RateLimitStore`;
     larger values are handed off to WorkManager backoff (5-min
     EXPONENTIAL initial, max 5 h). On a successful sync exit the
     worker clears the store.

  Two-tier timeouts: each artist has its own `MAX_PER_ARTIST_DURATION`
  budget (2 min). A stuck artist only costs its own turn —
  `withTimeoutOrNull` returns `null`, the log shows
  `sync: <id> timed out`, the existing pool slice is preserved, and the
  worker continues with the next artist. `MAX_RUN_DURATION` (30 min) is
  the outer safety net for non-fetch hangs (DB stall, pathologies
  outside the fetch path) and should never fire in practice.
- `PlaybackOrchestratorService` (foreground, `mediaPlayback`): hosts the
  `PlaybackOrchestrator` (pure Kotlin, testable without Android), which
  consumes a `Flow<PlayerState>` from `PlayerStateSource` and makes
  enqueue decisions. **Position-extrapolated timer**: the App Remote
  SDK does not emit periodically, so per state event the orchestrator
  arms `delay(durationMs - positionMs - TRIGGER_MS)`; each new event
  cancels and re-arms. A per-track latch acts as defense in depth. The
  pre-picked track is published via `PreviewTrackHolder` for the UI;
  `skipPreview()` replaces it without touching anti-repeat history.
  All Spotify App Remote SDK access lives exclusively in
  `AppRemotePlayerStateSource` — pinned via `flowOn(MainDispatcher)`,
  because the SDK internally instantiates `Handler()` without an
  explicit looper. Pre-onConnected cancel guard: if the collector is
  cancelled before the first `onConnected` callback, the freshly
  received remote disconnects itself inside the callback rather than
  leaking a limbo connection.
- Auth: `PkceAuthManager.refresh()` serializes concurrent 401-triggered
  refresh attempts behind a `Mutex` and returns an access token that
  was rotated under another caller, avoiding a redundant HTTP request.
  This prevents Spotify refresh-token-rotation invalidation on parallel
  401s. `TokenStore` additionally exposes a session epoch: `clear()`
  (logout) increments it, `doRefresh` captures it before its HTTP
  round-trip and persists via `storeIfMatchingEpoch` — a logout that
  races with a refresh invalidates the refresh result rather than
  silently restoring tokens after sign-out.

## Spotify API constraints

These endpoints were **deprecated for new apps on 2024-11-27** and are
**not used** here (and must not be):

- `/recommendations`
- `/artists/{id}/related-artists`
- `/audio-features`, `/audio-analysis`
- `/browse/featured-playlists`, `/browse/categories/{id}/playlists`

The track pool is instead built exclusively from the whitelisted artists'
own releases (`/artists/{id}/albums` + `/albums/{id}/tracks`). Details
and background in `docs/adr/0002-spotify-api-deprecation-handling.md`.

## Test conventions

See `app/src/test/java/com/github/reygnn/b2b/TESTING_CONVENTIONS.kt`.
Short version:

- **One dispatcher per test class** via `MainDispatcherRule`. Never
  instantiate `TestScope` or a separate `StandardTestDispatcher` inside
  a test.
- **MockK** for all mocking.
- **Turbine** for flow assertions.
- **`WhileSubscribed` flows**: use `runCurrent()` instead of
  `advanceUntilIdle()`, or subscribe via Turbine `.test { }`.
- **`MutableSharedFlow` in a constructor**: always `DROP_OLDEST` with a
  buffer. Otherwise the first emit suspends without an active
  subscriber.

Example tests:

- `domain/usecase/PickNextTrackUseCaseTest.kt` — canonical use-case
  pattern
- `playback/PlaybackOrchestratorTest.kt` — service logic via fake flow
- `data/auth/PkceAuthManagerTest.kt` — HTTP paths via MockWebServer

## Project layout

```
app/src/main/java/com/github/reygnn/b2b/
├── B2BApp.kt                Hilt entry, WorkManager config, schedules
│                            the periodic PoolSync (with 5-min backoff)
├── MainActivity.kt          Compose host, OAuth callback handler
├── di/                      Hilt modules + dispatcher / account qualifiers
├── diagnostics/             LogSink + LogBuffer (500-entry ring)
├── domain/                  Models, repository interfaces, use cases (pure)
├── data/
│   ├── remote/              Retrofit APIs (SpotifyApi + SpotifyAccountsApi),
│   │                        DTOs, AuthInterceptor
│   ├── local/               Room (entities, DAOs, AppDatabase)
│   ├── auth/                PKCE + TokenStore + AuthEventBus
│   └── repository/          Impls + PoolSyncObserver + RateLimitStore
│                            (SharedPreferences-persisted)
├── work/                    PoolSyncWorker + PoolSyncWorkNames
├── service/                 PlaybackOrchestratorService + ServiceState
├── playback/                PlaybackOrchestrator (pure logic, position-
│                            extrapolated timer) +
│                            PlayerStateSource (interface) +
│                            AppRemotePlayerStateSource (SDK-backed,
│                            Main-Looper-pinned) + PlayerState,
│                            OrchestratorStatus +
│                            OrchestratorStatusHolder, PlayerStateHolder,
│                            PreviewTrackHolder (UI-bus singletons)
└── ui/
    ├── AppViewModel.kt      Top-level (auth state)
    ├── theme/B2BTheme.kt    Material You dynamic color, system dark/light
    ├── nav/AppNavHost.kt    Login ↔ Whitelist ↔ Artists ↔ Settings
    ├── login/               LoginScreen + ViewModel
    ├── home/                HomeScreen + ViewModel (status card,
    │                        service toggle, skip pick, log panel,
    │                        BuildConfig.VERSION_NAME in the TopAppBar)
    ├── artists/             ArtistsScreen + ViewModel (explicit search,
    │                        active checkbox + trash-with-undo for
    │                        whitelist entries, "+" button for search
    │                        results, "Sync now" button with rate-limit
    │                        hard-block)
    └── settings/            SettingsScreen + ViewModel (manual sync
                             with rate-limit force-override dialog,
                             cancel sync, logout)
```

## Out of scope

- Multi-user / account switching
- A native Android Auto UI (the app stays a phone-side controller —
  Spotify itself runs on Android Auto)
- Import from an existing Spotify library
- Track-level blacklist
- Native Spotify-app auth (we use PKCE via Custom Tab; the
  `spotify-auth*.aar` files from the SDK zip live locally outside the
  repo under `spotify/`, in case this swap is wanted later)

## Polish backlog

- Make the anti-repeat window UI-configurable (DataStore preferences
  repo) — currently constant 50 in the service.
- Proactive token refresh before `expiresAtEpochMs`, instead of
  401-then-refresh.
- App Remote reconnect-retry on lost connection — currently the
  orchestrator emits `SpotifyUnavailable` and the `run` loop ends; the
  service stays foreground but does nothing until the user stops and
  starts it again.
- Release signing configuration + ProGuard rules for the Spotify SDK.
- Bias-pick: currently uniform random across the full pool. "Stay with
  the current artist" or similar would be plausible — uniform is
  intentional for now.

## ADRs

- `docs/adr/0001-single-dispatcher-test-convention.md`
- `docs/adr/0002-spotify-api-deprecation-handling.md`

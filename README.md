# B2B — Spotify Whitelist Companion

Android-Companion-App, die Spotify so steuert, dass nur Tracks aus einer
selbst gepflegten Artist-Whitelist gespielt werden. Hört man Spotify über
Android Auto, befüllt diese App im Hintergrund vom Handy aus die Spotify-
Queue mit zufälligen Tracks der gewählten Artists — der Spotify-eigene
Empfehlungsalgorithmus wird damit umgangen.

## Status

**End-to-End komplett, noch nicht auf einem Gerät validiert.** Alle
Skelett-Lücken sind implementiert: PKCE-OAuth (Exchange + Refresh) gegen
`accounts.spotify.com`, Periodic + One-Shot `PoolSyncWorker`, Compose-UI
(Login → Whitelist → Settings) inklusive Service-Toggle, App-Remote-SDK-
Integration via `AppRemotePlayerStateSource`. Suite: 49 Unit-Tests
(JUnit + MockK + Turbine + MockWebServer + Robolectric). Build clean,
keine Warnings. Personal-App-Konventionen siehe `CLAUDE.md`.

## Setup

1. **Spotify Developer App registrieren**: https://developer.spotify.com/dashboard
   - App erstellen
   - Redirect-URI auf `b2b://callback` setzen
   - Client-ID kopieren

2. **Client-ID einsetzen**: In `~/.gradle/gradle.properties` (oder lokal im
   Projekt) ergänzen:
   ```
   SPOTIFY_CLIENT_ID=dein_client_id_hier
   ```

3. **Spotify App Remote SDK**: Spotify veröffentlicht das App-Remote-AAR
   nicht über Maven. Lade das aktuelle Release-Zip von
   https://github.com/spotify/android-sdk/releases runter und kopiere
   `spotify-app-remote-release-X.Y.Z.aar` nach `app/libs/`. Der
   `implementation(fileTree("libs") { include("*.aar") })`-Eintrag in
   `app/build.gradle.kts` zieht es automatisch in den Classpath. Eine
   Version (`0.8.0`) ist bereits committed.

4. **Build**:
   ```bash
   ./gradlew :app:assembleDebug
   ```

5. **Tests**:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

## Voraussetzungen am Endgerät

- Spotify-App installiert und eingeloggt (App Remote spricht mit dem
  lokalen Spotify-Prozess, nicht direkt mit der Cloud)
- **Spotify Premium** (Web-API-Playback-Control geht ohne Premium nicht
  — der `enqueueNext`-Pfad löst sonst `Outcome.Error.NotPremium` aus,
  Notification wechselt auf "Premium required")
- Während der Session: Spotify als aktives Device sichtbar (App offen
  oder Android Auto verbunden)

## Bedienung

1. App starten → Login-Screen → "Sign in with Spotify" → Custom Tab
   öffnet sich → User authentifiziert sich.
2. Spotify-Redirect `b2b://callback?code=...` landet via Intent-Filter
   in `MainActivity`, das den Code via `PkceAuthManager` einlöst.
   Tokens sind verschlüsselt in `EncryptedSharedPreferences`.
3. Nav-Graph schaltet automatisch auf `WhitelistScreen` (gating über
   `TokenStore.authState()`).
4. Artists suchen (mit 300 ms Debounce) und zur Whitelist hinzufügen.
   Jede Add-Operation triggert einen One-Shot `PoolSyncWorker`
   (`KEEP`-Policy → mehrere schnelle Adds coalescen zu einem Lauf).
5. "Start session" → `PlaybackOrchestratorService` startet als
   Foreground, verbindet sich via App Remote mit der lokalen Spotify-
   Instanz, beobachtet `PlayerState`. Bei <15 s Restzeit des aktuellen
   Tracks wird der nächste Track aus dem Pool in die Spotify-Queue
   geschoben. Pro Track-URI feuert das genau einmal (Per-Track-Latch).
6. Settings: "Sync pool now" (REPLACE-Policy, eigene unique-work-Lane)
   und "Sign out" (`TokenStore.clear()` → Nav-Graph routet zurück zu
   Login).

## Architektur

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

Hintergrund:

- `PoolSyncWorker` (WorkManager): periodisch alle 24 h auf UNMETERED
  Network sowie One-Shot nach Whitelist-Änderungen. Walks die
  Whitelist → `/artists/{id}/albums` → `/albums/{id}/tracks` → upsert
  in `pool_track`. Bei HTTP 429 ehrt der Worker den `Retry-After`-Header
  per `delay()` innerhalb desselben Runs (bis zu 3 Versuche pro Artist),
  statt sofort an WorkManagers Backoff abzugeben.
- `PlaybackOrchestratorService` (Foreground, `mediaPlayback`): hostet
  den `PlaybackOrchestrator` (pure Kotlin, testbar ohne Android), der
  einen `Flow<PlayerState>` aus `PlayerStateSource` konsumiert und
  Enqueue-Entscheidungen trifft. Per-Track-Latch verhindert mehrfaches
  Feuern bei den ~mehrere-Hz-PlayerState-Updates pro Track. Spotify-
  App-Remote-SDK-Zugriff lebt ausschließlich in
  `AppRemotePlayerStateSource`.

## Spotify-API-Constraints

Diese Endpoints wurden am **27.11.2024 für neue Apps deaktiviert** und
sind hier **nicht verwendet** (und dürfen es auch nicht werden):

- `/recommendations`
- `/artists/{id}/related-artists`
- `/audio-features`, `/audio-analysis`
- `/browse/featured-playlists`, `/browse/categories/{id}/playlists`

Stattdessen wird der Track-Pool ausschließlich aus den eigenen Releases
der Whitelist-Artists aufgebaut (`/artists/{id}/albums` +
`/albums/{id}/tracks`). Details und Hintergrund in
`docs/adr/0002-spotify-api-deprecation-handling.md`.

## Test-Konvention

Siehe `app/src/test/java/com/github/reygnn/b2b/TESTING_CONVENTIONS.kt`.
Kurzfassung:

- **Ein Dispatcher pro Test-Klasse** via `MainDispatcherRule`. Niemals
  `TestScope` oder `StandardTestDispatcher` separat im Test
  instanziieren.
- **MockK** für alles Mocking.
- **Turbine** für Flow-Assertions.
- **`WhileSubscribed`-Flows**: `runCurrent()` statt `advanceUntilIdle()`,
  oder Turbine `.test { }` als Subscriber.
- **`MutableSharedFlow` im Konstruktor**: immer `DROP_OLDEST` mit Buffer.
  Sonst suspendiert der erste Emit ohne aktiven Subscriber.

Beispiel-Tests:

- `domain/usecase/PickNextTrackUseCaseTest.kt` — kanonisches Use-Case-Muster
- `playback/PlaybackOrchestratorTest.kt` — Service-Logik via Fake-Flow
- `data/auth/PkceAuthManagerTest.kt` — HTTP-Pfade via MockWebServer

## Verzeichnis

```
app/src/main/java/com/github/reygnn/b2b/
├── B2BApp.kt                Hilt-Entry, WorkManager-Config, schedules
│                            periodische PoolSync
├── MainActivity.kt          Compose-Host, OAuth-Callback-Handler
├── di/                      Hilt-Module + Dispatcher-/Account-Qualifier
├── domain/                  Modelle, Repository-Interfaces, UseCases (rein)
├── data/
│   ├── remote/              Retrofit-APIs (SpotifyApi + SpotifyAccountsApi),
│   │                        DTOs, AuthInterceptor
│   ├── local/               Room (Entities, DAOs, AppDatabase)
│   ├── auth/                PKCE + TokenStore + AuthEventBus
│   └── repository/          Implementierungen + PoolSyncTrigger
├── work/                    PoolSyncWorker
├── service/                 PlaybackOrchestratorService + ServiceState
├── playback/                PlaybackOrchestrator (pure logic) +
│                            PlayerStateSource (interface) +
│                            AppRemotePlayerStateSource (SDK-backed) +
│                            PlayerState, OrchestratorStatus
└── ui/
    ├── AppViewModel.kt      Top-level (Auth-State)
    ├── nav/AppNavHost.kt    Login ↔ Whitelist ↔ Settings + Auth-Gating
    ├── login/               LoginScreen + ViewModel
    ├── whitelist/           WhitelistScreen + ViewModel (Search-Debounce,
    │                        Service-Toggle)
    └── settings/            SettingsScreen + ViewModel (Logout + Manual-Sync)
```

## Out of Scope

- Multi-User / Account-Switching
- Android-Auto-eigene UI (App bleibt Phone-Controller — Spotify selbst
  läuft auf Android Auto)
- Import aus bestehender Spotify-Library
- Track-Level-Blacklist
- Native-Spotify-App-Auth (wir nutzen PKCE via Custom Tab; die
  `spotify-auth*.aar` aus dem SDK-Zip liegen lokal außerhalb des Repos
  unter `spotify/`, falls dieser Wechsel später erwünscht ist)

## v1-Polish, bewusst nicht im Skelett

- Anti-Repeat-Window über UI konfigurierbar (DataStore-Preferences-Repo)
- Proaktiver Token-Refresh vor `expiresAtEpochMs` statt 401-then-refresh
- AppRemote-Reconnect-Retry bei verlorener Verbindung
- Premium-Status-Caching (aktuell `/me`-Call pro Enqueue)
- Release-Signing-Konfiguration + ProGuard-Regeln für die Spotify-SDK
- Room-Migration-Setup (`exportSchema = true` + `room.schemaLocation`)

## ADRs

- `docs/adr/0001-single-dispatcher-test-convention.md`
- `docs/adr/0002-spotify-api-deprecation-handling.md`

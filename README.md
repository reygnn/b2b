# B2B — Spotify Whitelist Companion

Android-Companion-App, die Spotify so steuert, dass nur Tracks aus einer
selbst gepflegten Artist-Whitelist gespielt werden. Hört man Spotify über
Android Auto, befüllt diese App im Hintergrund vom Handy aus die Spotify-
Queue mit zufälligen Tracks der gewählten Artists — der Spotify-eigene
Empfehlungsalgorithmus wird damit umgangen.

## Status

**Real-Device-validiert, Version 0.4.0 (versionCode 28).** End-to-End-Push
beobachtet: Spotify spielt Whitelist-Track → 15 s vor Trackende schiebt b2b
den nächsten Pool-Track in die Spotify-Queue → Übergang sauber. PKCE-OAuth
gegen `accounts.spotify.com`, Periodic + One-Shot + Manual `PoolSyncWorker`,
Compose-UI (Login → Whitelist → Artists → Settings) mit Status-Karte,
Track-Position-Countdown, Skip-Button für den Vorschau-Pick, debounced
Artist-Suche und 500-Zeilen-Log-Panel. App-Remote-SDK-Integration via
`AppRemotePlayerStateSource` (Main-Looper-pinned). Material-You-Dynamic-
Color folgt System-Dark-Mode. Suite: 135 Unit-Tests (JUnit + MockK +
Turbine + MockWebServer + Robolectric). Build clean, keine Warnings.

Architektur-Details siehe Abschnitt „Architektur" unten; Personal-App-
Konventionen siehe `CLAUDE.md`; Code-Review-Korrekturen seit `b2b-main`
siehe `FIXES.md`.

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
   öffnet sich → User authentifiziert sich. Scopes: `app-remote-control`,
   `user-modify-playback-state`, `user-read-playback-state`,
   `user-read-currently-playing`, `user-read-private` (letzterer ist
   nötig, damit `/me` das `product`-Feld liefert — sonst sieht b2b
   jeden Account als Free-Tier).
2. Spotify-Redirect `b2b://callback?code=...` landet via Intent-Filter
   in `MainActivity`, das den Code via `PkceAuthManager` einlöst.
   Tokens sind verschlüsselt in `EncryptedSharedPreferences`.
3. Nav-Graph schaltet automatisch auf `HomeScreen` (gating über
   `TokenStore.authState()`).
4. **„Manage artists"** öffnet den dedizierten Artists-Screen: Suche
   (mit 300 ms Debounce) liefert Treffer als Listenzeilen mit „+"-Button
   zum Hinzufügen. Whitelist-Einträge oben in der Liste haben eine
   Aktiv-Checkbox (ob der Random-Picker sie aktuell verwendet — Toggle
   passiert lokal, kein Sync) und ein 🗑-Icon für endgültiges Entfernen
   mit 5 s Undo-Snackbar. Jede Add-Operation triggert einen One-Shot
   `PoolSyncWorker` (`KEEP`-Policy → mehrere schnelle Adds coalescen zu
   einem Lauf). Inaktive Artists behalten ihre Pool-Tracks für ein
   schnelles Re-Aktivieren; der nächste Sync überspringt sie (kein
   API-Verbrauch) und der Picker filtert sie via JOIN auf
   `whitelisted_artist.isActive = 1`.
5. "Start session" → `PlaybackOrchestratorService` startet als
   Foreground, verbindet sich via App Remote mit der lokalen Spotify-
   Instanz, beobachtet `PlayerState`. Position-basierter Timer:
   `delay(durationMs - positionMs - 15_000)` arms pro Track-Event, beim
   Ablauf wird der nächste Pool-Track in Spotifys Queue geschoben.
   Pro Track-URI feuert das genau einmal (Per-Track-Latch).
6. **Status-Karte** auf dem Home-Screen rendert live:
   - aktueller `OrchestratorStatus` (`Currently:` / `✓ Last enqueued:` /
     `⚠ Spotify: <reason>` / `Not started`)
   - `Next: <Track> — <Artist> ↻` — der vorgemerkte Pool-Pick, der
     beim nächsten Trigger gepusht wird. Tap auf `↻` zieht einen
     frischen Random aus dem Pool, ohne Anti-Repeat zu verbrauchen.
   - `Track: 1:23 / 3:45 · Next push in 0:42` — Position aus dem
     letzten SDK-Event extrapoliert (1 Hz Tick), Countdown bis
     Trigger.
   - `Pool: N tracks · last sync 3h ago` bzw. `Syncing now…` während
     ein `PoolSyncWorker` läuft.
7. **Log-Panel** unterhalb der Status-Karte: 500-Zeilen-Ring-Buffer
   (`LogBuffer`) mit `HH:mm:ss  message` pro Eintrag, reverseLayout
   (neuestes oben). Reicht für „was ist gerade gelaufen?"-Diagnose
   ohne adb-logcat. Buffer wird beim Prozess-Tod gewiped — er ist
   bewusst kein Persistenz-Mechanismus.
8. Settings: "Sync pool now" (REPLACE-Policy, eigene unique-work-Lane),
   "Cancel running sync" (Notausgang falls ein Worker hängt — ruft
   `WorkManager.cancelUniqueWork` auf alle drei Lanes), "Sign out"
   (`TokenStore.clear()` → Nav-Graph routet zurück zu Login).

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
  Network, One-Shot nach Whitelist-Add, Manual via Settings. Walks die
  Whitelist → `/artists/{id}/albums` → `/albums/{id}/tracks`, filtert
  Tracks ohne den angefragten Artist aus (Compilations / Various-
  Artists-Releases liefern sonst Fremd-Tracks). Pro Artist wird der
  Pool-Slice via `PoolTrackDao.replaceTracksForArtist` atomar getauscht
  (`@Transaction` um `deleteByArtist` + `upsertAll`), damit ein Worker-
  Kill zwischen Delete und Insert keinen kurzfristig leeren Slice
  hinterlässt, den ein paralleler `pickNext` lesen könnte. Hard caps
  gegen Endlos-Pagination (max 100 Album-Pages, max 20 Track-Pages
  pro Album, plus Break bei `limit == 0` aus pathologischen Dev-Mode-
  Responses). 10-min-Worker-Timeout als letzte Verteidigungslinie.
  Bei HTTP 429 mit Retry-After ≤ 120 s wird in-run `delay()`-gewartet
  (max 3 Versuche pro Artist); größere Werte werden an WorkManager-
  Backoff (5-min EXPONENTIAL initial, max 5 h) übergeben, damit wir
  Spotify nicht weiter hämmern und eine bestehende Penalty verlängern.
- `PlaybackOrchestratorService` (Foreground, `mediaPlayback`): hostet
  den `PlaybackOrchestrator` (pure Kotlin, testbar ohne Android), der
  einen `Flow<PlayerState>` aus `PlayerStateSource` konsumiert und
  Enqueue-Entscheidungen trifft. **Position-extrapolierter Timer**:
  App-Remote-SDK emittiert nicht periodisch, daher pro State-Event
  `delay(durationMs - positionMs - TRIGGER_MS)` armed, jedes neue
  Event cancelt + re-armt. Per-Track-Latch als Defense-in-Depth.
  Vorgemerkter Pick wird im `PreviewTrackHolder` für die UI publik
  gemacht; `skipPreview()` ersetzt ihn ohne Anti-Repeat zu touchen.
  Spotify-App-Remote-SDK-Zugriff lebt ausschließlich in
  `AppRemotePlayerStateSource` — pinned via `flowOn(MainDispatcher)`,
  weil das SDK intern `Handler()` ohne expliziten Looper instanziiert.
  Pre-onConnected-Cancel-Guard: wenn der Collector vor dem ersten
  `onConnected`-Callback gecancelt wird, disconnected sich die frisch
  erhaltene Remote im Callback selbst, anstatt eine Limbo-Connection
  zu leaken.
- Auth: `PkceAuthManager.refresh()` serialisiert konkurrierende
  401-getriggerte Refresh-Versuche über eine `Mutex` und gibt einen
  bereits unter dem Caller frisch gerotierten Access-Token zurück,
  ohne einen redundanten HTTP-Request zu posten. Verhindert die
  Spotify-Refresh-Token-Rotations-Invalidation bei parallelen 401s.
  Zusätzlich kapselt `TokenStore` eine Session-Epoch: `clear()` (Logout)
  inkrementiert sie, `doRefresh` capturet sie vor dem HTTP-Call und
  persistiert via `storeIfMatchingEpoch` — ein zwischenzeitlicher Logout
  invalidiert das Refresh-Ergebnis, anstatt Tokens nach dem Sign-out
  stillschweigend wiederherzustellen.

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
│                            periodische PoolSync (mit 5-min Backoff)
├── MainActivity.kt          Compose-Host, OAuth-Callback-Handler
├── di/                      Hilt-Module + Dispatcher-/Account-Qualifier
├── diagnostics/             LogSink + LogBuffer (500-Eintrag-Ring)
├── domain/                  Modelle, Repository-Interfaces, UseCases (rein)
├── data/
│   ├── remote/              Retrofit-APIs (SpotifyApi + SpotifyAccountsApi),
│   │                        DTOs, AuthInterceptor
│   ├── local/               Room (Entities, DAOs, AppDatabase)
│   ├── auth/                PKCE + TokenStore + AuthEventBus
│   └── repository/          Impls + PoolSyncTrigger/Observer
├── work/                    PoolSyncWorker + PoolSyncWorkNames
├── service/                 PlaybackOrchestratorService + ServiceState
├── playback/                PlaybackOrchestrator (pure logic, position-
│                            extrapolierter Timer) +
│                            PlayerStateSource (interface) +
│                            AppRemotePlayerStateSource (SDK-backed,
│                            Main-Looper-pinned) + PlayerState,
│                            OrchestratorStatus +
│                            OrchestratorStatusHolder, PlayerStateHolder,
│                            PreviewTrackHolder (UI-Bus-Singletons)
└── ui/
    ├── AppViewModel.kt      Top-level (Auth-State)
    ├── theme/B2BTheme.kt    Material You dynamic color, system dark/light
    ├── nav/AppNavHost.kt    Login ↔ Whitelist ↔ Artists ↔ Settings
    ├── login/               LoginScreen + ViewModel
    ├── home/                HomeScreen + ViewModel (Status-Karte,
    │                        Service-Toggle, Skip-Pick, Log-Panel,
    │                        BuildConfig.VERSION_NAME in der TopAppBar)
    ├── artists/             ArtistsScreen + ViewModel (Search-Debounce,
    │                        Aktiv-Checkbox + Trash-mit-Undo für Whitelist-
    │                        Einträge, "+"-Button für Search-Results)
    └── settings/            SettingsScreen + ViewModel (Manual-Sync,
                             Cancel-Sync, Logout)
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

## Offene Polish-Punkte

- Anti-Repeat-Window über UI konfigurierbar (DataStore-Preferences-Repo) —
  aktuell konstant 50 im Service.
- Proaktiver Token-Refresh vor `expiresAtEpochMs` statt 401-then-refresh.
- AppRemote-Reconnect-Retry bei verlorener Verbindung — der
  Orchestrator emittiert `SpotifyUnavailable` und der `run`-Loop endet;
  Service bleibt foreground aber tut nichts mehr bis User stop+start.
- Release-Signing-Konfiguration + ProGuard-Regeln für die Spotify-SDK.
- Bias-Pick: aktuell uniform random aus dem ganzen Pool. „Stay with the
  current artist" o.ä. wäre denkbar — aktuell intentional uniform.

## ADRs

- `docs/adr/0001-single-dispatcher-test-convention.md`
- `docs/adr/0002-spotify-api-deprecation-handling.md`

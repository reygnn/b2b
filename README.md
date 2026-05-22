# B2B — Spotify Whitelist Companion

Android-Companion-App, die Spotify so steuert, dass nur Tracks aus einer
selbst gepflegten Artist-Whitelist gespielt werden. Hört man Spotify über
Android Auto, befüllt diese App im Hintergrund vom Handy aus die Spotify-
Queue mit zufälligen Tracks der gewählten Artists — der Spotify-eigene
Empfehlungsalgorithmus wird damit umgangen.

## Status

**Real-Device-validiert, Version 0.5.8 (versionCode 38).** End-to-End-Push
beobachtet: Spotify spielt Whitelist-Track → 15 s vor Trackende schiebt b2b
den nächsten Pool-Track in die Spotify-Queue → Übergang sauber. PKCE-OAuth
gegen `accounts.spotify.com`, Periodic + Manual `PoolSyncWorker` mit
mehrschichtigem Rate-Limit-Schutz (Skip während laufender Spotify-Strafe,
Per-Artist Frische-Skip, 30 s Inter-Artist-Cooldown, Per-Artist-Timeout),
Compose-UI (Login → Whitelist → Artists → Settings) mit Status-Karte,
Track-Position-Countdown, Skip-Button für den Vorschau-Pick, explizite
Artist-Suche und 500-Zeilen-Log-Panel. App-Remote-SDK-Integration via
`AppRemotePlayerStateSource` (Main-Looper-pinned). Material-You-Dynamic-
Color folgt System-Dark-Mode. Suite: 151 Unit-Tests (JUnit + MockK +
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
4. **„Manage artists"** öffnet den dedizierten Artists-Screen: Suche ist
   explizit (🔍-Button bzw. IME-Search-Action — Tippen löst keine
   Spotify-Abfrage aus, ein leeres Feld räumt die letzten Treffer lokal
   weg). Search-Results haben „+"-Buttons zum Hinzufügen. Whitelist-
   Einträge oben in der Liste haben eine Aktiv-Checkbox (ob der
   Random-Picker sie aktuell verwendet — Toggle passiert lokal, kein
   Sync) und ein 🗑-Icon für endgültiges Entfernen mit 5 s Undo-Snackbar.
   Adds triggern **keinen** automatischen Sync mehr (das lief vorher in
   Spotify-Rate-Limits, wenn man mehrere Artists schnell hintereinander
   hinzufügte). Stattdessen sitzt auf dem Artists-Screen ein expliziter
   **„Sync now"**-Button; der periodische 24-h-Worker greift sowieso.
   Während eines aktiven Spotify-Rate-Limits ist der Button hart
   disabled (Label „Sync gesperrt — Rate-Limit aktiv") — die Override-
   Option lebt im Settings-Screen, hinter einem Warn-Dialog. Inaktive
   Artists behalten ihre Pool-Tracks für ein schnelles Re-Aktivieren;
   der nächste Sync überspringt sie (kein API-Verbrauch) und der Picker
   filtert sie via JOIN auf `whitelisted_artist.isActive = 1`.
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
     ein `PoolSyncWorker` läuft. `N` zählt nur Tracks aktiver Artists
     (gleiche JOIN-Semantik wie der Picker — pausierte Artists tragen
     nicht zur Anzeige bei, auch wenn ihre Tracks noch in der DB
     liegen).
   - `Artists: X aktiv von Y total` — die Whitelist in der Übersicht.
   - `Rate-Limit: HH:MM:SS` — nur sichtbar, wenn Spotify dem
     `PoolSyncWorker` eine Wartezeit angekündigt hat und der Countdown
     noch läuft. Tickt jede Sekunde, verschwindet automatisch bei 0,
     und überlebt App-Restart (SharedPreferences-persistierter
     `RateLimitStore`).
7. **Log-Panel** unterhalb der Status-Karte: 500-Zeilen-Ring-Buffer
   (`LogBuffer`) mit `HH:mm:ss  message` pro Eintrag, reverseLayout
   (neuestes oben). Reicht für „was ist gerade gelaufen?"-Diagnose
   ohne adb-logcat. Buffer wird beim Prozess-Tod gewiped — er ist
   bewusst kein Persistenz-Mechanismus.
8. Settings: "Sync pool now" (REPLACE-Policy, eigene unique-work-Lane).
   Wenn ein Spotify-Rate-Limit noch läuft, öffnet der Klick zuerst einen
   Warn-Dialog mit der Restzeit und einem „Trotzdem syncen"-Button, der
   den Worker via `inputData.force=true` an seinem Rate-Limit-Skip
   vorbei feuert — bewusst ausgelegt als die einzige Override-Option,
   damit ein versehentlicher Klick die Spotify-Strafe nicht verlängert.
   "Cancel running sync" ist der Notausgang falls ein Worker hängt
   (ruft `WorkManager.cancelUniqueWork` auf alle Lanes). "Sign out"
   ruft `TokenStore.clear()` und der Nav-Graph routet zurück zu Login.

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
  Network, plus Manual aus Artists- und Settings-Screen. Auto-Sync nach
  Whitelist-Add ist bewusst weg — schnelle Adds rannten in
  Spotify-Rate-Limits. Walks die Whitelist → `/artists/{id}/albums` →
  `/albums/{id}/tracks`, filtert Tracks ohne den angefragten Artist aus
  (Compilations / Various-Artists-Releases liefern sonst Fremd-Tracks).
  Pro Artist wird der Pool-Slice via `PoolTrackDao.replaceTracksForArtist`
  atomar getauscht (`@Transaction` um `deleteByArtist` + `upsertAll`),
  damit ein Worker-Kill zwischen Delete und Insert keinen kurzfristig
  leeren Slice hinterlässt, den ein paralleler `pickNext` lesen könnte.
  Hard caps gegen Endlos-Pagination (max 100 Album-Pages, max 20
  Track-Pages pro Album, plus Break bei `limit == 0` aus pathologischen
  Dev-Mode-Responses).

  Vierschichtiger Rate-Limit-Schutz:

  1. **Active-Skip:** beim doWork-Entry prüft der Worker den
     `RateLimitStore`; wenn Spotify eine Wartezeit angekündigt hat und
     der Countdown noch läuft, exit mit `Result.success()` ohne
     API-Aufruf. Eine erneute Anfrage während der angekündigten
     Wartezeit könnte als Hämmern interpretiert werden und die Strafe
     verlängern. Die Settings-Override-Lane setzt `inputData.force=true`
     und überspringt diesen Check.
  2. **Per-Artist Frische-Skip:** pro Artist wird vor dem Fetch
     `lastSyncedEpochMsForArtist(id)` gegen `FRESH_THRESHOLD_MS` (18 h)
     gemessen. Frische Slices bleiben unberührt — ein Manual-Sync wenige
     Stunden nach dem Periodikum verbrennt nicht erneut die ganze API-
     Quota. `force=true` übergeht auch das.
  3. **Inter-Artist-Cooldown:** zwischen Artists, die tatsächlich die API
     treffen, wartet der Worker `INTER_ARTIST_DELAY_MS` (30 s). Damit
     ist Spotifys 30-s-Rolling-Window beim nächsten Burst leer, zwei
     Artists landen nie im selben Fenster. Force übergeht den Cooldown
     nicht — der Override soll respektvoll mit der API umgehen.
  4. **In-Run 429-Handling:** bei HTTP 429 mit Retry-After ≤ 120 s wird
     in-run `delay()`-gewartet (max 3 Versuche pro Artist) und der
     `RateLimitStore` mit `(retryAfterSeconds, jetzt)` befüllt; größere
     Werte werden an WorkManager-Backoff (5-min EXPONENTIAL initial,
     max 5 h) übergeben. Bei erfolgreichem Sync-Ende cleart der Worker
     den Store.

  Zweistufige Timeouts: jeder Artist bekommt sein eigenes
  `MAX_PER_ARTIST_DURATION`-Budget (2 min). Ein hängender Artist kostet
  nur seinen Turn — `withTimeoutOrNull` returnt `null`, Log
  `sync: <id> timed out`, der existierende Pool-Slice bleibt, der Worker
  macht mit dem nächsten Artist weiter. `MAX_RUN_DURATION` (30 min) ist
  das äußere Safety-Net für nicht-Fetch-Hänger (DB, sonstige
  Pathologien) und sollte in der Praxis nie greifen.
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
│   └── repository/          Impls + PoolSyncObserver + RateLimitStore
│                            (SharedPreferences-persistiert)
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
    ├── artists/             ArtistsScreen + ViewModel (explizite Suche,
    │                        Aktiv-Checkbox + Trash-mit-Undo für Whitelist-
    │                        Einträge, "+"-Button für Search-Results,
    │                        "Sync now"-Button mit Rate-Limit-Hard-Block)
    └── settings/            SettingsScreen + ViewModel (Manual-Sync mit
                             Rate-Limit-Force-Override-Dialog, Cancel-
                             Sync, Logout)
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

# B2B — Spotify Whitelist Companion

Android-Companion-App, die Spotify so steuert, dass nur Tracks aus einer
selbst gepflegten Artist-Whitelist gespielt werden. Hört man Spotify über
Android Auto, befüllt diese App im Hintergrund vom Handy aus die Spotify-Queue
mit zufälligen Tracks der gewählten Artists — der SoundCloud-/Spotify-eigene
Algorithmus wird damit umgangen.

## Status

**Skelett.** Kompiliert nach Gradle-Sync. Vollständige Layer-Struktur, alle
Interfaces verdrahtet, vier Unit-Tests passen. Nicht implementiert sind die
beiden TODOs in `PkceAuthManager` (Token-Exchange + Refresh HTTP-Calls) und
die App-Remote-SDK-Integration in `PlaybackOrchestratorService`. Beide sind
markiert und vom Rest entkoppelt.

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
   `app/build.gradle.kts` zieht es automatisch in den Classpath.

4. **Build**:
   ```bash
   ./gradlew :app:assembleDebug
   ```

5. **Tests**:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

## Voraussetzungen am Endgerät

- Spotify-App installiert und eingeloggt
- **Spotify Premium** (Web-API-Playback-Control geht ohne Premium nicht)
- Spotify als aktives Device sichtbar (App offen, Android Auto verbunden)

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

Hintergrund:
- `PoolSyncWorker` (WorkManager, alle 24h auf UNMETERED): Whitelist → Albums →
  Tracks → `pool_track`.
- `PlaybackOrchestratorService` (Foreground): App-Remote-PlayerState beobachten,
  bei <15 s Restzeit nächsten Track aus Pool minus Recent-Window in Spotify-
  Queue schicken.

## Spotify-API-Constraints

Diese Endpoints wurden am **27.11.2024 für neue Apps deaktiviert** und sind
hier **nicht verwendet** (und dürfen es auch nicht werden):
- `/recommendations`
- `/artists/{id}/related-artists`
- `/audio-features`, `/audio-analysis`
- `/browse/featured-playlists`, `/browse/categories/{id}/playlists`

Stattdessen wird der Track-Pool ausschließlich aus den eigenen Releases der
Whitelist-Artists aufgebaut (`/artists/{id}/albums` + `/albums/{id}/tracks`).

## Test-Konvention

Siehe `app/src/test/java/.../TESTING_CONVENTIONS.kt`. Kurzfassung:

- **Ein Dispatcher pro Test-Klasse** via `MainDispatcherRule`. Niemals
  `TestScope` oder `StandardTestDispatcher` separat im Test instanziieren.
- **MockK** für alles Mocking.
- **Turbine** für Flow-Assertions.

Beispiel-Test:
`app/src/test/java/com/github/reygnn/b2b/domain/usecase/PickNextTrackUseCaseTest.kt`

## Verzeichnis

```
app/src/main/java/com/github/reygnn/b2b/
├── B2BApp.kt
├── MainActivity.kt
├── di/                  Hilt-Module + Dispatcher-Qualifier
├── domain/              Modelle, Repository-Interfaces, UseCases (rein)
├── data/
│   ├── remote/          Retrofit-API, DTOs, AuthInterceptor
│   ├── local/           Room (Entities, DAOs, AppDatabase)
│   ├── auth/            PKCE + TokenStore
│   └── repository/      Implementierungen
├── work/                PoolSyncWorker
├── service/             PlaybackOrchestratorService
├── playback/            AntiRepeatRingBuffer
└── ui/                  Compose-Screens + ViewModels
```

## Out of Scope

- Multi-User / Account-Switching
- Android-Auto-eigene UI (App bleibt Phone-Controller — Spotify selbst läuft
  auf Android Auto)
- Import aus bestehender Spotify-Library
- Track-Level-Blacklist

## ADRs

- `docs/adr/0001-single-dispatcher-test-convention.md`
- `docs/adr/0002-spotify-api-deprecation-handling.md`

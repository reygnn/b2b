# ADR 0002 — Spotify Web API deprecation handling

Status: Accepted
Date: 2026-05-16

## Context

On 2024-11-27 Spotify deprecated several Web API endpoints for new app
registrations:

- `/recommendations`
- `/artists/{id}/related-artists`
- `/audio-features`, `/audio-analysis`
- `/browse/featured-playlists`, `/browse/categories/{id}/playlists`

Apps with existing extended-mode access continue to work; new apps cannot use
them.

## Decision

We do not depend on any deprecated endpoint. The whitelist app's design
intentionally needs none of them:

- Pool sourcing: `/artists/{id}/albums` (paginated) + `/albums/{id}/tracks`
  (paginated). Tracks the artist actually released — usually the right thing
  for "I want to hear more of this artist".
- Discovery: handled by the user adding artists explicitly via `/search`.
- Playback control: `/me/player/devices`, `/me/player/queue`.

The Retrofit interface `SpotifyApi` is the single point of API surface. New
endpoints are added there with a code-review note confirming they are not on
the deprecated list. A linter rule could enforce this; for now it is a
review-time check.

## Consequences

- No dependency on Spotify's recommendation model. The "discovery" is the
  user's own decision plus the artist's full discography.
- If Spotify deprecates further endpoints (`/me/player/queue` would be
  catastrophic), the orchestrator service is the only consumer and can be
  swapped for `/me/player/play` with a manually managed URI list as fallback.
- The app cannot offer "more like X" suggestions. Out of scope by design.

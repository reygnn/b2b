# ADR 0003 — Trickle pool sync (one artist per tick)

Status: Accepted
Date: 2026-05-23
Last reviewed: 2026-05-23 (active-skip restored; see "Active-skip" below)

## Context

The original [`PoolSyncWorker`](../../app/src/main/java/com/github/reygnn/b2b/work/PoolSyncWorker.kt)
walked every active whitelisted artist in a single run, separated by a
30 s inter-artist cooldown. To keep that burst manageable it accumulated
a four-tier rate-limit protection: a `RateLimitStore` "active-skip" at
`doWork` entry, an 18 h per-artist freshness skip, the 30 s inter-artist
cooldown, and in-run 429 handling with a `MAX_RATE_LIMIT_WAIT_SECONDS`
cap before falling back to `Result.retry()`. Two work lanes were exposed:
`PERIODIC` from `B2BApp` (24 h) and `MANUAL` from the Artists / Settings
"Sync now" buttons, with `force=true` from the Settings override dialog
to bypass the active-skip after the user acknowledged the warning.

On 2026-05-22/23 this design produced a multi-hour Spotify penalty:

- The user pressed Settings → "Sync now" → "Sync anyway" once, sending a
  `force=true` `OneTimeWorkRequest` into the `MANUAL` lane.
- The single artist's `Retry-After` came back at 22316 s (≈ 6 h). The
  worker recorded it to `RateLimitStore`, hit the cap, returned
  `Result.retry()`.
- WorkManager preserves `inputData` across backoff retries, so the next
  scheduled retry ran the same worker again with `force=true` — bypassing
  the active-skip we had just recorded.
- This repeated through six WorkManager-driven retries over ten hours.
- Around 06:45 the next periodic occurrence collided with a backoff retry
  of the manual lane: two PoolSyncWorker instances ran in parallel against
  the same already-banned artist (visible in the log as duplicate lines
  and an "auth: refresh coalesced" from the [`PkceAuthManager`](../../app/src/main/java/com/github/reygnn/b2b/data/auth/PkceAuthManager.kt)
  mutex catching the race).
- Within minutes, Spotify upgraded the penalty to 86018 s (≈ 24 h),
  affecting a different artist that had previously synced cleanly. The
  app was effectively shadow-banned for the day.

The root cause was structural: a sync that touches N artists is one
burst, no matter how cleverly we space it inside the burst. As soon as
anything (`force`, a parallel lane, retry) sidestepped the active-skip,
the burst hit Spotify during an announced wait and earned a longer one.

## Decision

We replace the all-at-once burst with a **trickle**: each periodic tick
refreshes at most **one** active artist.

- Tick cadence: 15 minutes (`PeriodicWorkRequest`'s hard floor,
  `MIN_PERIODIC_INTERVAL_MILLIS`). This is 30× Spotify's documented
  rolling rate-limit window — two artist-fetches cannot share a window
  by construction.
- Artist selection lives in SQL ([`WhitelistDao.pickNextToSync(floor)`](../../app/src/main/java/com/github/reygnn/b2b/data/local/dao/Daos.kt)):
  active artists only, never-synced first (sorted by `addedAtEpochMs`
  ASC so the user's add-order is honored), then stalest-past-floor.
- Freshness floor: 24 h. If everything has been refreshed inside that
  window the tick is a no-op `Result.success()` — no API call, one log
  line.
- **Active-skip at `doWork` entry.** If [`RateLimitStore`](../../app/src/main/java/com/github/reygnn/b2b/data/repository/RateLimitStore.kt)
  reports a non-zero remaining wait, the worker exits `Result.success()`
  immediately, no API call. Spotify's own documentation says "wait for
  the number of seconds specified in `Retry-After`"; sending requests
  during the announced wait is the documented path to penalty extension.
  This is unconditional — there is no `force` flag, no input-data
  override, no caller path that bypasses it. (See "Note on the
  active-skip" below for the design history.)
- Outcomes:
  - `Outcome.Success` → atomic slice swap + prune-against-current-whitelist
    + `rateLimitStore.clear()`.
  - `Outcome.Error.RateLimited` → `rateLimitStore.record(seconds)`
    (which arms the next tick's active-skip and the UI countdown),
    then `Result.retry()` with WorkManager's 5 min exponential backoff.
    No in-run delay.
  - `Outcome.Error.Network` → `Result.retry()`.
  - Other terminal errors (Unauthenticated, etc.) → `Result.failure()`;
    the next 15 min occurrence picks up.

What goes away:

- The `MANUAL` work-lane and both `manualSync()` ViewModel methods.
- The Settings force-override dialog (`rate_limit_dialog_*` strings,
  `KEY_FORCE` input data, `force=true` argument plumbing).
- The Artists "Sync now" button.
- The Settings "Cancel running sync" button.
- `INTER_ARTIST_DELAY_MS`, `MAX_RATE_LIMIT_WAIT_SECONDS`,
  `MAX_RATE_LIMIT_ATTEMPTS`, `MAX_PER_ARTIST_DURATION`, `MAX_RUN_DURATION`,
  and the in-run for-loop over `activeIds`. The new worker is ~50 lines.

What stays:

- `RateLimitStore` — both as the active-skip gate (above) and as the
  source for the home screen's "Rate-Limit: HH:MM:SS" countdown. The
  worker records on 429 and clears on a successful tick.

## Note on the active-skip

The first revision of this ADR retired the active-skip along with the
rest of the four-tier protection, on the reasoning that the trickle's
15 min cadence already kept us safe. That was wrong. Even at 15 min
spacing, a fresh 429 opens a wait window of hours, during which the
worker would issue ~four requests per hour into Spotify's announced
wait — the exact "hammer through the announced penalty" behaviour
that caused the incident in the first place, just spread out over more
time.

The flawed pattern in the original incident was not the active-skip
itself — it was `force=true` letting the user's "Sync anyway" tap
bypass the gate, and WorkManager preserving that bypass across backoff
retries. With `force=true` and the entire manual-override surface
removed, the active-skip has no exception path and can be applied
unconditionally. That is the safe and Spotify-recommended behaviour.

## Consequences

- **No more bursts.** A single artist's API calls (one `/artists/{id}/albums`
  page, plus its `/albums/{id}/tracks` pages) easily fit inside one
  rolling window with margin to spare. Two artists are 15 minutes apart.
  Spotify cannot interpret this as hammering.
- **Slower initial fill.** At 15 min/artist, six artists fill in 90 min,
  one hundred in 25 h. For a personal-app whitelist this is fine; the
  status card's "Syncing now…" indicator and the log panel give the
  user visible progress.
- **No "Sync now" UX.** Newly added artists are picked up by the next
  tick automatically (within 15 min). The user trades immediate-pool-fill
  for a system that cannot accidentally enter penalty mode. This is the
  correct trade-off for a system that previously made the wrong one.
- **Graceful degradation at scale.** With more active artists than
  `(24 h / 15 min) = 96`, the freshness floor can no longer be honored;
  the worker simply picks the stalest one and refresh intervals stretch
  beyond 24 h. The system stays burst-free; only the freshness contract
  relaxes.

## Alternatives considered

1. **Patch the existing design.** Make `force=true` non-persistent across
   `Result.retry()`, add a single global lock across `PERIODIC` and
   `MANUAL` lanes, keep everything else. Rejected: the root cause is
   the burst, not the specific control-flow that failed this time. The
   next defect at a similar surface (another lane, a retry that
   forgets the lock, a future feature with its own work-request) would
   reproduce the same class of failure.

2. **Self-rescheduling `OneTimeWorkRequest` chain at 2 min.** Considered
   because 2 min × Spotify's 30 s window gives more margin. Rejected
   because the safety factor at 15 min (30×) is already vastly
   sufficient, and `PeriodicWorkRequest` is a simpler, better-tested
   harness than a self-rescheduling chain that needs a watchdog to
   recover from the rare chain-break.

3. **Drop the freshness floor and refresh on every tick.** Rejected:
   wastes Spotify quota and our DB writes on slices that have not
   changed; a refresh interval of 15 min serves no observable user
   need.

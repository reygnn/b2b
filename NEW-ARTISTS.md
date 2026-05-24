# NEW-ARTISTS.md — penalty triggered by adding new artists

Working document. Hypothesis and proposed mitigations for a 20 h
Spotify rate-limit penalty observed on 2026-05-24, **after** the
defensive trickle (ADR-0003) and the active-skip restore had landed.
This is a problem we did **not** solve with the trickle rewrite, and
this file is the parking spot for the next round of analysis once we
have a log to work from.

## Observation

- **Penalty trigger**: user added four new artists to the whitelist.
  All four were added in one session (between morning and the time
  the penalty surfaced).
- **Penalty size**: ~20 hours announced by Spotify.
- **Log buffer**: empty at the time we tried to diagnose. Initially
  attributed to process death, but the user clarified afterward that
  they had pressed the **Clear Log** button before noticing the
  penalty in the status card — a single-tap user error that wiped
  the only diagnostic record. The `RateLimitStore` survived
  (SharedPreferences-backed) and that is how the user can see the
  20 h countdown.
- **Trickle behaviour throughout**: morning sync run 06:46 – 08:21
  was clean (5 artist fetches, all `200 OK`, no `skipping tick`, no
  429). The defensive trickle by itself did not produce this
  penalty.
- **Active-skip behaviour now**: as designed — every tick logs
  `sync: rate-limited for Xs, skipping tick` for the next ~20 h,
  zero API calls.

## What this rules out (already mitigated by ADR-0003)

The 2026-05-22/23 incident's exact failure mode is structurally
gone. We can be confident the following did **not** cause this
penalty:

- `force=true` persisting across WorkManager retries → the flag and
  its plumbing have been deleted; the `SyncRateLimitGuardrailsTest`
  suite pins this.
- A second work-lane running in parallel → `PoolSyncWorkNames.ALL`
  is exactly `[PERIODIC]`; the test class pins this.
- In-run `delay()` on 429 → removed; tests pin that virtual time
  barely advances during a 429 handling tick.
- Active-skip bypass → unconditional, no input flag can override.

The trickle's invariant ("at most one artist fetch per Spotify rate-
limit window") is intact. Whatever caused this penalty is **outside**
the trickle code path.

## Hypotheses, ranked

### H1 — `/search` API calls during the add session (highest)

Each press of the 🔍 button in **Manage Artists** issues a
`GET /search?q=...&type=artist` call. The trickle does not gate
this — by design, since search has to be live to be useful.

Adding four artists typically means more than four searches: the
user iterates on query text, sees results, refines, tries again.
A realistic session might emit 8–20 search calls in 10–15 minutes.
Combined with whatever else is hitting Spotify at the time
(see H3), this can easily push total request volume across
Spotify's rate-limit threshold for the app credentials.

`/search` is also the one Spotify endpoint b2b uses that takes a
user-controlled string. There is no debounce. There is no per-
query cache. There is no minimum interval between successive
searches. The user can spam-tap the button and we'll spam-call
Spotify.

**Confidence**: medium-high. This is the most plausible
"unprotected vector" we know exists; we have no measurement on its
actual call rate, but the user said they were *adding* four
artists (implying multiple search interactions) which is exactly
the trigger this hypothesis predicts.

### H2 — Spotify "watching period" after yesterday's major penalty

Yesterday's penalty was 86 018 s (~24 h). It expired around 06:46
this morning. The morning trickle then ran cleanly for ~95 min.
Then mid-day the user adds artists → new penalty.

Spotify does not document this, but it is consistent with how
many rate-limited APIs work in practice: after a major penalty,
the same account/app credentials receive stricter quotas for some
period — anything from hours to a few days — even after the
visible Retry-After has elapsed. The same total request volume
that would be fine on a "clean" day can hit the lowered ceiling
on a post-penalty day.

If H2 is true, the same workflow tried in a week (no recent
penalty) might not trigger anything. That's also why we cannot
just unit-test our way to certainty here — the threshold is
external state we don't observe.

**Confidence**: medium. Plausible but unfalsifiable without
controlled experiments across multiple-day windows.

### H3 — Concurrent orchestrator activity

If the user was listening to Spotify while adding artists, the
`PlaybackOrchestratorService` was actively firing `POST /me/player/queue`
roughly every 3–5 minutes (once per track end). During a 15 min
window of adding artists, that's 3–5 extra calls from the
orchestrator on top of search calls and any trickle tick that
happened to fall in the window.

Spotify's rate limit is per-app, not per-endpoint, so the
orchestrator's queue calls count against the same budget as
`/search` and `/artists/.../albums`. Three independent subsystems
(orchestrator + search + trickle) firing into the same 30 s
rolling window can easily look like a burst from Spotify's side
even though each one in isolation is gentle.

**Confidence**: medium. Likely a contributor rather than the sole
cause. Could be confirmed if the user can next time correlate the
penalty with whether music was playing.

### H4 — Trickle-induced burst for newly-added artists

Adding four artists at once gives each `lastSync = null`. The
trickle's selection order puts never-synced artists first
(ordered by `addedAtEpochMs` ASC), so the next four ticks would
pick them up one after another at 15 min intervals.

That is by itself well within the rate-limit window — four ticks
spread across 60 minutes is not a burst. But: each fetch hits
`/artists/{id}/albums` (paginated, up to 100 album-pages) plus
`/albums/{id}/tracks` (paginated, up to 20 track-pages per album).
A discography-heavy artist (we've seen 700 tracks in a real log)
generates dozens of API calls inside its single tick. Four
heavy-discography artists in a row could amount to ~200 API calls
spread over an hour — well below the 30 s rolling window threshold
on its own, but combined with H1 + H3 could push total daily
volume over a per-day quota.

**Confidence**: low-medium. Per 30 s window the trickle is still
safe by construction. The risk surface is a **per-day** quota that
Spotify does not document.

### H5 — `/me` calls or auth refresh storms

Each auth refresh is a single token-endpoint call, but if `TokenStore`
is invalidating mid-session — e.g. due to a scope change or
inconsistency — it could refresh repeatedly. The morning log
showed one `auth: refresh ok` line every few hours, which is
normal. Without a log from the incident, I can't rule out a refresh
storm during the add session.

**Confidence**: low. No evidence either way. Easy to confirm next
time by counting `auth:` log lines in the relevant window.

## Proposed mitigations, ranked

### M1 — Guard the Clear Log button (do first)

The actual loss this time was a single accidental tap on the
**Clear Log** icon, not process death. Two cheap options:

- **Confirmation dialog**: tapping the icon opens an
  `AlertDialog` ("Clear the log? This cannot be undone.") with
  Cancel / Clear buttons. One extra tap, but it makes the
  destructive action explicit.
- **Undo snackbar**: a 5 s snackbar with an Undo action, mirroring
  the existing artist-delete pattern in `ArtistsViewModel`. The
  cleared entries are kept in a buffer until the timer elapses;
  Undo restores them. Slightly more code but no extra friction
  in the common case.

I'd pick the undo snackbar — it matches a pattern already
established in the codebase, and zero friction for intentional
clears.

**Cost**: tiny (UI only, no schema, no Hilt rewiring).

**Risk**: zero. Strict UX improvement.

### M1b — Persistent log for post-mortem diagnosis (still worth doing later)

The `LogBuffer` is also wiped on process death — Doze, OOM kill,
forced stop, etc. The user-error case is the immediate motivator
this time, but process death will eventually wipe a log too. A
persistent mirror remains a worthwhile second layer of defense.

Add a `Room`-backed log table that mirrors the same `LogSink`
interface (`LogSink.log(message)` writes to both the in-memory
buffer *and* a persistent table). Keep the last ~24 h of entries,
prune on a periodic task. Show / share via the existing
"Copy log" affordance, but with the persistent buffer feeding it.

**Cost**: small. Migration v2→v3 (one new table), a new
`PersistentLogSink` impl wired through Hilt, an eviction worker.

**Risk**: small. The table is append-only and unrelated to any
Spotify path.

Lower priority than M1 because M1 addresses the actually-observed
loss; M1b addresses a theoretical future one.

### M2 — Search debounce + per-query cache

Address H1 head-on. Two complementary changes:

- **Debounce**: tappable search button is rate-limited to one call
  per N seconds (say 2 s). Holding the IME Search action repeats
  it only at the same interval. This prevents accidental
  spam-tapping from emitting many calls.
- **Per-query cache**: if the user submits the same query string
  twice within the same screen session, return the cached
  results. No HTTP. The user gets visible feedback (results
  appear) without burning a call.

Combined, these reduce realistic add-session search load from
8–20 calls to 4–8.

**Cost**: medium. Both live in `ArtistsViewModel.submitSearch`.

**Risk**: tiny. Worst case: a slightly stale result for the same
query (the user can hit the button again after the debounce
window).

### M3 — Cross-feature `RateLimitStore` awareness

The store is currently gated only by the trickle. The orchestrator
and search paths ignore it. If Spotify announces a penalty:

- Orchestrator continues calling `/me/player/queue` until each
  track ends → potentially extends the penalty.
- Search continues calling `/search` → also potentially extends.

Proposal: any code path that touches Spotify should consult
`RateLimitStore.state().value` first. During an announced wait:

- **Orchestrator**: emit a `SpotifyUnavailable` status, pause
  enqueues. Resume when the wait clears.
- **Search**: button disabled, with a label like "Rate-limited —
  wait HH:MM:SS". (We already had that string before
  ADR-0003 removed it; it can come back, scoped to display only.)

This generalizes the trickle's defensive principle to the rest of
the app: while Spotify is asking us to wait, **nothing** in the app
should make API calls.

**Cost**: medium. Wire `RateLimitStore.state()` through the
Orchestrator and the ArtistsViewModel. Some new UI states.

**Risk**: small. The behaviour is strictly less aggressive than
today. Worst case: user can't search for a few hours after a
penalty hit, which is acceptable.

### M4 — Telemetry: per-day Spotify call counter

To test H2 (and detect any future regressions of M2/M3), add a
simple counter that tracks Spotify API calls per rolling 24 h
window, broken down by endpoint family (search / artists / albums /
queue / me / token). Surface it on a debug-only screen or in the
log panel as a periodic "stats" line.

If H2 is right we'd see the counter sit at e.g. ~500 calls/day on
normal use and the penalty hit when it climbs past ~1500. That
would give us a concrete threshold to tune M2/M3 against.

**Cost**: small-medium. A `SpotifyCallCounter` (Room or a simple
prefs-backed sliding window) and a hook on each Retrofit call.

**Risk**: tiny. Read-only counter.

### M5 — Trickle: extra cooldown for never-synced artists

Marginal mitigation for H4. When the trickle picks a never-synced
artist (which means a heavy discography-dump fetch), insert an
extra 15-min gap before the next tick — effectively making "burst
add" sessions trickle at 30 min/artist instead of 15 min/artist.

This is incremental and doesn't change the architectural invariant.
I'd defer this until M1 + M4 give us evidence whether the trickle
itself was actually a contributor.

**Cost**: small. One conditional in `pickNextToSync`.

**Risk**: small. Slower first-fill for new artists. The user
already accepted the trickle's "wait it out" trade-off.

### M6 — Global call-budget gate

The conservative ground-truth solution: a single in-process token
bucket that all Spotify calls pass through. E.g., max one call per
2 s globally. Heavy-handed and would slow down playback transitions
slightly, but completely robust against any combination of
subsystem-induced bursts.

I would only reach for this if M1 + M2 + M3 + M4 don't tame the
problem.

**Cost**: large. A `SpotifyCallBudget` interceptor, careful
ordering with `AuthInterceptor`, latency budget tuning.

**Risk**: medium. Slowing the orchestrator's queue enqueue by even
a few seconds could push it past Spotify's "next track" handover
window. Needs measurement.

## Recommendation

In order:

1. **M1 (guard the Clear Log button)** — addresses the actually-
   observed loss. Tiny UI change, ships in an afternoon.
2. **M2 (search debounce + cache)** — addresses the most plausible
   unprotected Spotify path with low risk.
3. **M3 (cross-feature RateLimitStore awareness)** — broadens the
   trickle's defensive principle to the rest of the app. Right
   architectural move regardless of which hypothesis is correct.
4. **M4 (per-day counter)** — gives us the data to test H2 and
   tune the rest. Especially useful once M1 + M1b are in place
   and we can rely on the log surviving.
5. **M1b (persistent log)** — second layer of log durability against
   process death.
6. **M5, M6** — deferred until data justifies.

If we have to pick just one for the next branch: **M1 + M2**
together. M1 makes the next incident diagnosable; M2 makes it
less likely to happen in the first place. Both fit cleanly on a
single `feature/` branch.

## Things to verify next time the user adds artists

Sequence to capture in the re-test (per Spotify's penalty
expiring):

1. **Take the log immediately on incident.** Use the 📋 copy
   button in the log panel *before* opening anything else that
   might cause process death.
2. **Note what was playing.** Was music playing? Was the
   orchestrator firing? Was Spotify queued or paused?
3. **Note search count.** Approximate how many times the 🔍 button
   was pressed in the session.
4. **Note timing.** How long between the first artist-add and the
   first sign of a 429? Was there a successful sync in between?
5. **Note pool state.** Did the four new artists ever get tracks
   in the pool (`<Name> (<N>)` row showed non-zero), or did the
   penalty hit before any trickle tick reached them?

With those five facts plus a log we can move from "two plausible
hypotheses" to "here is the exact sequence and here is the fix."

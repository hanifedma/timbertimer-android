# Architecture

Notes for anyone changing this code — mostly about the decisions that are not
obvious from reading it, and the ones that would be easy to undo by accident.

## Layout

```
app/src/main/java/com/example/timbertimer/
  TimberApplication.kt   the dependency container, and the wiring that only
                         makes sense once everything exists
  MainActivity.kt        deep links, widget destinations, system bar styling

  core/                  no Android, no Compose — the parts worth unit testing
    Seed.kt              the seeded tree maths, ported bit-for-bit from the web
    Time.kt              clock, calendar boundaries, ISO-8601
    UiMessage.kt         a message as a resource id, resolved at display time

  data/
    model/Models.kt      FocusRecord, Note, ActiveTimer, RestTimer, TreeSpecies
    RecordMapper.kt      row <-> record, with the table's CHECK constraints
    TimberRepository.kt  the single source of truth for records and to-dos
    local/               SharedPreferences: records, notes, settings, locale
    remote/              Supabase auth (PKCE), PostgREST, Realtime

  timer/
    TimerEngine.kt       owns the running timer; application-scoped
    TimerService.kt      foreground service: process priority + notification
    TimerNotifications.kt  channels, the ongoing notification, alerts
    TimerAlarms.kt       exact-alarm backstop, boot restore
    TimerFeedback.kt     PCM chime synthesis and vibration

  ui/                    Compose screens, the eight trees, theme
  widget/                the home screen to-do widget
```

Dependencies point inward: `ui` knows about `data`, `data` knows about nothing
above it, and `core` knows about neither.

## The timer does not count down

`ActiveTimer` stores `endAt` — an instant — not a number of seconds remaining.
Every reading is derived from the wall clock:

```kotlin
fun remainingSeconds(now: Long) = maxOf(0L, ceilDiv(endAt - now, 1000L))
```

Nothing has to keep ticking for the timer to stay correct, so being killed,
dozed, or rebooted cannot make it drift, and there is no state to repair on the
way back. A timer whose moment passed while the phone was off is simply *due*.

Three things notice that it is due, and they all funnel into one guarded
`complete()`:

| | |
|---|---|
| the 1-second ticker | while the process is alive |
| the foreground service | keeps the process alive so the ticker runs |
| an exact alarm | fires even if the service was torn down by a vendor battery manager |

`complete()` holds a mutex and a `completing` flag, so whichever arrives second
finds the work already done.

## Two devices, one record

Both the phone and the website can be watching the same countdown. Whichever one
*deletes* the shared `active_focus_timers` row is the one that writes the
record; the other finds nothing to delete and steps aside:

```kotlin
if (!repository.claimCloudTimer(timer)) {
    applyTimer(null); repository.refresh()
    message(R.string.toast_timer_finished_elsewhere); return
}
```

Postgres arbitrates, so there is no clock to agree on. The consequence worth
remembering: **a failed upload of that row must never be recorded as a success.**
A timer wrongly believed to be published would find nothing to claim when it
finished, conclude another device had recorded it, and discard the session. This
is why `pushCloudTimer` returns a boolean and only a `true` sets `cloudSynced`.

## A finished session is durable before the network is touched

`createRecord` writes to a local outbox *first*, then attempts the upload and
removes it from the outbox on success. A process death mid-upload therefore
costs nothing; the next refresh flushes the outbox before it fetches.

This is the one piece of data the app must never lose — everything else can be
re-derived or re-entered.

## Realtime decides *when*, never *what*

`RealtimeClient` subscribes to all four tables over a Phoenix websocket. A change
of any kind triggers a full reconcile through the ordinary load path, rather than
applying the row delta.

Applying deltas would mean a second merge path, exercised only when two devices
are in use at once — precisely the case where a disagreement between the two
would be hardest to reproduce. One path stays correct; the socket only decides
when it runs.

Polling stays as well. Supabase streams a table only once it is published for
replication, which no project does by default, so a refused channel has to
degrade to the old 15-second poll rather than to silence. `connected` is what
the engine reads to stretch that poll to 60 seconds while the socket is up.

## `core/Seed.kt` is a bit-for-bit port

The web client derives a tree's species and colours from a hash of the session
name. Reproducing it *approximately* would mean the same session grows a
different tree on each client — visible, unexplainable, and impossible to fix
later without rewriting history.

The subtle part is the species mix:

```js
seed = (seed * 0x5bd1e995) >>> 0;
```

In JavaScript that multiply exceeds 2^53 and **loses precision** — and the lost
bits decide the answer. `Seed.defaultSpeciesFor` performs the same step in
`Double` and spells out ECMAScript's `ToUint32`, so it lands on the same species
rather than a plausible one.

`SeedParityTest` pins this against values generated by running the website's own
functions under Node. If it ever fails, the port has drifted — fix the port, not
the test.

## Notification channels

| Channel | Importance | Why |
|---|---|---|
| `timer-running-v2` | DEFAULT | LOW files it under "Silent", which most lock screens and nearly every OEM skin hide. The running timer is the one thing that must be readable without unlocking. Sound and vibration are stripped at the channel instead. |
| `timer-done` | HIGH | It is an alarm. |
| `timer-idle` | LOW | An invitation sitting in the shade, not an alert. |

A channel's importance is **fixed once created**, so raising it required a new
id; the old one is deleted on upgrade so it does not linger in settings. Sound
and vibration are played by the app, not the channel, so the in-app switches
actually govern them.

## The widget

`TodoWidget` is an `AppWidgetProvider` and must be `exported="true"` — the
launcher cannot enumerate it otherwise. Since ticking a task changes user data,
those taps go to a **separate, non-exported** `TodoWidgetActions` receiver, so
only this app's own PendingIntents can drive them.

A collection gets exactly one `PendingIntentTemplate`, so both row behaviours
share it and are told apart by the fill-in intent's action: the circle sends
`ACTION_TOGGLE`, the text sends `ACTION_OPEN`. The header, `+` and empty state
launch the activity directly instead, because they can.

Two constraints that are easy to trip over:

- **RemoteViews inflates only a fixed set of classes.** `android.view.View` is
  not one of them — a divider made of one fails the entire widget with
  "Can't load widget".
- The list adapter can run in a process started for that tap alone, before any
  account is resolved, so it reads a flat snapshot written by the repository
  rather than trying to work out where the real list lives.

## Testing

`./gradlew testDebugUnitTest` — 26 tests, all off-device:

- **`SeedParityTest`** — the hash, species, palette and jitter against Node-generated
  expectations from the web client.
- **`TimerLogicTest`** — clock-derived timing including the reboot case, the
  table's clamps, species fallbacks, rest detection, Sunday-start weeks, and both
  timestamp shapes Postgres and the web client write.

`core/` and `data/` are deliberately free of Android imports so this stays
possible; `Seed.kt` in particular has no Compose import, which is why the palette
is returned as raw HSL and converted in `ui/`.

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
    Seed.kt              the name -> species/colour hash, bit-for-bit from the web
    Palette.kt           a project colour -> the four colours a tree is drawn with
    CalendarLayout.kt    records -> per-day blocks, packed into columns
    Time.kt              clock, calendar boundaries, ISO-8601
    UiMessage.kt         a message as a resource id, resolved at display time

  data/
    model/Models.kt      FocusRecord, Note, ActiveTimer, RestTimer, TreeSpecies
    model/Project.kt     Project, the built-ins, and the ProjectBook lookups
    RecordMapper.kt      row <-> record, with the table's CHECK constraints
    TimberRepository.kt  the single source of truth for projects, records, to-dos
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

## A project owns the colour and the tree; a record only points at one

`FocusRecord.projectId` is the only link. How a record is *drawn* is asked of the
`ProjectBook`, never of the record:

```kotlin
fun speciesFor(record: FocusRecord): TreeSpecies {
    if (record.status == RecordStatus.ABANDONED) return TreeSpecies.WILTED
    val project = projectFor(record)
    if (!project.missing) return project.species
    return TreeSpecies.byLabelOrId(record.treeKind) ?: TreeSpecies.PINE
}
```

That is what makes recolouring a project re-plant its whole forest at once. The
`tree_kind` still written on every row is the fallback for a record whose project
was deleted on another device, and the value the web client reads.

Ids are plain strings rather than UUIDs, which is what lets two devices invent
the same project without coordinating:

| id | what it is |
|---|---|
| `focus`, `rest` | the two built-ins, seeded on a fresh install, never deletable |
| `t:deep focus` | a record written *before* projects existed, mapped by its title |
| a UUID | anything the user made |

`Projects.resolveId` does that mapping at the boundary, so nothing above
`RecordMapper` ever sees a row without a project. A completed record carrying the
wilted tree was a rest; anything else keys off its lowercased title. Colours and
species come from `Seed.mixedHash`, so "Reading" is the same indigo pine on every
device with nothing written down first.

**The projects table is optional.** `projectsTableMissing`,
`sessionProjectColumnMissing` and `activeTimerProjectColumnMissing` each latch on
the first rejection and drop the field from subsequent writes — PostgREST refuses
a whole request that names a column which does not exist, so a record saved
against a database that predates the migration has to omit it rather than send
null. The app keeps working; projects simply stay on the device.

## The calendar's layout is a plain function

`buildSegments` splits every record into per-day pieces — a session across
midnight appears in both columns, marked `partial` so neither half offers a drag
— and `pack` gives overlapping pieces a column each. Both are ordinary Kotlin in
`core/`, because that is where a day grid actually goes wrong and it is far
easier to pin down in `CalendarLayoutTest` than by dragging blocks on a device.

The screen is left with placing rectangles and reading one gesture. That gesture
is hand-written rather than composed from `detectTapGestures` +
`detectDragGesturesAfterLongPress`, for one reason: **nothing may be consumed
until a long press has actually happened.** A finger that moves before then is
scrolling the day, and the scroller above has to be free to take it. Stacking the
stock detectors does not work either — `detectTapGestures` consumes the down
event, which cancels the drag detector's long press.

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

`RealtimeClient` subscribes to all five tables over a Phoenix websocket. A change
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

## `core/Seed.kt` and `core/Palette.kt` are bit-for-bit ports

The web client derives a project's species and colour from a hash of its name,
and its tree's four colours from that colour. Reproducing either *approximately*
would mean the same project looks different on each client — visible,
unexplainable, and impossible to fix later without rewriting history.

The subtle part is the species mix:

```js
seed = (seed * 0x5bd1e995) >>> 0;
```

In JavaScript that multiply exceeds 2^53 and **loses precision** — and the lost
bits decide the answer. `Seed.defaultSpeciesFor` performs the same step in
`Double` and spells out ECMAScript's `ToUint32`, so it lands on the same species
rather than a plausible one.

The colour arithmetic has a quieter trap: the web builds a CSS `hsl()` string,
which **rounds every component to a whole number**. Skipping that rounding leaves
colours a shade off on every tree. `Palette` rounds in the same place.

`SeedParityTest` pins all of it against values generated by running the website's
own functions under Node. If it ever fails, the port has drifted — fix the port,
not the test.

## Two doors into the same session

`SupabaseAuth` speaks two grants, and they exist for a presentation reason as
much as a technical one:

| grant | how it looks |
|---|---|
| `pkce` | a Custom Tab hands off to Google and returns through Supabase's callback, so Google's prompt names `<project>.supabase.co` |
| `id_token` | `GoogleSignIn` asks Credential Manager for a token on-device, so Android's own sheet appears and names *this app* |

The redirect's address is not something branding can change — it is where Google
is actually returning the user — so the second door is the only way to be rid of
it. It is also the more fragile one: it needs Play Services, a Google account,
and this build's signing certificate registered against an Android OAuth client.
Every way it can fail returns `Unavailable` and falls back to the first door,
and a failure latches for the session so a second tap does not repeat it.

The nonce is split the same way the web client splits it: Google is given the
SHA-256 hash, Supabase the original, which is what proves the token was minted
for this request rather than replayed from another.

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

`./gradlew testDebugUnitTest` — 49 tests, all off-device:

- **`SeedParityTest`** — the hash, the species and colour a name picks, the tree
  palette a colour produces, the readable ink per theme, and the jitter, against
  Node-generated expectations from the web client.
- **`TimerLogicTest`** — clock-derived timing including the reboot case, the
  table's clamps, how a pre-projects row is mapped to a project, the built-ins'
  sort order, the deleted-project fallback, Sunday-start weeks, and both
  timestamp shapes Postgres and the web client write. The date picker's
  midnight-UTC round trip is here too: reading it back in the device's own zone
  lands on the day before for anyone east of Greenwich.
- **`CalendarLayoutTest`** — the day a record lands on, the split across
  midnight, the padding that keeps a one-minute record tappable, and the column
  packing (including a freed lane being reused rather than widening the run).

`core/` and `data/` are deliberately free of Android imports so this stays
possible; `Seed.kt` and `Palette.kt` in particular have no Compose import, which
is why colours are returned as raw HSL and converted in `ui/`.

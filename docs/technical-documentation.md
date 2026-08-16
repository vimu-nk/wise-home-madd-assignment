# WiseHome — Technical Documentation

**Module:** SCS 3311 — Mobile Application Design & Development
**Project:** Smart Home Monitoring & Control System (mini-project)
**Repository:** `madd-assignment` (monorepo: `android-app/`, `web-simulator/`, `supabase/`, `docs/`)

This document is the deliverable required by the project guide — *"a concise report
outlining the synchronizing mechanism, floor representation and simulator operations"* —
extended with the finalized tech stack, the mapping of every stated requirement onto the
code that implements it, and an account of how the work was carried out.

Companion documents:

| Document | Contents |
|---|---|
| [`smart-home-project-spec.md`](smart-home-project-spec.md) | Design decisions log (v1 → v8), device catalogue, house layout |
| [`technical-report.md`](technical-report.md) | Short-form report (the condensed version of §3–§5 here) |
| [`../README.md`](../README.md) | Setup, build and deployment instructions |

---

## 1. Scope and shape of the system

WiseHome is three deployables and **one source of truth**.

```
   Android client  ─────┐                    ┌─────  Web hardware simulator
   (Kotlin/Compose)     │                    │       (HTML + supabase-js)
        intent          ▼                    ▼        hardware reality
                 ┌──────────────────────────────────┐
                 │  Supabase / PostgreSQL           │
                 │  · 16 tables + row-level security │
                 │  · triggers (usage log, safety)   │
                 │  · pg_cron workers (safety, light)│
                 │  · Realtime (WAL → websocket)     │
                 └──────────────────────────────────┘
                              decision + record
```

The two clients never talk to each other. Both read and write the same Postgres
database and both subscribe to Supabase Realtime, so a change made anywhere reaches
everyone else without a refresh.

The division of responsibility is deliberate and drives most of the design:

* The **app** expresses *intent* — "turn the iron on", "this light should be on from
  18:00 to 22:30".
* The **database** *decides and records* what actually happened — schedules, the
  maximum-on-duration cut-off, and all usage history are enforced server-side, so they
  hold when no phone is switched on.
* The **simulator** *reports what the hardware is doing*, and is the only producer of
  the `ERROR` and `DISCONNECTED` states. No amount of tapping in the app can create a
  fault, exactly as with real hardware.

---

## 2. Finalized tech stack

| Layer | Choice | Why this, and not the alternative |
|---|---|---|
| Mobile client | **Kotlin + Jetpack Compose**, native Android (minSdk 24, targetSdk 37) | Deliverable is an APK only, so cross-platform buys nothing; Compose's `StateFlow` → recomposition model is a direct fit for reactive device state. **Rejected Flutter** — new toolchain for no benefit. |
| Architecture | **MVVM**: Composable → ViewModel (`StateFlow`) → Repository → Supabase | Keeps every network call in the data layer; no Composable touches the Supabase client. |
| Navigation | **Navigation Compose**, 3-tab bottom bar | `Screen.bottomNavItems` = Home / Alerts / Settings. |
| Backend / DB | **Supabase (PostgreSQL)** | Relational model fits 11 heterogeneous device types cleanly (base table + per-type extension tables). **Rejected Firebase** — Cloud Functions require the Blaze plan and a linked credit card even at $0 usage. |
| Sync | **Supabase Realtime** (Postgres WAL → websocket) | Bidirectional push to both clients; no polling anywhere in the system. |
| Server-side workers | **`pg_cron` + PL/pgSQL functions**, inside the database | Zero external hosting or deploy step. **Rejected a Node/Express worker** — more infra for a time-boxed project. |
| Server-side invariants | **Postgres triggers** | Usage logging and safety-timer arming cannot be forgotten by any writer. |
| Hardware simulator | **Plain HTML/CSS/JS + supabase-js (vendored)**, GitHub Pages | No build tooling; static hosting; same database as the app. |
| Images | **Coil** (`coil-compose`, `coil-network-okhttp`) | Camera snapshot rendering. |
| HTTP/WS engine | **Ktor OkHttp engine** | Non-negotiable — see §3.5. |
| Auth | **None** (single shared home) + **row-level security** | Simplifies the schema; RLS constrains what the public publishable key can do. |

Android dependency set (`android-app/app/build.gradle.kts`): Compose BOM, Material 3,
Material Icons Extended, Lifecycle/ViewModel Compose, Navigation Compose,
kotlinx-serialization, kotlinx-coroutines, `supabase-postgrest`, `supabase-realtime`,
`ktor-client-okhttp`, Coil, core-library desugaring (for `java.time` on API 24).

Supabase credentials are injected as `BuildConfig` fields from
`android-app/local.properties` (gitignored) and from `web-simulator/config.js` — never
committed.

---

## 3. Synchronizing mechanism

### 3.1 Overview

There are three synchronization paths, and each exists for a different reason:

| Path | Direction | Mechanism |
|---|---|---|
| **Write** | client → database | Optimistic local update → REST write → reconcile with the returned row (rollback on failure) |
| **Read** | database → all clients | Realtime `postgres_changes` over a websocket, one channel per shared table |
| **Autonomous** | database → database → all clients | `pg_cron` workers + triggers mutate rows; the change propagates back out over the same Realtime path |

### 3.2 Write path — optimistic, then reconciled

A toggle in the app does not wait for the network before redrawing.
`DeviceRepository.setStatus()` applies the change to its in-memory `StateFlow`
immediately, issues the `UPDATE`, then **replaces** the local row with the authoritative
row read back from Postgres. On failure the optimistic change is rolled back and an
error is emitted to the UI (a snackbar via `HomeViewModel.errors`).

```
tap → local StateFlow patched → PATCH /rest/v1/devices → row re-selected → local row replaced
                                       │
                                       └── exception → previous row restored + error emitted
```

Trusting the *returned* row rather than the local guess matters because the same row is
concurrently writable by the simulator and by the cron workers. A value the server
adjusted, or a change that landed from another writer in between, wins instead of being
papered over by the local optimistic value.

Writes that touch `devices` from anywhere in the app — including
`DeviceExtrasRepository` while a detail sheet is open — are routed **through**
`DeviceRepository`, so the one shared device cache (and every grid badge and room row
reading it) learns about the change.

### 3.3 Read path — Realtime subscriptions

Every table a client observes is in the `supabase_realtime` publication
(`20260816000001_enable_realtime.sql`, extended by `20260817000003_rooms.sql`):
`devices`, `device_switches`, `alerts`, `smart_locks`, `safety_configs`, `thermostats`,
`ac_units`, `sensors`, `cameras`, `power_metrics`, `usage_logs`, `floors`, `rooms`,
`light_schedules`, `safety_presets`.

Two subscription tiers in the app:

* **Shared, app-lifetime channels** — one per shared table, owned by repository
  singletons in `RepositoryProvider` (`devices`, `device_switches`, `alerts`, `floors`,
  `rooms`). ViewModels `collect` these and filter client-side, e.g.
  `HomeViewModel.devicesOnSelectedFloor` combines the device flow with the selected
  floor id.
* **Scoped channels** — `DeviceExtrasRepository.watchTable()` opens a channel for the
  extension table of the device currently being viewed and closes it on dismissal, so
  the socket carries only what is on screen. The same pattern serves
  `UsageRepository.observeUsageForDevice()`.

Replica identity stays at the Postgres default: clients decode `record` for
INSERT/UPDATE (always fully populated) and read only `oldRecord["id"]` for DELETE, which
the primary-key identity supplies.

Four implementation details that the system depends on:

1. **The subscribe/fetch race.** Every watcher fetches once immediately, again once the
   subscription is confirmed live (`channel.subscribe(blockUntilSubscribed = true)`
   followed by `refresh()`), and then on each change. Without the second fetch, a change
   landing between the initial read and the subscription going live is invisible until
   the next user action.
2. **Reconnect and resume.** `RepositoryProvider.startConnectionWatch()` connects the
   socket explicitly at startup rather than relying on connect-on-first-subscribe — so
   the connection indicator does not depend on which screen the user opens first — and
   calls `refreshAll()` whenever the status returns to `CONNECTED`. `MainActivity` also
   calls `refreshAll()` on `RESUMED`. Between the two, nothing that happened while the
   app was backgrounded or offline can leave stale state behind.
3. **Channel cleanup.** Scoped channels are removed in a `finally` block wrapped in
   `withContext(NonCancellable)`; the suspending `unsubscribe()` would throw
   `CancellationException` immediately and leak the channel.
4. **Duplicate-instance hazard.** Repositories *must* be shared singletons. An earlier
   version let each ViewModel construct its own via default constructor arguments,
   producing two `DeviceRepository` instances, two caches, and two channels on the same
   topic — optimistic updates on one screen were invisible on another.

### 3.4 Autonomous path — server-side rules

Two `pg_cron` jobs run inside the database:

| Job | Interval | Function | Behaviour |
|---|---|---|---|
| `safety-cutoff-check` | 15 s | `run_safety_cutoff()` | Finds `ON` devices whose `safety_configs.turned_on_at` is older than `max_on_duration_seconds`; sets `devices.status = 'OFF'`, clears `turned_on_at`, stamps `last_auto_cutoff_at`, writes an `AUTO_CUTOFF` usage log and an `alerts` row |
| `light-schedule-check` | 1 min | `run_light_schedules()` | Switches `scheduled_light` devices in `AUTO` mode on/off from `light_schedules` |

**The safety timer is armed by a trigger, not by the app.** `trg_devices_safety_arm`
(`arm_safety_timer()`) fires `after update of status on devices`: on OFF→ON it stamps
`turned_on_at`, on ON→anything it clears it. Originally the Android client stamped this
itself, which left a hole — an iron switched on from the *simulator*, i.e. from the
hardware side, never armed the timer and would have stayed on indefinitely, the exact
scenario the cut-off exists to prevent. Moving it into a trigger covers every writer by
construction: app, simulator, cron, or SQL editor.

The light worker's rules are equally deliberate:

* **`AUTO` only** — `control_mode = 'MANUAL'` is the user override, and the worker must
  not fight a user who has taken control.
* **Skips `ERROR` / `DISCONNECTED`** — those are hardware states asserted by the
  simulator; flipping `status` would erase a fault the hardware is reporting.
* **Only devices with an enabled schedule** — otherwise an `AUTO` light with no windows
  could never be switched on at all, because the worker would drive it back OFF a minute
  later.
* **Evaluated in `Asia/Colombo`** — the database runs UTC, so an 18:00 window would
  otherwise fire at 23:30 local. Windows that wrap past midnight are handled by matching
  the small-hours half against *yesterday's* day-of-week.
* **Writes only on an actual change** — otherwise every light would emit a `usage_logs`
  row and a Realtime event every single minute.

Because pg_cron connects as a superuser it bypasses RLS, so both workers run unaffected
by the policies in §7.

### 3.5 Usage logging by trigger

`usage_logs` is written by database triggers on `devices`, `device_switches` and
`thermostats` — `log_device_status_change()`, `log_switch_status_change()`,
`log_thermostat_mode_change()` — not by client code. Consequences:

* Any change is logged exactly once, with the correct actor (`user` when
  `control_mode = 'MANUAL'`, `schedule` when `AUTO`, `safety_worker` from the cut-off).
* Lock devices log `LOCKED` / `UNLOCKED` instead of `ON` / `OFF`, resolved by looking up
  `smart_locks.mechanism`.
* No write path can forget to log, regardless of which client originated it.

### 3.6 Failure mode worth recording

Realtime requires websockets, and **Ktor's `android` engine does not support them**.
With that engine the socket never connected: REST worked, so data loaded and the app
looked healthy, but nothing was ever live and the connection indicator sat on "Offline".
The project uses `ktor-client-okhttp` for this reason. The failure is silent and easy to
reintroduce.

### 3.7 Alerts → notifications

`AlertRepository` exposes `newAlerts`, emitted **only** from the Realtime INSERT branch,
never from `refresh()` — a reconnect re-fetches the whole table, and notifying from
there would re-announce every historical alert. A `notifiedIds` set guards against the
same insert being delivered twice across a reconnect. `MainActivity` collects that flow
on `Lifecycle.State.STARTED` (not `RESUMED` — an alert arriving with the app merely
backgrounded is exactly the one worth showing) and `AlertNotifier` posts a high-priority
system notification on the `wisehome_safety_alerts` channel, respecting the API 33+
`POST_NOTIFICATIONS` permission.

---

## 4. Floor representation

### 4.1 Model

A floor is a grid; a room is a rectangle of cells on that grid; a device sits in exactly
one cell.

```
floors  (id, name, image_url, grid_cols, grid_rows)
  ├── rooms   (id, floor_id, label, x0, y0, x1, y1)     inclusive rectangle
  └── devices (id, floor_id, name, type, grid_x, grid_y, status, control_mode, …)
```

Coordinates are **integer grid-cell indices**, not pixels or normalized floats. This
matches the assignment's own wording ("abstract / simple grid mapping") and removes any
need to calibrate coordinates against the source artwork.

Devices belong to a **floor**, not to a room. Room membership is *derived* by testing
whether the device's cell falls inside a room's rectangle (`Room.contains(device)`).
Rooms can therefore be renamed, resized, moved or deleted without touching a single
device row.

Seeded layout (three floors, matching the house design in the spec):

| Floor | Grid | Rooms |
|---|---|---|
| Ground Floor | 6 × 5 | Foyer, Living Room, Kitchen, Dining Area, Guest Bedroom, Garage |
| First Floor | 6 × 5 | Master Bedroom, Bedroom 2, Study / Office, Bathroom, Balcony, Landing / Hallway |
| Exterior / Garden | 8 × 6 | Walking Gate, Driveway Gate, Front Approach, Back Garden |

Rooms were originally hardcoded in the Android client, keyed by floor *name*. That
worked for the three seeded floors and failed for anything else — a floor created at
runtime matched no name and collapsed into one unlabelled region spanning the whole
grid. Moving them into a `rooms` table (`20260817000003_rooms.sql`) is what makes "add
and manage floor plans" real; the seed reproduces the original layouts exactly, so
nothing changed visually.

### 4.2 Rendering — `FloorMap.kt`

The dashboard is **two levels deep: floor tabs → floor map + room sections**. An earlier
build inserted a room-cards screen between the tabs and the map, but a house has exactly
one of each floor, so that was a menu in front of a menu.

`FloorMap` draws, in order, inside a `BoxWithConstraints`-sized square grid
(`cell = min(64.dp, maxWidth / grid_cols)`):

1. **The floor plan image** as an underlay, resolved from `floors.image_url` by
   `floorPlanDrawable()` to a bundled drawable. Alpha is theme-dependent (0.40 light /
   0.14 dark) — the plans are drawn on light paper and at full opacity become a bright
   slab that swallows the device badges in dark theme.
2. **The abstract grid** — dashed cell boundaries across the full `grid_cols × grid_rows`.
3. **Room regions** — rounded rectangles at `(x0, y0)`–`(x1, y1)`, filled with the
   primary container colour at *alternating* strength (0.30 / 0.18), because two rooms
   sharing an edge would otherwise read as one region.
4. **Room labels** as real `Text` composables constrained to the room's own width, so
   Compose does the ellipsising and a long name can never bleed into the room next door.
   Labels are dropped entirely below a 44.dp cell, where a one-cell label is all ellipsis
   and no word — the room sections beneath the map still name every room.
5. **Device badges last**, so nothing is drawn over them: a circular badge at
   `(grid_x, grid_y)`, tinted by state tone, with a type-specific icon and a
   content description of `"<name>, <state>"`. A device sharing its row with a room label
   is aligned to the bottom of its own cell rather than being nudged out of the grid.

An unrecognised `image_url` resolves to `null` and the grid renders alone — a floor added
by the user is usable before any artwork exists for it.

Below the map, the same floor's devices are listed grouped by room (`RoomCard` +
`DeviceRow`) with inline toggles. First-match-wins assignment means a device inside two
overlapping rooms is listed once, and any device no room covers appears under
**"Not in a room"** rather than disappearing.

### 4.3 Management

* **Settings → Floor plans** (`FloorManagement.kt`) adds, renames, resizes and deletes
  floors, and adds, renames, moves and deletes the rooms on each. Room rectangles are
  validated against the floor's grid; deleting a floor warns that its rooms and devices
  cascade with it; rejected writes raise a dialog rather than being silently discarded.
* **Home → room card → Add device** (`DeviceEditor.kt`) creates a device inside a room,
  choosing its type and type-specific setup (sensor type, switch count, safety-preset
  kind, appliance label). Cells already occupied on that floor are refused, since two
  devices in one cell would hide one behind the other on the map. Creation is
  two-phase — base row, then extension row — and the base row is deleted again if the
  extension row fails, so no half-built device is left behind.
* Because `floors` and `rooms` are on Realtime, an edit appears on the dashboard, and on
  any other running client, immediately.

---

## 5. Simulator operations

`web-simulator/` is a static page — plain HTML, CSS and JavaScript with supabase-js
vendored so it works without a CDN — deployed on GitHub Pages. It needs nothing but the
Supabase project. It **represents the physical appliances**.

Live: <https://vimu-nk.github.io/wise-home-madd-assignment/web-simulator/>

### 5.1 Layout

Floor tabs → room sections → one card per device, showing the device name, its
type-specific label, a state badge, a live detail line, its controls, and a
"Simulate hardware" fault row. Room grouping reuses the same rectangle-containment rule
as the app (`vocabulary.js`: `roomsForFloor`, `roomContains`), and `vocabulary.js` also
holds the shared state-vocabulary (`deviceDisplay`, verb labels) so the two clients name
the same state the same way.

### 5.2 Controls — the inverse of the app's

| Control | Table written | Purpose |
|---|---|---|
| **Fault / Clear fault** | `devices.status` → `ERROR` / `OFF` | A state no app action can produce |
| **Disconnect / Reconnect** | `devices.status` → `DISCONNECTED` / `OFF` | Simulates loss of the device |
| **Trigger / Clear** (sensors) | `sensors.current_reading`, `last_triggered_at`, `devices.status`, `usage_logs` | Binary sensors: motion, door/window, water leak |
| **Reading slider** (sensors) | same | Analogue sensors: smoke 0–80 ppm, gas 0–60 ppm; crossing `alert_threshold` trips the device to `ON` |
| **New snapshot** | `cameras.last_snapshot_url`, `last_snapshot_at` | Rotates a camera through three deterministic stock images |
| **Turn on / off** | `devices.status` | Hardware-side actuation of outlets, appliances, lights, safety appliances |
| **Per-switch toggles** | `device_switches.status` + parent `devices.status` | Gang-box units; the parent is `ON` if any child is `ON` |
| **Lock / Unlock, Open / Close** | `devices.status`, `smart_locks.last_locked_at` / `last_unlocked_at` | Verbs adapt to the lock `mechanism` |
| **−1° / +1°** (AC units) | `ac_units.current_temp_c` | The room temperature the hardware reports |
| **Wattage slider** (metered plugs) | `power_metrics.current_watts`, `last_reading_at` | Live consumption |
| **Simulate toggle** | sensors + cameras | A 10 s timer that drifts one random sensor reading and, 30 % of the time, refreshes a random camera |

Thermostats deliberately expose no hardware control — a thermostat is a control panel,
driven from the app; the card says so.

### 5.3 Sync behaviour

Everything the simulator does is a plain table write. It subscribes to one channel
(`simulator-all`) carrying `postgres_changes` for `devices`, `device_switches`,
`sensors`, `cameras`, `smart_locks`, `thermostats`, `ac_units` and `power_metrics`, and
re-runs `loadAll()` on any change — so it also reflects app-driven changes. Toggling a
light on the phone updates the simulator without a refresh, and vice versa. Two
independent clients with no direct connection, both converging on the database, is the
clearest demonstration of bidirectional sync.

The connection indicator tracks the subscription callback: `SUBSCRIBED` → "Live ·
connected", `CHANNEL_ERROR` / `TIMED_OUT` → "Reconnecting…", `CLOSED` → "Offline".

### 5.4 Failing loudly

Setup problems replace the page content with the actual cause rather than leaving the
placeholder up: a missing supabase-js, a missing `config.js`, a missing
`vocabulary.js` / `timeformat.js` (usually a cached page), a query error, or a load that
never settles — `withTimeout()` rejects after 15 s so a hang surfaces instead of looking
idle. A silent no-op is the hardest kind of bug to diagnose in a live demo.

### 5.5 Why the simulator matters for grading

It is the only way to exercise `ERROR` and `DISCONNECTED`, and it is what makes the
safety cut-off testable end to end: switch an iron on **in the simulator**, watch the
app's countdown arm itself (the trigger stamped `turned_on_at`), and watch both clients
flip to `OFF` plus an alert and a notification appear when `run_safety_cutoff()` fires.

---

## 6. Data model

One base `devices` table plus one extension table per device type (1:1, except
`device_switches` which is 1:many), and three cross-cutting tables.

**Base:** `devices(id, floor_id, name, type, grid_x, grid_y, status, control_mode,
appliance_type, created_at, updated_at)` with `status ∈ {ON, OFF, ERROR, DISCONNECTED}`
and `control_mode ∈ {MANUAL, AUTO}` (default `MANUAL`).

| # | `type` | Extension table | Key fields |
|---|---|---|---|
| 1 | `outlet` | — | binary ON/OFF |
| 2 | `multiswitch` | `device_switches` | `switch_index`, `label`, `status` (1:many) |
| 3 | `scheduled_safety` | `safety_configs` (+ `safety_presets`) | `kind`, `max_on_duration_seconds`, `turned_on_at`, `last_auto_cutoff_at` |
| 4 | `scheduled_light` | `light_schedules` | `start_time`, `end_time`, `days_of_week[]`, `enabled` |
| 5 | `camera` | `cameras` | `mock_stream_uri`, `last_snapshot_url`, `last_snapshot_at` |
| 6 | `thermostat` | `thermostats` | `target_temp_c`, `mode`, `controls_device_id` → an `ac_unit` |
| 7 | `ac_unit` | `ac_units` | `fan_speed`, `current_temp_c` (reported by the simulator) |
| 8 | `smart_lock` | `smart_locks` | `mechanism ∈ {deadbolt, sliding_gate, turnstile}`, `auto_relock_after_seconds`, lock/unlock timestamps |
| 9 | `sensor` | `sensors` | `sensor_type ∈ {motion, door_window, smoke, gas, water_leak}`, `current_reading`, `unit`, `alert_threshold`, `last_triggered_at` |
| 10 | `smart_plug_metered` | `power_metrics` | `current_watts`, `energy_kwh_total`, `last_reading_at` |
| 11 | `appliance` | — | `devices.appliance_type` free label (TV, fridge, washer, microwave, exhaust fan…) |

**Cross-cutting:** `floors`, `rooms`, `usage_logs`
(`event_type ∈ {ON, OFF, ERROR, AUTO_CUTOFF, LOCKED, UNLOCKED, SENSOR_TRIGGERED, MODE_CHANGE}`,
`triggered_by ∈ {user, schedule, safety_worker}`), `alerts` (`message`, `acknowledged`).

**Design notes.** `safety_presets` holds the per-appliance duration options (iron 15 min,
hair dryer 10 min, space heater 2 h, water heater 45 min) **in the database** rather than
the client, so the seeded caps and the options the app offers cannot drift apart. A
thermostat and its AC unit stay two rows — presented as one climate card in the UI — so
the AC can report its own `ERROR` / `DISCONNECTED` independently of the panel.

Migrations, applied in filename order:

| File | Contents |
|---|---|
| `20260815000001_init_schema.sql` | All tables, indexes, `run_safety_cutoff()`, `safety-cutoff-check` cron job |
| `20260815000002_seed_data.sql` | 3 floors and ~30 devices across all 11 types |
| `20260816000001_enable_realtime.sql` | Adds 11 tables to the `supabase_realtime` publication |
| `20260816000002_usage_log_triggers.sql` | `usage_logs` triggers on devices / switches / thermostats |
| `20260817000001_light_schedule_worker.sql` | `run_light_schedules()` + `light-schedule-check` cron job |
| `20260817000002_safety_arm_and_presets.sql` | `trg_devices_safety_arm`, `safety_presets`, three more hazard appliances |
| `20260817000003_rooms.sql` | `rooms` table, seeded layouts, Realtime for `floors` / `rooms` / `light_schedules` / `safety_presets` |
| `20260817000004_rls.sql` | Row-level security policies |

---

## 7. Security posture

There is no login by design (one shared home), so RLS is permissive **on purpose** —
what it buys is a bounded blast radius. The publishable (anon) key ships inside a public
APK *and* a public GitHub Pages site, so it must be assumed known to everyone.

* **SELECT** — granted to `anon` on all 15 application tables.
* **INSERT / UPDATE** — granted on the 14 tables the app or simulator actually writes.
  `safety_presets` is deliberately excluded: reference data, read-only to clients.
* **DELETE** — granted on only `floors`, `rooms`, `devices` and `light_schedules`, the
  four the management UI needs. Before this, one crafted request could have emptied the
  demo database.
* The `service_role` key is never used by either client and is not in the repository.

---

## 8. Requirement coverage

Requirements as stated in the project guide, mapped to the code that satisfies them.

| # | Requirement | Where it lives | Status |
|---|---|---|---|
| 1 | Multi-floor dashboard with abstract grid overlay on floor plan images | `HomeScreen.kt`, `FloorMap.kt`, `FloorPlanImage.kt`; `floors` / `rooms` tables | ✅ |
| 2 | Add & manage floor plans | Settings → Floor plans (`FloorManagement.kt`), `FloorRepository`, `RoomRepository` | ✅ |
| 3 | Reactive device state: ON / OFF / ERROR / DISCONNECTED | `DeviceStatus`; every screen driven by `StateFlow`; faults injected only by the simulator | ✅ |
| 4 | Electrical outlets (simple binary) | `outlet`, plus `smart_plug_metered` with live wattage | ✅ |
| 5 | Multi-switch gang-box units (2/3/5 switches, one entity) | `device_switches`, individually addressable; `SwitchRepository`; parent status derived from children | ✅ |
| 6 | Safety-critical scheduled devices with `max_on_duration` | `safety_configs` + `safety_presets`; countdown in the detail sheet | ✅ |
| 7 | Auto on/off scheduled light bulbs (preset windows) | `light_schedules` + `run_light_schedules()`; editor in `ScheduleControls.kt`; MANUAL override via `control_mode` | ✅ |
| 8 | Security cameras (mock snapshot / stream URIs) | `cameras`; `CameraSnapshot.kt` renders via Coil; rotated by the simulator | ✅ |
| 9 | Bidirectional realtime sync, no manual refresh | Realtime subscriptions in both clients; optimistic-write reconciliation in the app (§3) | ✅ |
| 10 | Server-side safety cut-off + pushed alert | `run_safety_cutoff()` (pg_cron, 15 s) → `devices` + `usage_logs` + `alerts` → `AlertNotifier` system notification | ✅ |
| 11 | Usage reporting / tracking | `usage_logs` written by triggers; per-device live history in the device detail sheet | ✅ |
| 12 | Companion web hardware simulator that listens to the DB and reflects state | `web-simulator/`, GitHub Pages (§5) | ✅ |
| 13 | Source code on GitHub + APK link | Monorepo + GitHub Releases (`./gradlew assembleDebug`) | ✅ |
| 14 | Technical documentation (sync, floor representation, simulator) | This document + `technical-report.md` | ✅ |

**One deliberate deviation from the original plan.** The spec sketched four bottom-nav
tabs (Home / Alerts / Reports / Settings). Shipped navigation is three — Home, Alerts,
Settings — with usage reporting delivered **per device inside the detail sheet** rather
than as a standalone tab. Reporting is per-device by nature, and a separate tab would
have opened with a device picker duplicating the dashboard the user just left. The
underlying data (`usage_logs`) and the live query (`UsageRepository.observeUsageForDevice`)
are unchanged, so a Reports tab remains a UI-only addition if it is wanted later.

---

## 9. How the work was carried out

### 9.1 Process

Planning was done first and written down: `docs/smart-home-project-spec.md` evolved
through eight revisions until no decision was left open — tech stack, the 11 device
types, the house layout room by room, the grid coordinate system, the camera mock
strategy, the Realtime subscription strategy and the simulator's update model. Only then
was code written. That document doubled as the brief handed to the coding sessions,
which is why the implementation matches it almost line for line.

### 9.2 Build order

Bottom-up, foundation first, so that nothing was ever built on something unproven:

1. **Monorepo scaffold** — `android-app/`, `web-simulator/`, `supabase/`, `docs/`.
2. **Schema and seed** — all tables, the safety cut-off worker, then ~30 seeded devices
   covering all 11 types across three floors, so every later screen had real data.
3. **Android data layer first** — the Supabase client, then one repository per table.
   Repositories are what everything else depends on, so they were proven with plain REST
   before any UI existed.
4. **Home screen** — floor tabs, grid-overlaid floor plan, device badges by grid cell,
   tap-to-open detail sheet.
5. **Device detail controls by type**, simplest first: outlet/appliance toggle → the
   multiswitch child list → the iron countdown → the combined thermostat + AC climate
   card.
6. **Alerts, then usage history**, off the same repositories.
7. **The web simulator** — manual per-device controls first, the "Simulate" timer last.
8. **Realtime wired last**, once manual CRUD worked end to end in both clients — three
   shared channels, then the scoped ones.
9. **Server-side rules hardened** — the light-schedule worker, the safety-arming trigger,
   the usage-log triggers, the `rooms` table, then RLS.
10. **UI consolidation** — the room-cards level was removed in favour of a direct floor
    map, and `DeviceEditor` added so devices can be created and moved from the app.

Sequencing Realtime last was the single most useful decision: it meant every live-update
bug could be attributed to the subscription layer, because the CRUD underneath was
already known good.

### 9.3 Recurring principle — push correctness into the database

Four things moved out of the client during development, each because a hole appeared
that a client-side implementation could not close:

| Moved | From | Why |
|---|---|---|
| Safety-timer arming | Kotlin client | An iron switched on from the simulator never armed the timer |
| Usage logging | Kotlin client | History was empty for every device the client didn't explicitly log |
| Room rectangles | Hardcoded in the UI, keyed by floor name | A floor created at runtime had no rooms |
| Duration presets | Kotlin constants | Seeded caps and offered options could drift apart |

The general form: if a rule must hold regardless of which client acted, it belongs in
Postgres.

### 9.4 Verification

Each behaviour was exercised from **both** clients, since single-client testing hides
exactly the bugs this architecture is meant to prevent:

* Toggle in the app → observe the simulator update without a refresh, and the reverse.
* Fault / disconnect in the simulator → observe the app's badge tone and state label.
* Switch an iron on **in the simulator** → the app's countdown arms → at expiry both
  clients flip to `OFF`, an alert row appears in the Alerts tab, and a system
  notification is posted.
* Put a scheduled light in `AUTO` with a window covering "now" → the worker switches it
  within a minute; switch to `MANUAL` → the worker leaves it alone.
* Kill and restore connectivity → `startConnectionWatch()` re-reads everything on
  reconnect; background and resume → `refreshAll()` on `RESUMED`.
* Add / rename / resize / delete a floor and its rooms in Settings → the dashboard and
  any second running client update immediately.

### 9.5 Known limitations

* **Notifications require a live process.** `AlertNotifier` posts while the app process
  is alive; a killed app would need FCM, which this project deliberately does not take
  on.
* **Supabase free tier auto-pauses after 7 days of inactivity** — unpause from the
  dashboard before a demo.
* **Camera snapshots are stock images** cycled by the simulator, not video. The app only
  renders whatever `last_snapshot_url` currently holds, exactly as specified.
* **No authentication.** One shared home by design; RLS bounds what the public key can
  do (§7).

---

## 10. Engineering decisions, summarized

* **Rules in the database, not the client.** A client can crash, be uninstalled or be
  offline; the cut-off still fires.
* **Triggers over client-side bookkeeping.** Correctness does not depend on every writer
  remembering.
* **One subscription per shared table**, owned by repository singletons — no duplicate
  caches, no duplicate channels.
* **Optimistic UI with reconciliation and rollback**, rather than blocking on the network
  or trusting the local guess.
* **Derived room membership** from grid rectangles, so rooms are editable without
  touching device rows.
* **Row-level security** for a key that is, by construction, public.
* **Fail loudly.** Setup problems in the simulator replace the page with the real cause;
  rejected writes in the app raise a dialog. A silent no-op is the hardest kind of bug to
  diagnose in a demo.

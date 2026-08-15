# WiseHome — Technical Report

SCS 3311 Mobile Application Design & Development — Mini-Project
Smart Home Monitoring & Control System

---

## 1. System overview

WiseHome has three parts and one source of truth.

```
   Android client  ─────┐                    ┌─────  Web hardware simulator
   (Kotlin/Compose)     │                    │       (HTML + supabase-js)
                        ▼                    ▼
                 ┌──────────────────────────────────┐
                 │  Supabase / PostgreSQL           │
                 │  · tables + RLS                  │
                 │  · triggers (usage log, safety)  │
                 │  · pg_cron workers               │
                 │  · Realtime (WAL → websocket)    │
                 └──────────────────────────────────┘
```

Neither client talks to the other. Both read and write the same Postgres database, and
both subscribe to Supabase Realtime, so a change made anywhere is pushed to everyone
else. Rules that protect life and property — the maximum on-duration cut-off, the
lighting schedules — run inside the database, not in either client, so they hold even
when no app is open.

The division of responsibility is deliberate:

* The **app** expresses intent ("turn the iron on", "this light should be on from 18:00").
* The **database** decides and records what actually happened.
* The **simulator** reports what the hardware is doing, and is the only source of the
  `ERROR` and `DISCONNECTED` states — no amount of tapping in the app can produce them,
  exactly as with real hardware.

---

## 2. Synchronising mechanism

### 2.1 Write path — optimistic, then reconciled

A toggle in the app does not wait for the network before redrawing. `DeviceRepository`
applies the change to its in-memory `StateFlow` immediately, sends the `UPDATE`, then
replaces the local row with the authoritative row returned by Postgres. If the write
fails, the optimistic change is rolled back and an error is surfaced to the UI.

```
tap → local state updated → PATCH /rest/v1/devices → row returned → local row replaced
                                     │
                                     └── failure → rollback + error message
```

This matters because the same row is also being changed by other writers. Trusting the
returned row rather than the local guess means a value the server adjusted (or a
concurrent change from the simulator) wins, instead of being papered over.

### 2.2 Read path — Realtime

Every table the clients care about is in the `supabase_realtime` publication
(`migrations/20260816000001_enable_realtime.sql`, extended in `20260817000003`). Postgres
streams changes from the WAL, Supabase fans them out over a websocket, and the clients
apply them.

The app keeps **one** subscription per shared table, owned by a repository singleton in
`RepositoryProvider`. An earlier version let each ViewModel construct its own repository
via default constructor arguments, which produced two `DeviceRepository` instances, two
caches, and two channels with the same topic — optimistic updates on one screen were
invisible on another. Sharing the instances fixed the class of bug rather than the
instance.

Detail sheets additionally open **scoped** subscriptions (`DeviceExtrasRepository.watchTable`)
for the extension table of the device being viewed, and close them on dismissal, so the
socket carries only what is on screen.

Three details worth noting:

1. **The subscribe/fetch race.** Every watcher fetches once immediately, again once the
   subscription is confirmed live, and then on each change. Without the second fetch, a
   change occurring between the initial read and the subscription becoming active is
   lost until the next user action.
2. **Reconnect handling.** `RepositoryProvider.startConnectionWatch()` opens the socket
   explicitly at startup rather than relying on connect-on-first-subscribe, and re-reads
   everything whenever the status returns to `CONNECTED`. The app also refreshes on
   resume, so state cannot be left stale by anything that happened while it was
   backgrounded.
3. **The websocket engine.** Realtime needs websockets, and Ktor's `android` engine does
   not support them. With that engine the socket never connected: REST worked, so data
   loaded and the app looked healthy, but nothing was ever live and the connection
   indicator sat on "Offline". The project uses `ktor-client-okhttp` for this reason —
   recorded here because the failure is silent and easy to reintroduce.

### 2.3 Server-side rules

Two `pg_cron` jobs run against the database itself:

| Job | Interval | What it does |
|---|---|---|
| `safety-cutoff-check` | 15s | Finds `ON` devices whose `turned_on_at` is older than their `max_on_duration_seconds`, sets them `OFF`, writes an `AUTO_CUTOFF` usage log and an `alerts` row |
| `light-schedule-check` | 1 min | Switches `scheduled_light` devices in `AUTO` mode on/off according to `light_schedules` |

The safety timer is armed by a trigger on `devices.status`, not by the app. Originally
the app stamped `turned_on_at` itself, which left a hole: an iron switched on from the
simulator — that is, from the hardware side — never armed the timer and would have
stayed on indefinitely. Moving it into `trg_devices_safety_arm` covers every writer:
app, simulator, cron, or SQL editor.

The light worker evaluates times in **Asia/Colombo** rather than UTC (the database
runs UTC, so an 18:00 window would otherwise fire at 23:30 local), handles windows that
cross midnight, ignores devices in `ERROR`/`DISCONNECTED` so it cannot erase a fault the
hardware is reporting, and writes only when the status actually changes — otherwise
every light would emit a log row and a Realtime event every minute.

`control_mode` is the override: `AUTO` means the schedule drives the device, `MANUAL`
means the user has taken over and the worker leaves it alone.

### 2.4 Usage logging

Usage history is written by database triggers on `devices`, `device_switches` and
`thermostats` rather than by client code. Any change is logged once, with the correct
actor (`user`, `schedule`, `safety_worker`), no matter which client caused it — and no
write path can forget to log.

---

## 3. Floor representation

### 3.1 Model

A floor is a grid; a room is a rectangle of cells on it; a device sits in one cell.

```
floors (id, name, image_url, grid_cols, grid_rows)
  └── rooms   (id, floor_id, label, x0, y0, x1, y1)      inclusive rectangle
  └── devices (id, floor_id, name, type, grid_x, grid_y, status, control_mode, …)
```

Devices belong to a *floor*, not to a room: room membership is derived by testing
whether the device's cell falls inside a room's rectangle (`Room.contains`). Rooms can
therefore be renamed, resized or deleted without touching a single device row.

Rooms were originally hardcoded in the Android client, keyed by floor name. That worked
for the three seeded floors and failed for anything else — a floor created at runtime
matched no name and collapsed into one unlabelled room spanning the whole grid. Moving
them into a `rooms` table is what makes "add and manage floor plans" real; the seed
reproduces the original layouts exactly, so nothing changed visually.

### 3.2 Rendering

The dashboard is three levels deep: **floor tabs → room cards → room map**. The room map
(`RoomDetailView.RoomMap`) draws the abstract grid the specification asks for — dashed
cell boundaries, one tappable badge per device positioned at its `grid_x/grid_y`, tinted
by state.

Behind the grid sits the floor plan image named by `floors.image_url`, resolved to a
bundled drawable by `floorPlanDrawable()`. Because the plans are generated from the same
room rectangles, the artwork can be **cropped to the room being viewed** (the plan's
cell size and padding are known constants), so the drawing lines up with the grid rather
than floating behind it. An unrecognised `image_url` resolves to `null` and the grid
renders alone — a floor added by the user is usable before any artwork exists for it.

### 3.3 Management

Settings → Floor plans supports adding, renaming, resizing and deleting floors, and
adding, renaming, moving and deleting the rooms on each. Room rectangles are validated
against the floor's grid, deleting a floor warns that its rooms and devices cascade with
it, and rejected writes are reported rather than silently discarded. Because `floors` and
`rooms` are on Realtime, an edit appears on the dashboard — and on any other running
client — immediately.

---

## 4. Hardware simulator

`web-simulator/` is a static page (plain HTML, CSS and JavaScript, with supabase-js
vendored so it works without a CDN). It represents the physical appliances. It is
deployed on GitHub Pages and needs nothing but the Supabase project.

Its controls are deliberately the **inverse** of the app's. The app asks the house to do
something; the simulator reports what the hardware is doing:

| Control | Purpose |
|---|---|
| Fault / Clear fault | Puts a device into `ERROR` — a state no app action can produce |
| Disconnect / Reconnect | Puts a device into `DISCONNECTED` |
| Trigger / Clear, sliders | Drives sensor readings; crossing `alert_threshold` trips the sensor |
| New snapshot | Rotates a camera's `last_snapshot_url` |
| Switch / lock / plug controls | Hardware-side actuation, independent of the app |
| Simulate toggle | Periodic sensor drift and camera refreshes, so the demo moves on its own |

Everything it does is a plain table write. It subscribes to the same tables as the app
and re-renders on any change, so it also reflects app-driven changes: toggling a light on
the phone updates the simulator without a refresh, and vice versa. This is the clearest
demonstration of bidirectional sync — two independent clients, no direct connection,
both converging on the database.

The simulator is also what makes the safety cut-off testable end to end: switch an iron
on there, watch the app's countdown arm itself, and watch both clients flip to `OFF`
when the worker fires.

---

## 5. Requirement coverage

| Requirement | Where it lives |
|---|---|
| Multi-floor dashboard, grid overlay | `HomeScreen`, `RoomListView`, `RoomMap`; `floors`/`rooms` tables |
| Add & manage floor plans | Settings → Floor plans (`FloorManagement.kt`) |
| ON / OFF / ERROR / DISCONNECTED | `DeviceStatus`; faults injected only by the simulator |
| Electrical outlets | `outlet`, `smart_plug_metered` (with live wattage) |
| Multi-switch gang boxes | `device_switches`, individually addressable under one device |
| Safety-critical scheduling | `safety_configs` + `safety_presets`, `run_safety_cutoff()`, `trg_devices_safety_arm` |
| Scheduled lights | `light_schedules` + `run_light_schedules()`, editor in the device sheet |
| Security cameras | `cameras`, snapshots rendered in-app (Coil) and rotated by the simulator |
| Bidirectional sync | Realtime subscriptions in both clients; optimistic writes in the app |
| Server-side cut-off + alert | pg_cron worker → `devices`, `usage_logs`, `alerts` → system notification |
| Reporting | `usage_logs` written by triggers; per-device history in the detail sheet |
| Hardware simulator | `web-simulator/`, deployed on GitHub Pages |

---

## 6. Notable engineering decisions

* **Rules in the database, not the client.** Anything safety-related runs in Postgres.
  A client can crash, be uninstalled or be offline; the cut-off still fires.
* **Triggers over client-side bookkeeping.** Usage logs and the safety timer are
  maintained by triggers, so correctness does not depend on every writer remembering.
* **One subscription per shared table.** Shared repository singletons prevent duplicate
  caches and duplicate channels.
* **Optimistic UI with reconciliation and rollback**, rather than either blocking on the
  network or trusting the local guess.
* **Row-level security** with anonymous read plus scoped write, because the publishable
  key ships inside a public APK and a public web page and must be assumed known.
* **Fail loudly.** Setup problems in the simulator replace the page content with the
  actual cause; rejected writes in the app raise a dialog. A silent no-op is the hardest
  kind of bug to diagnose in a demo.

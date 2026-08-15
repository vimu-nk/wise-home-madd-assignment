# Smart Home Monitoring & Control System — Project Spec & Decisions

> Living document. Updated as we finalize decisions during planning.
> Intended to be handed to Claude Code as project context.

## 1. Assignment Summary

Module: SCS 3311 — Mobile Application Design & Development
Deliverable: Mobile app + cloud backend + companion web-based hardware simulator.

**Core features required:**
- Multi-floor dashboard with abstract grid overlay on floor plan images
- Device control UI, reactive state updates: ON / OFF / ERROR / DISCONNECTED
- Heterogeneous device types:
  - Electrical outlets (simple binary on/off)
  - Multi-switch gang-box units (2/3/5 switches under one entity)
  - Safety-critical scheduled devices (e.g. irons) with `max_on_duration`
  - Auto on/off scheduled light bulbs (preset time windows)
  - Security cameras (mock snapshot/stream URIs)
- Bidirectional realtime sync (app <-> cloud DB, no manual refresh)
- Server-side safety cutoff: backend worker/listener auto-flips device to OFF + pushes alert if `max_on_duration` breached
- Usage reporting/tracking for key devices
- Companion web-based Hardware Simulator: listens to DB, visually reflects state

**Deliverables:**
1. Source code on GitHub + link to final APK
2. Technical documentation (sync mechanism, floor representation, simulator operation)
3. Demo video, all 3 members present, ≤25 minutes

## 2. Tech Stack (Decided)

| Layer | Choice | Rationale |
|---|---|---|
| Mobile client | **Kotlin + Jetpack Compose** (native Android) | Team already has Kotlin/Android Studio set up; no cross-platform need since only APK is required; Compose fits reactive device-state UI well |
| Database / Sync | **Supabase (Postgres) + Realtime** | No credit card required (unlike Firebase Cloud Functions post-Feb 2026); relational model fits heterogeneous device types cleanly; Realtime subscriptions give bidirectional sync |
| Safety-cutoff worker | **pg_cron + Postgres function**, inside Supabase | Runs as a scheduled SQL job directly in the DB — zero external hosting/deploy step, fewer moving parts than a standalone Node service |
| Hardware Simulator | **Plain HTML/JS + Supabase JS client**, hosted on GitHub Pages | Lightweight, no build tooling, listens to same DB in real time via Realtime channels |
| Auth | Skip for v1 — single shared home (see section 3) | Simplifies schema; add Supabase Auth later if multi-user needed |

### Rejected options
- **Flutter**: cross-platform benefit not needed (APK-only deliverable); would require new tooling setup vs. team's existing Kotlin environment.
- **Firebase (Firestore + Cloud Functions)**: Cloud Functions now require the Blaze (pay-as-you-go) plan, meaning a linked credit card even for $0 usage. Supabase's free tier needs no card and pg_cron avoids a separate deployable worker entirely.
- **Custom self-hosted backend (Node/Express)**: more infra to deploy/maintain vs. pg_cron for a time-boxed mini-project.

### Caveat to remember
Supabase free-tier projects auto-pause after 7 days of inactivity (manual unpause from dashboard). Not an issue during active development — just don't let it sit idle for a week right before the deadline/demo.

## 3. Decisions Resolved
- **Single shared home, no auth.** One home, no multi-user/multi-tenant layer. Simplifies schema (no `homes`/`users` tables).
- **Repo structure: monorepo.** See section 6.
- **Team task split:** out of scope for this doc (handled separately).
- **Grid coordinate system:** integer grid cells, not pixel/normalized coordinates. Each floor has `grid_cols`/`grid_rows`; a device's `grid_x`/`grid_y` is its cell index. The app overlays the floor image with an evenly-divided `grid_cols × grid_rows` grid and renders each device icon centered in its cell. This matches the assignment's own wording ("abstract/simple grid mapping") and is far less fiddly to implement than pixel-precise placement — no coordinate calibration against the source image needed.
- **Camera mock strategy:** static images, not real video. Each `camera` device cycles through 2–3 pre-picked stock photos (matching its location — gate, garden, front door) on a timer in the **web simulator** (e.g. every 30s), which updates `cameras.last_snapshot_url` and `last_snapshot_at`. The Kotlin app just displays whatever `last_snapshot_url` currently is — it never needs real streaming. Gives the illusion of a live camera without any video infrastructure.
- **Realtime subscription strategy:** three Realtime channels total, opened once at app start and shared app-wide (not re-subscribed per screen): one on `devices`, one on `device_switches`, one on `alerts`. Each channel's Postgres-changes stream feeds into its repository's `Flow`; ViewModels `collect` and filter client-side (e.g. `HomeViewModel` filters devices by the currently-selected `floor_id`). `usage_logs` is read on-demand (paginated query) for the Reports tab, not subscribed — history doesn't need to be realtime.
- **Simulator update strategy:** manual by default, with an optional "simulate" toggle. The web simulator's primary mode is manual: buttons per device to flip state, adjust sensor readings, etc. — deterministic and demo-friendly. A single global "Simulate" toggle turns on a lightweight timer that randomly perturbs sensor readings (temperature drift, occasional motion triggers) every ~10s, purely for showing the system feels "alive" in the demo video; turn it off for controlled testing/screenshots.

## 4. Data Model (Finalized)

Relational schema on Supabase Postgres. Full DDL lives in `schema.sql` in the repo. Pattern: one base `devices` table (common fields: name, position, status) + one type-specific extension table per device type (1:1, except `device_switches` which is 1:many for multi-switch gang-boxes).

**Device types (11):**

| Type | Extension table | Key fields |
|---|---|---|
| `outlet` | — (base table only) | simple ON/OFF |
| `multiswitch` | `device_switches` | per-switch index, label, status |
| `scheduled_safety` (irons, etc.) | `safety_configs` | `max_on_duration_seconds`, `turned_on_at` |
| `scheduled_light` | `light_schedules` | daily time window, days of week; supports MANUAL override |
| `camera` | `cameras` | mock stream URI, last snapshot |
| `thermostat` | `thermostats` | target temp, mode, `controls_device_id` → linked `ac_unit` |
| `ac_unit` | `ac_units` | fan speed, current temp (reported by simulator); ON/OFF driven by its thermostat or manually |
| `smart_lock` | `smart_locks` | locked/unlocked timestamps, auto-relock |
| `sensor` (motion/door/smoke/gas/leak) | `sensors` | sensor_type, current_reading, alert_threshold |
| `smart_plug_metered` | `power_metrics` | current_watts, energy_kwh_total |
| `appliance` (TV, fridge, washer, microwave, exhaust fan, water heater, etc.) | — (base table only) | `devices.appliance_type` free label |

**Manual vs. auto control:** every device has `devices.control_mode` (`MANUAL` / `AUTO`, default `MANUAL`). For a `scheduled_light`, `AUTO` means `light_schedules` drives `status`; flipping the app switch to `MANUAL` pauses the schedule and gives the user direct on/off control until they switch it back. Same mechanism covers thermostats later if auto climate control is added.

**Thermostat ↔ AC linkage:** a `thermostat` device doesn't have its own compressor — it's a control panel. `thermostats.controls_device_id` points at the `ac_unit` device it drives. The app shows them as one logical "climate control" card (thermostat's target temp + mode, AC unit's fan speed + actual current temp from the simulator), but they remain two separate rows so the AC unit can report its own `status`/`ERROR`/`DISCONNECTED` independently of the thermostat panel.

**Cross-cutting tables:**
- `floors` — one row per floor plan (image + grid dimensions), `devices.floor_id` links each device to a floor and grid position.
- `usage_logs` — event history per device (ON/OFF/ERROR/AUTO_CUTOFF/LOCKED/UNLOCKED/SENSOR_TRIGGERED/MODE_CHANGE), powers the Reporting requirement.
- `alerts` — pushed by the safety-cutoff worker (and can be extended to sensor-triggered alerts).

**Safety-cutoff mechanism:** `pg_cron` runs `run_safety_cutoff()` every 15s — scans `safety_configs` for devices where `now() - turned_on_at > max_on_duration_seconds`, flips `devices.status` to `OFF`, logs an `AUTO_CUTOFF` usage event, and inserts an alert. Entirely inside Postgres — no external worker to deploy.

**Sync mechanism:** Supabase Realtime subscriptions on `devices` (and `device_switches`) push row-level changes to both the Kotlin app and the web simulator — no polling.

## 5. House Layout — 2 Floors + Exterior/Garden Zone

The house sits inside a garden enclosed by a boundary wall, with two entrances: a **walking gate** (pedestrian) and a **driveway gate** (vehicle). Model the exterior as a third `floors` row ("Exterior / Garden") — same table, just a different grid/image, since it's really just another zone with its own device layout. Grid suggestion: 6×5 per floor, 8×6 for the garden zone (wider, to cover the boundary).

**Camera and lock rule:** cameras are exterior-only (walking gate, driveway gate, front approach, back garden — 4 total). No cameras inside the house. Locks cover three access points: front door, walking gate, driveway gate.

**Every device defaults to `control_mode = MANUAL`** — the whole house is manually controllable from the app by default. A subset of devices (scheduled lights, garden lights, the safety-capped iron's auto-cutoff, thermostats if you later add auto climate scheduling) can be switched to `AUTO` per-device; flipping back to `MANUAL` always hands control back to the user. This satisfies "manual control everywhere, automatic where it makes sense" without needing two separate schemas.

### Ground Floor
| Room | Devices |
|---|---|
| Foyer | `smart_lock` (front door) |
| Living Room | `ac_unit` + `thermostat` (linked), `appliance` (TV), `sensor` (motion) |
| Kitchen | `sensor` (smoke), `appliance` (fridge, microwave) |
| Dining Area | `multiswitch` (lights) |
| Guest Bedroom | `ac_unit` + `thermostat` (linked), `scheduled_light`, `outlet` |
| Garage | `sensor` (door/window), `appliance` (washing machine), `outlet` |

### First Floor
| Room | Devices |
|---|---|
| Master Bedroom | `ac_unit` + `thermostat` (linked), `scheduled_safety` (iron), `smart_plug_metered` |
| Bedroom 2 | `ac_unit` + `thermostat` (linked), `scheduled_light`, `outlet` |
| Bathroom | `appliance` (exhaust fan), `sensor` (water leak) |
| Study / Office | `ac_unit` + `thermostat` (linked), `multiswitch`, `smart_plug_metered` |
| Balcony | `sensor` (door/window), `scheduled_light` (outdoor) |
| Landing / Hallway | `sensor` (motion), `scheduled_light` |

### Exterior / Garden Zone
Both gates sit at the **front** of the property (street side); the back garden camera is on the opposite side.

| Location | Devices |
|---|---|
| Walking gate (front, pedestrian) | `smart_lock` (`mechanism='turnstile'`), `camera` |
| Driveway gate (front, vehicle) | `smart_lock` (`mechanism='sliding_gate'`), `camera` |
| Front approach (between gates and house) | `camera`, `scheduled_light` (path lighting, `AUTO` at dusk) |
| Back garden (opposite side) | `camera`, `sensor` (motion) |
| Front door | `smart_lock` (`mechanism='deadbolt'`) |

`smart_locks.mechanism` distinguishes the three access points so the app can show the right control (deadbolt = simple lock/unlock toggle; sliding_gate = motorized open/close, `ON`=closed, `OFF`=open; turnstile = lock/unlock rotation, `ON`=locked, `OFF`=free to rotate).

This puts every device type to real use: AC+thermostat pairs in every bedroom and the study (all manually overrideable), a metered plug next to the safety-capped iron for a clean reporting contrast, three access-controlled entry points, four perimeter cameras with zero interior surveillance, and auto-scheduled garden lighting alongside fully manual interior lighting.

## 6. Repo Structure (Monorepo)

```
smart-home-project/
├── android-app/          # Kotlin + Jetpack Compose client
├── web-simulator/         # Plain HTML/JS + Supabase JS client
├── supabase/
│   ├── schema.sql          # full DDL (floors, devices, extension tables, pg_cron job)
│   └── seed.sql             # sample data for demo (optional)
├── docs/                    # technical documentation, diagrams, ERD export
└── README.md
```

## 7. App Design

### Navigation
Bottom nav with 4 tabs: **Home**, **Alerts**, **Reports**, **Settings**.

- **Home** — floor tabs (Ground / First / Exterior-Garden) → grid-overlaid floor plan image → tap a device icon → device detail bottom sheet with type-specific manual controls (every device is manually controllable by default; a device in `AUTO` mode shows its schedule with a "switch to manual" toggle).
- **Alerts** — list of `alerts` rows (safety cutoffs, sensor triggers), newest first, tap to acknowledge.
- **Reports** — per-device usage charts from `usage_logs` (on/off history, energy totals for metered devices).
- **Settings** — simulator connection info, about/credits.

Primary path: launch → Home dashboard → tap device → detail sheet → action writes to Supabase → Realtime pushes the update back out to every open screen (dashboard grid icon, alerts list, reports) with no manual refresh.

### Device detail controls (by type)
| Type | Control shown |
|---|---|
| `outlet`, `appliance`, `smart_plug_metered` | Simple ON/OFF toggle (+ live wattage for metered plugs) |
| `multiswitch` | List of child switches, each with its own toggle |
| `scheduled_safety` (iron) | ON/OFF toggle + remaining time until auto-cutoff (from `max_on_duration_seconds` − elapsed) |
| `scheduled_light` | ON/OFF toggle + MANUAL/AUTO switch + schedule editor (time window, days) |
| `thermostat` + `ac_unit` | One combined "climate" card: target temp stepper, mode selector, linked AC's fan speed + current temp |
| `smart_lock` | Lock/unlock control, adapted to `mechanism` (deadbolt = toggle, sliding_gate = open/close, turnstile = lock/free) |
| `sensor` | Read-only current reading + last-triggered timestamp (no manual control — sensors report, they don't get toggled) |
| `camera` | Snapshot/mock stream viewer |

### Compose architecture
Standard MVVM layering: **Presentation** (Compose screens + one ViewModel per screen, exposing `StateFlow`, navigated with Navigation Compose) talks to a **Data layer** (a small set of repositories — `DeviceRepository`, `AlertRepository`, `UsageRepository` — wrapping the Supabase Kotlin client). The data layer holds the single Realtime subscription per table and exposes it as a `Flow`; ViewModels collect that flow and map it into UI state. Supabase itself (Postgres + Realtime + the `pg_cron` safety worker) sits behind the data layer — the app never talks to Postgres directly, only through the repositories.

Practical implication for Claude Code: scaffold one `Repository` per major table (`devices`, `alerts`, `usage_logs`), one `ViewModel` per screen (`HomeViewModel`, `DeviceDetailViewModel`, `AlertsViewModel`, `ReportsViewModel`), and keep all Supabase calls inside the repositories — nothing in a Composable should call the Supabase client directly.

## 8. Handoff Checklist (for Claude Code)

All decisions above are final — nothing marked TODO remains. Suggested build order:

1. Scaffold the monorepo folders (section 6).
2. Run `supabase/schema.sql` against a new Supabase project; write `supabase/seed.sql` with sample rows matching the house layout in section 5 (2 floors + exterior zone, ~30 devices total across the 11 types).
3. Initialize the Kotlin/Compose project in `android-app/`, wire up the Supabase Kotlin client, and scaffold the repository + ViewModel layers per section 7's architecture (repositories first, they're the foundation everything else depends on).
4. Build the Home screen: floor tabs, grid-overlaid floor plan image, device icons (integer grid-cell placement per section 3), tap-to-open detail sheet.
5. Build device detail controls per the type table in section 7, starting with the simplest (outlet/appliance toggle) and working up to the more involved ones (thermostat+AC combined card, multiswitch list, iron countdown).
6. Build Alerts and Reports tabs off the same repositories.
7. Build the web simulator (`web-simulator/`): manual per-device controls first, then the optional "Simulate" timer toggle.
8. Wire Realtime last — once manual CRUD works end-to-end, add the three subscriptions (section 3) so the app and simulator update live without refresh.

## 9. Change Log
- v1: tech stack finalized (Kotlin/Compose + Firebase + web simulator)
- v2: switched Firestore/Cloud Functions → Supabase (Postgres + Realtime + pg_cron) — avoids Blaze/credit-card requirement, no external worker needed
- v3: data model finalized — 9 device types, single shared home (no auth), monorepo structure locked in
- v4: expanded to 11 device types (added `ac_unit` linked to `thermostat`, generic `appliance`), added `devices.control_mode` (MANUAL/AUTO) for auto-schedule vs. manual override, finalized 2-floor house layout with room-to-device mapping
- v5: no interior cameras (moved to a new Exterior/Garden zone: walking gate, driveway gate, front approach, back garden — 4 cameras + 3 locks total); every bedroom and the study now gets its own AC+thermostat pair; added TV/washing machine/microwave appliances; confirmed MANUAL is the default control mode everywhere, with AUTO available per-device; floor plans redrawn as touching-room layouts instead of spaced grid boxes
- v6: corrected site plan so both gates are at the front of the property (street side) with the back garden camera on the opposite side; added `smart_locks.mechanism` (deadbolt / sliding_gate / turnstile) to distinguish the front door, driveway gate, and walking gate
- v7: app design finalized — bottom-nav structure (Home/Alerts/Reports/Settings), device detail controls per type, MVVM Compose architecture (Presentation → Repository → Supabase, single Realtime subscription per table)
- v8: all remaining open decisions resolved (grid coordinate system, camera mock strategy, Realtime subscription strategy, simulator update strategy) and a build-order handoff checklist added — doc is complete and ready to hand to Claude Code

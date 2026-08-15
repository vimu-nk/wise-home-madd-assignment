-- Smart Home Monitoring & Control System — Finalized Schema
-- Target: Supabase (Postgres). Single shared home, no auth/multi-tenancy.

create extension if not exists pgcrypto; -- for gen_random_uuid()
create extension if not exists pg_cron with schema extensions; -- for the safety-cutoff worker below

-- ============================================================
-- FLOORS: multiple floor plans within the one home
-- ============================================================
create table floors (
  id uuid primary key default gen_random_uuid(),
  name text not null,                  -- e.g. "Ground Floor"
  image_url text not null,             -- floor plan asset
  grid_cols int not null,
  grid_rows int not null,
  created_at timestamptz default now()
);

-- ============================================================
-- DEVICES: base table, one row per device regardless of type
-- ============================================================
create table devices (
  id uuid primary key default gen_random_uuid(),
  floor_id uuid references floors(id) on delete cascade,
  name text not null,
  type text not null check (type in (
    'outlet',
    'multiswitch',
    'scheduled_safety',    -- irons, hair dryers, space heaters
    'scheduled_light',     -- bulbs on a daily time window, with manual override
    'camera',
    'thermostat',           -- control panel; links to an ac_unit device
    'ac_unit',              -- the physical AC unit a thermostat controls
    'smart_lock',
    'sensor',               -- motion / door-window / smoke / gas / water-leak
    'smart_plug_metered',   -- outlet variant with power monitoring
    'appliance'              -- generic smart appliance (TV, fridge, washer, etc.)
  )),
  grid_x int not null,
  grid_y int not null,
  status text not null default 'OFF' check (status in ('ON','OFF','ERROR','DISCONNECTED')),
  -- AUTO = schedule/logic drives status (e.g. scheduled_light, thermostat);
  -- MANUAL = user has direct control from the app. Users can flip a device
  -- between the two; switching to MANUAL pauses its schedule until reverted.
  control_mode text not null default 'MANUAL' check (control_mode in ('MANUAL','AUTO')),
  appliance_type text,      -- free label when type='appliance': 'tv','fridge','washing_machine','microwave','fan','water_heater', etc.
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

create index idx_devices_floor on devices(floor_id);

-- keep updated_at fresh on any change
create or replace function set_updated_at() returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql;

create trigger trg_devices_updated_at
  before update on devices
  for each row execute function set_updated_at();

-- ============================================================
-- TYPE-SPECIFIC EXTENSION TABLES
-- Each device has exactly one matching row here, per its `type`.
-- ============================================================

-- Multi-switch gang-box: child switches under one physical unit
create table device_switches (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  switch_index int not null,           -- 1..N within the unit
  label text,                          -- e.g. "Kitchen light"
  status text not null default 'OFF' check (status in ('ON','OFF','ERROR','DISCONNECTED')),
  unique(device_id, switch_index)
);
create index idx_switches_device on device_switches(device_id);

-- Safety-critical duration-capped devices (irons, etc.)
create table safety_configs (
  device_id uuid primary key references devices(id) on delete cascade,
  max_on_duration_seconds int not null,
  turned_on_at timestamptz,            -- null when OFF; set when user turns ON
  last_auto_cutoff_at timestamptz
);

-- Auto on/off light bulbs — one or more daily time windows
create table light_schedules (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  start_time time not null,
  end_time time not null,
  days_of_week int[] not null default '{1,2,3,4,5,6,7}', -- 1=Mon..7=Sun
  enabled boolean default true
);
create index idx_light_schedules_device on light_schedules(device_id);

-- Security cameras
create table cameras (
  device_id uuid primary key references devices(id) on delete cascade,
  mock_stream_uri text,
  last_snapshot_url text,
  last_snapshot_at timestamptz
);

-- Thermostats: the control panel. Linked to the physical AC unit it drives.
create table thermostats (
  device_id uuid primary key references devices(id) on delete cascade,
  target_temp_c numeric not null default 22,
  mode text not null default 'OFF' check (mode in ('COOL','HEAT','AUTO','OFF')),
  controls_device_id uuid references devices(id) on delete set null  -- the linked ac_unit device
);
create index idx_thermostats_controls on thermostats(controls_device_id);

-- AC units: the physical appliance a thermostat controls.
-- ON/OFF/ERROR/DISCONNECTED lives on the base `devices.status` row, as usual.
create table ac_units (
  device_id uuid primary key references devices(id) on delete cascade,
  fan_speed text not null default 'AUTO' check (fan_speed in ('LOW','MED','HIGH','AUTO')),
  current_temp_c numeric              -- reported by the hardware simulator
);

-- Smart locks (front door deadbolt, driveway sliding gate, walking turnstile gate)
create table smart_locks (
  device_id uuid primary key references devices(id) on delete cascade,
  mechanism text not null default 'deadbolt' check (mechanism in ('deadbolt','sliding_gate','turnstile')),
  auto_relock_after_seconds int,       -- null = no auto-relock
  last_locked_at timestamptz,
  last_unlocked_at timestamptz
);

-- Sensors: motion / door-window / smoke / gas / water-leak (read-only, alert-driven)
create table sensors (
  device_id uuid primary key references devices(id) on delete cascade,
  sensor_type text not null check (sensor_type in ('motion','door_window','smoke','gas','water_leak')),
  current_reading numeric,
  unit text,
  alert_threshold numeric,
  last_triggered_at timestamptz
);

-- Metered smart plugs — outlet variant with power monitoring
create table power_metrics (
  device_id uuid primary key references devices(id) on delete cascade,
  current_watts numeric default 0,
  energy_kwh_total numeric default 0,
  last_reading_at timestamptz default now()
);

-- ============================================================
-- REPORTING & ALERTS
-- ============================================================

create table usage_logs (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  switch_id uuid references device_switches(id) on delete cascade, -- null unless multiswitch sub-switch
  event_type text not null check (event_type in (
    'ON','OFF','ERROR','AUTO_CUTOFF','LOCKED','UNLOCKED','SENSOR_TRIGGERED','MODE_CHANGE'
  )),
  triggered_by text not null check (triggered_by in ('user','schedule','safety_worker')),
  created_at timestamptz default now()
);
create index idx_usage_logs_device_time on usage_logs(device_id, created_at desc);

create table alerts (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  message text not null,
  created_at timestamptz default now(),
  acknowledged boolean default false
);
create index idx_alerts_device_ack on alerts(device_id, acknowledged);

-- ============================================================
-- SAFETY-CUTOFF WORKER (pg_cron)
-- Checks every 15s for safety-capped devices that exceeded their
-- max_on_duration, flips them OFF, logs it, and raises an alert.
-- ============================================================

create or replace function run_safety_cutoff() returns void as $$
declare
  r record;
begin
  for r in
    select d.id as device_id, d.name, sc.turned_on_at, sc.max_on_duration_seconds
    from devices d
    join safety_configs sc on sc.device_id = d.id
    where d.status = 'ON'
      and sc.turned_on_at is not null
      and now() - sc.turned_on_at > (sc.max_on_duration_seconds || ' seconds')::interval
  loop
    update devices set status = 'OFF' where id = r.device_id;
    update safety_configs
      set turned_on_at = null, last_auto_cutoff_at = now()
      where device_id = r.device_id;
    insert into usage_logs(device_id, event_type, triggered_by)
      values (r.device_id, 'AUTO_CUTOFF', 'safety_worker');
    insert into alerts(device_id, message)
      values (r.device_id, r.name || ' exceeded max on-duration and was auto-shut-off');
  end loop;
end;
$$ language plpgsql;

select cron.schedule('safety-cutoff-check', '15 seconds', 'select run_safety_cutoff();');

-- Enable Supabase Realtime for every table the app or simulator observes.
--
-- Without this, Postgres never publishes row changes and no postgres_changes
-- event ever fires: writes succeed but no client is told about them.
--
-- Replica identity is deliberately left at the default. The clients decode
-- `record` for INSERT/UPDATE (always fully populated regardless of replica
-- identity) and only read oldRecord["id"] for DELETE, which the default
-- (primary key) identity supplies. Note the extension tables key on
-- `device_id` rather than `id`, but no client handles DELETE on those —
-- they re-fetch on any action. Row-level realtime filters on non-PK columns
-- would require `replica identity full`; add it per-table if that day comes.
--
-- Deliberately excluded: `floors` (fetched once, never mutated) and
-- `light_schedules` (no UI yet). Keeps WAL volume down.

do $$
declare
  t text;
begin
  foreach t in array array[
    'devices',          -- DeviceRepository
    'device_switches',  -- SwitchRepository / multiswitch children
    'alerts',           -- AlertRepository
    'smart_locks',      -- DeviceExtrasRepository.observeLock
    'safety_configs',   -- observeSafetyConfig (iron countdown)
    'thermostats',      -- observeThermostat / observeThermostatControlling
    'ac_units',         -- observeAcUnit
    'sensors',          -- driven by the web simulator
    'cameras',          -- snapshot rotation from the web simulator
    'power_metrics',    -- metered plug live wattage
    'usage_logs'        -- live history feed
  ]
  loop
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime'
        and schemaname = 'public'
        and tablename = t
    ) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end $$;

-- Populate usage_logs automatically from actual state changes.
--
-- Previously only run_safety_cutoff() and the web simulator ever inserted here,
-- so the in-app usage history was empty for nearly every device. Doing this with
-- triggers rather than in the Kotlin client means changes originating anywhere
-- (app, simulator, pg_cron, SQL editor) are logged uniformly, and no write path
-- can forget to log.

-- devices.status -> ON / OFF / ERROR, or LOCKED / UNLOCKED for lock hardware.
create or replace function log_device_status_change() returns trigger as $$
declare
  kind text;
  mech text;
begin
  if new.status is not distinct from old.status then
    return new;
  end if;

  select mechanism into mech from smart_locks where device_id = new.id;

  if mech is not null then
    kind := case when new.status = 'ON' then 'LOCKED' else 'UNLOCKED' end;
  else
    kind := case new.status
              when 'ON' then 'ON'
              when 'OFF' then 'OFF'
              when 'ERROR' then 'ERROR'
              else 'OFF'
            end;
  end if;

  insert into usage_logs(device_id, event_type, triggered_by)
  values (
    new.id,
    kind,
    case when new.control_mode = 'AUTO' then 'schedule' else 'user' end
  );

  return new;
end;
$$ language plpgsql;

drop trigger if exists trg_devices_usage_log on devices;
create trigger trg_devices_usage_log
  after update of status on devices
  for each row execute function log_device_status_change();

-- device_switches.status -> ON / OFF, carrying switch_id so per-switch history works.
create or replace function log_switch_status_change() returns trigger as $$
begin
  if new.status is not distinct from old.status then
    return new;
  end if;

  insert into usage_logs(device_id, switch_id, event_type, triggered_by)
  values (
    new.device_id,
    new.id,
    case when new.status = 'ON' then 'ON' else 'OFF' end,
    'user'
  );

  return new;
end;
$$ language plpgsql;

drop trigger if exists trg_switches_usage_log on device_switches;
create trigger trg_switches_usage_log
  after update of status on device_switches
  for each row execute function log_switch_status_change();

-- thermostats.mode -> MODE_CHANGE
create or replace function log_thermostat_mode_change() returns trigger as $$
begin
  if new.mode is not distinct from old.mode then
    return new;
  end if;

  insert into usage_logs(device_id, event_type, triggered_by)
  values (new.device_id, 'MODE_CHANGE', 'user');

  return new;
end;
$$ language plpgsql;

drop trigger if exists trg_thermostats_usage_log on thermostats;
create trigger trg_thermostats_usage_log
  after update of mode on thermostats
  for each row execute function log_thermostat_mode_change();


-- ############################################################
-- Added 2026-08-17: spec-gap closures (light worker, safety arming
-- + presets, rooms, RLS). Mirrors supabase/migrations/20260817*.sql.
-- ############################################################

-- ============================================================
-- LIGHT-SCHEDULE WORKER (pg_cron)
--
-- `light_schedules` existed and was seeded, but nothing ever read it, so
-- scheduled_light devices never switched themselves on or off. This is the
-- backend half of that requirement; the app only edits the windows.
--
-- Deliberate rules:
--   * AUTO only. control_mode = 'MANUAL' is the user override the schema
--     already defines — the worker must not fight a user who took control.
--   * Devices in ERROR / DISCONNECTED are skipped: those are hardware states
--     asserted by the simulator, and flipping status would erase them.
--   * Only devices that actually have an enabled schedule are considered.
--     Otherwise an AUTO light with no windows could never be turned on at all,
--     because the worker would drive it back OFF a minute later.
--   * Times are evaluated in Asia/Colombo, not UTC. The database runs UTC, so
--     an 18:00 window would otherwise fire at 23:30 local.
--   * Writes happen only when the status actually changes — otherwise every
--     light would emit a usage_logs row and a realtime event every minute.
-- ============================================================

create or replace function run_light_schedules() returns void as $$
declare
  r record;
  local_ts timestamp;
  local_time time;
  local_dow int;
begin
  local_ts := now() at time zone 'Asia/Colombo';
  local_time := local_ts::time;
  local_dow := extract(isodow from local_ts)::int;   -- 1=Mon .. 7=Sun

  for r in
    select
      d.id as device_id,
      d.status,
      bool_or(
        case
          when ls.start_time <= ls.end_time then
            -- Same-day window, e.g. 18:00 -> 22:30.
            local_dow = any(ls.days_of_week)
            and local_time >= ls.start_time
            and local_time < ls.end_time
          else
            -- Window wraps past midnight, e.g. 18:00 -> 06:00. The evening half
            -- belongs to today's schedule; the small-hours half belongs to
            -- yesterday's, so it is matched against yesterday's day-of-week.
            (local_dow = any(ls.days_of_week) and local_time >= ls.start_time)
            or (((local_dow + 5) % 7 + 1) = any(ls.days_of_week) and local_time < ls.end_time)
        end
      ) as should_be_on
    from devices d
    join light_schedules ls on ls.device_id = d.id and ls.enabled
    where d.type = 'scheduled_light'
      and d.control_mode = 'AUTO'
      and d.status in ('ON', 'OFF')
    group by d.id, d.status
  loop
    if r.should_be_on and r.status = 'OFF' then
      update devices set status = 'ON' where id = r.device_id;
    elsif not r.should_be_on and r.status = 'ON' then
      update devices set status = 'OFF' where id = r.device_id;
    end if;
  end loop;
end;
$$ language plpgsql;

-- No logging code here on purpose: trg_devices_usage_log already writes a
-- usage_logs row with triggered_by = 'schedule' for AUTO devices.

-- Re-runnable: unschedule the previous job before registering it again.
select cron.unschedule('light-schedule-check')
where exists (select 1 from cron.job where jobname = 'light-schedule-check');

select cron.schedule('light-schedule-check', '* * * * *', 'select run_light_schedules();');

-- ============================================================
-- SAFETY: arm the timer from any writer, and per-appliance presets
--
-- Problem this fixes: safety_configs.turned_on_at was written only by the
-- Android app. Turning an iron ON from the hardware simulator left it null, so
-- run_safety_cutoff() skipped the device and it stayed on indefinitely — the
-- exact scenario the cutoff exists to prevent.
--
-- Moving the bookkeeping into a trigger covers every writer by construction:
-- app, simulator, pg_cron, SQL editor.
-- ============================================================

create or replace function arm_safety_timer() returns trigger as $$
begin
  if new.status = 'ON' and old.status is distinct from 'ON' then
    -- No-ops for devices without a safety config, which is most of them.
    update safety_configs set turned_on_at = now() where device_id = new.id;
  elsif new.status is distinct from 'ON' and old.status = 'ON' then
    update safety_configs set turned_on_at = null where device_id = new.id;
  end if;
  return new;
end;
$$ language plpgsql;

drop trigger if exists trg_devices_safety_arm on devices;
create trigger trg_devices_safety_arm
  after update of status on devices
  for each row execute function arm_safety_timer();

-- ------------------------------------------------------------
-- Per-appliance duration presets
--
-- Held in the database rather than the client so the seeded caps and the
-- options offered in the app cannot drift apart. Durations reflect what each
-- appliance is plausibly left running for — a 5-minute cap on a space heater
-- or a 2-hour cap on an iron would both be useless.
-- ------------------------------------------------------------

create table if not exists safety_presets (
  kind text primary key,
  label text not null,
  default_seconds int not null,
  options_seconds int[] not null
);

insert into safety_presets (kind, label, default_seconds, options_seconds) values
  ('iron',         'Iron',         900,  '{300,900,1800}'),
  ('hair_dryer',   'Hair dryer',   600,  '{300,600,900}'),
  ('space_heater', 'Space heater', 7200, '{1800,3600,7200}'),
  ('water_heater', 'Water heater', 2700, '{900,2700,5400}')
on conflict (kind) do update
  set label = excluded.label,
      default_seconds = excluded.default_seconds,
      options_seconds = excluded.options_seconds;

alter table safety_configs add column if not exists kind text references safety_presets(kind);

update safety_configs set kind = 'iron' where kind is null;
alter table safety_configs alter column kind set not null;

-- ------------------------------------------------------------
-- More hazard appliances
--
-- Only one existed (a single iron), which left the "heterogeneous device
-- profiles" requirement thin. Grid cells chosen from cells that are free and
-- inside an existing room, so every one of these is visible in the app.
-- ------------------------------------------------------------

insert into devices (id, floor_id, name, type, grid_x, grid_y) values
  ('10000020-0000-0000-0000-000000000031', '00000000-0000-0000-0000-000000000001', 'Living Room Space Heater', 'scheduled_safety', 2, 0),
  ('10000021-0000-0000-0000-000000000032', '00000000-0000-0000-0000-000000000001', 'Kitchen Water Heater',     'scheduled_safety', 4, 0),
  ('20000020-0000-0000-0000-000000000033', '00000000-0000-0000-0000-000000000002', 'Bathroom Hair Dryer',      'scheduled_safety', 3, 2)
on conflict (id) do nothing;

insert into safety_configs (device_id, kind, max_on_duration_seconds) values
  ('10000020-0000-0000-0000-000000000031', 'space_heater', 7200),
  ('10000021-0000-0000-0000-000000000032', 'water_heater', 2700),
  ('20000020-0000-0000-0000-000000000033', 'hair_dryer',   600)
on conflict (device_id) do nothing;

-- ============================================================
-- ROOMS
--
-- Room rectangles were hardcoded in the Android client, keyed by floor *name*,
-- so a floor added to the database rendered as one unlabelled room spanning the
-- whole grid. Moving them into the database is what makes "adding and managing
-- floor plans" possible from the app.
--
-- Seeded with exactly the layouts the client used, so nothing visibly changes
-- on first run. One deliberate exception: Bathroom grows from (2,2)-(2,3) to
-- (2,2)-(3,3). Those two cells belonged to no room, and the Bathroom Hair Dryer
-- added in the previous migration sits at (3,2) — without this it would be
-- invisible in the app.
-- ============================================================

create table if not exists rooms (
  id uuid primary key default gen_random_uuid(),
  floor_id uuid not null references floors(id) on delete cascade,
  label text not null,
  x0 int not null,
  y0 int not null,
  x1 int not null,
  y1 int not null,
  created_at timestamptz default now(),
  unique (floor_id, label),
  check (x0 <= x1 and y0 <= y1)
);

create index if not exists idx_rooms_floor on rooms(floor_id);

insert into rooms (floor_id, label, x0, y0, x1, y1) values
  -- Ground Floor
  ('00000000-0000-0000-0000-000000000001', 'Foyer',             0, 0, 1, 0),
  ('00000000-0000-0000-0000-000000000001', 'Living Room',       2, 0, 3, 2),
  ('00000000-0000-0000-0000-000000000001', 'Kitchen',           4, 0, 5, 2),
  ('00000000-0000-0000-0000-000000000001', 'Dining Area',       0, 1, 1, 2),
  ('00000000-0000-0000-0000-000000000001', 'Guest Bedroom',     0, 3, 1, 4),
  ('00000000-0000-0000-0000-000000000001', 'Garage',            2, 3, 5, 4),
  -- First Floor
  ('00000000-0000-0000-0000-000000000002', 'Master Bedroom',    0, 0, 1, 1),
  ('00000000-0000-0000-0000-000000000002', 'Bedroom 2',         3, 0, 4, 1),
  ('00000000-0000-0000-0000-000000000002', 'Study / Office',    5, 0, 5, 3),
  ('00000000-0000-0000-0000-000000000002', 'Bathroom',          2, 2, 3, 3),
  ('00000000-0000-0000-0000-000000000002', 'Balcony',           0, 4, 1, 4),
  ('00000000-0000-0000-0000-000000000002', 'Landing / Hallway', 2, 4, 4, 4),
  -- Exterior / Garden
  ('00000000-0000-0000-0000-000000000003', 'Walking Gate',      0, 0, 1, 1),
  ('00000000-0000-0000-0000-000000000003', 'Driveway Gate',     3, 0, 4, 1),
  ('00000000-0000-0000-0000-000000000003', 'Front Approach',    2, 2, 5, 3),
  ('00000000-0000-0000-0000-000000000003', 'Back Garden',       5, 4, 7, 5)
on conflict (floor_id, label) do nothing;

-- ------------------------------------------------------------
-- Realtime for the tables the new UI edits.
--
-- `floors` and `light_schedules` were previously excluded as "fetched once,
-- never mutated" / "no UI yet". Both are now editable from the app, so a change
-- on one device must reach the others without a manual refresh.
-- ------------------------------------------------------------

do $$
declare
  t text;
begin
  foreach t in array array['floors', 'rooms', 'light_schedules', 'safety_presets']
  loop
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime'
        and schemaname = 'public'
        and tablename = t
    ) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end $$;

-- ============================================================
-- ROW LEVEL SECURITY
--
-- The publishable key ships inside a public APK and a public GitHub Pages site,
-- so it must be treated as known to everyone. Until now every table was fully
-- open to it, including DELETE — a single crafted request could empty the demo.
--
-- This project has no login (one shared home by design), so the policies stay
-- permissive on purpose: anon may read everything and write the tables the app
-- and simulator actually write. What it buys is that nothing else is reachable,
-- and mass deletion is limited to the three tables the management UI needs.
--
-- pg_cron workers connect as a superuser and bypass RLS, so run_safety_cutoff()
-- and run_light_schedules() are unaffected.
-- ============================================================

-- Readable by anyone holding the publishable key.
do $$
declare
  t text;
begin
  foreach t in array array[
    'floors', 'rooms', 'devices', 'device_switches', 'safety_configs',
    'safety_presets', 'light_schedules', 'cameras', 'thermostats', 'ac_units',
    'smart_locks', 'sensors', 'power_metrics', 'usage_logs', 'alerts'
  ]
  loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists %I on public.%I', t || '_anon_select', t);
    execute format(
      'create policy %I on public.%I for select to anon, authenticated using (true)',
      t || '_anon_select', t
    );
  end loop;
end $$;

-- Writable: everything the app or the hardware simulator mutates.
-- safety_presets is deliberately absent — it is reference data, read-only to clients.
do $$
declare
  t text;
begin
  foreach t in array array[
    'floors', 'rooms', 'devices', 'device_switches', 'safety_configs',
    'light_schedules', 'cameras', 'thermostats', 'ac_units', 'smart_locks',
    'sensors', 'power_metrics', 'usage_logs', 'alerts'
  ]
  loop
    execute format('drop policy if exists %I on public.%I', t || '_anon_insert', t);
    execute format(
      'create policy %I on public.%I for insert to anon, authenticated with check (true)',
      t || '_anon_insert', t
    );
    execute format('drop policy if exists %I on public.%I', t || '_anon_update', t);
    execute format(
      'create policy %I on public.%I for update to anon, authenticated using (true) with check (true)',
      t || '_anon_update', t
    );
  end loop;
end $$;

-- Deletable: only what the floor/room management UI and the schedule editor
-- need. Everything else can be turned off or emptied, but not removed.
do $$
declare
  t text;
begin
  foreach t in array array['floors', 'rooms', 'devices', 'light_schedules']
  loop
    execute format('drop policy if exists %I on public.%I', t || '_anon_delete', t);
    execute format(
      'create policy %I on public.%I for delete to anon, authenticated using (true)',
      t || '_anon_delete', t
    );
  end loop;
end $$;

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

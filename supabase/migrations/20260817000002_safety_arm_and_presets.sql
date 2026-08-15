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

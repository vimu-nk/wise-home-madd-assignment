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

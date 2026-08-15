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

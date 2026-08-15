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

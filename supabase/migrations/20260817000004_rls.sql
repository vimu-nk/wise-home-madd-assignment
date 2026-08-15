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

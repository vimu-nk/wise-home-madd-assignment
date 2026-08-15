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

# WiseHome — Smart Home Monitoring & Control System

Module: SCS 3311 — Mobile Application Design & Development

A smart-home monitoring and control system in three parts: an Android client, a
Supabase (Postgres) backend that enforces the safety and scheduling rules server-side,
and a web-based hardware simulator that stands in for the physical devices.

| Deliverable | Link |
|---|---|
| Hardware simulator (live) | https://vimu-nk.github.io/wise-home-madd-assignment/web-simulator/ |
| Android APK | See [Releases](../../releases) |
| Technical report | [docs/technical-report.md](docs/technical-report.md) |

## Structure

```
android-app/    Kotlin + Jetpack Compose client
web-simulator/  Plain HTML/JS + Supabase JS client (hardware simulator)
supabase/       schema.sql (full DDL, mirrors migrations/), migrations/, seed.sql
docs/           technical report, project spec / decisions log
```

## Setup

1. Create a Supabase project (free tier, no card required).
2. Apply the schema: run the files in `supabase/migrations/` in filename order from the
   SQL editor, or `supabase/schema.sql` followed by `supabase/seed.sql` for a fresh
   project. `pg_cron` must be enabled — the safety cut-off and light schedule workers
   run from it.
3. Android app: create `android-app/local.properties` (gitignored — it is not in the
   repo) with the values from Project Settings → API:

   ```properties
   SUPABASE_URL=https://<project>.supabase.co
   SUPABASE_ANON_KEY=<publishable key>
   ```

   Without these the build still succeeds but `BuildConfig` holds empty strings and the
   app talks to nothing.
4. Web simulator: put the same two values in `web-simulator/config.js`, then serve the
   folder statically (`npx http-server web-simulator`, or GitHub Pages).

## Building the APK

```bash
cd android-app
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

Attach that file to a GitHub Release so the download link is stable.

## Notes on keys

The publishable (anon) key is embedded in both the APK and the simulator page, which is
how Supabase publishable keys are designed to be used — they are not secrets. What
protects the data is row-level security, applied in
`supabase/migrations/20260817000004_rls.sql`: anonymous clients may read everything and
write the tables the app and simulator use, and may delete only floors, rooms, devices
and light schedules. The `service_role` key is never used by either client and must not
be added to this repo.

Full design decisions: [docs/smart-home-project-spec.md](docs/smart-home-project-spec.md).

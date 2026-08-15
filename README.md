# WiseHome — Smart Home Monitoring & Control System

Module: SCS 3311 — Mobile Application Design & Development

## Structure
```
android-app/    Kotlin + Jetpack Compose client
web-simulator/  Plain HTML/JS + Supabase JS client (hardware simulator)
supabase/       schema.sql (DDL + pg_cron worker), seed.sql (sample house layout)
docs/           project spec / decisions log
```

## Setup

1. Create a Supabase project (free tier, no card required).
2. Run `supabase/schema.sql` then `supabase/seed.sql` against it (SQL editor or CLI).
3. Android app: fill in `android-app/local.properties` with `SUPABASE_URL` and `SUPABASE_ANON_KEY`
   from Project Settings → API.
4. Web simulator: fill in the same values in `web-simulator/config.js`, then serve the folder
   statically (e.g. GitHub Pages, or `npx serve web-simulator`).

Full design decisions: `docs/smart-home-project-spec.md`.

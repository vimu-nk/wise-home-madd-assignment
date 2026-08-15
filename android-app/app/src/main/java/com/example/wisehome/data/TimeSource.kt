package com.example.wisehome.data

import java.time.Instant

/**
 * Timestamps written to Postgres. Explicit ISO-8601 rather than the literal
 * string "now" — Postgres happens to accept 'now' as a timestamptz literal, but
 * relying on that is undocumented luck and makes the stored value look
 * unparseable to anyone reading the row.
 */
fun nowIso(): String = Instant.now().toString()

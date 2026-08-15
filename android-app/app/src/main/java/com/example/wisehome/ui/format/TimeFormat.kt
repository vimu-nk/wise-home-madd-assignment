package com.example.wisehome.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Human-readable time. Nothing in the UI should ever render a raw ISO-8601
 * string from Postgres.
 */

private val TIME_ONLY = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val WEEKDAY_TIME = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())
private val DAY_MONTH_TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())
private val FULL = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.getDefault())

/**
 * Lenient parse of a PostgREST timestamptz. Returns null rather than throwing —
 * the previous unguarded OffsetDateTime.parse could crash the detail sheet.
 */
fun parseTimestamp(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { Instant.parse(iso) }
        .recoverCatching {
            // Postgres can return "2026-08-15 12:39:40.184428+00" (space, no 'T')
            OffsetDateTime.parse(iso.replaceFirst(' ', 'T')).toInstant()
        }
        .getOrNull()
}

/**
 * "Just now" · "4 minutes ago" · "3 hours ago" · "Yesterday 9:14 PM" ·
 * "Tue 3:42 PM" · "12 Aug, 3:42 PM" · "12 Aug 2025, 3:42 PM" · "—" when unknown.
 */
fun formatRelative(iso: String?, now: Instant = Instant.now()): String {
    val instant = parseTimestamp(iso) ?: return "—"
    val zone = ZoneId.systemDefault()
    val seconds = Duration.between(instant, now).seconds

    if (seconds < 0) return "Just now"

    val local = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val date = local.toLocalDate()

    return when {
        seconds < 45 -> "Just now"
        seconds < 3_600 -> {
            val minutes = seconds / 60
            if (minutes <= 1) "1 minute ago" else "$minutes minutes ago"
        }
        seconds < 43_200 -> {
            val hours = seconds / 3_600
            if (hours <= 1) "1 hour ago" else "$hours hours ago"
        }
        date == today -> "Today ${local.format(TIME_ONLY)}"
        date == today.minusDays(1) -> "Yesterday ${local.format(TIME_ONLY)}"
        seconds < 604_800 -> local.format(WEEKDAY_TIME)
        date.year == today.year -> local.format(DAY_MONTH_TIME)
        else -> local.format(FULL)
    }
}

/** "15 Aug 2026, 3:42 PM" — the exact moment, for detail rows. */
fun formatAbsolute(iso: String?): String {
    val instant = parseTimestamp(iso) ?: return "—"
    return instant.atZone(ZoneId.systemDefault()).format(FULL)
}

/** 754 -> "12m 34s"; 45 -> "45s"; 3700 -> "1h 1m". */
fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

/** Re-renders every 30s so "2 minutes ago" ages by itself while the screen is open. */
@Composable
fun rememberRelativeTime(iso: String?): String {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(iso) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    return formatRelative(iso, now)
}

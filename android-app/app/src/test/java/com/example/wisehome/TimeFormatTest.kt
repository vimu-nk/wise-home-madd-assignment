package com.example.wisehome

import com.example.wisehome.ui.format.formatDuration
import com.example.wisehome.ui.format.formatRelative
import com.example.wisehome.ui.format.parseTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class TimeFormatTest {

    private val now: Instant = Instant.parse("2026-08-15T12:00:00Z")

    private fun ago(amount: Long, unit: ChronoUnit): String =
        now.minus(amount, unit).toString()

    @Test
    fun `parses postgrest offset timestamps`() {
        assertNotNull(parseTimestamp("2026-08-15T12:39:40.184428Z"))
        assertNotNull(parseTimestamp("2026-08-15T12:39:40.184428+00:00"))
    }

    @Test
    fun `parses postgres space-separated timestamps`() {
        assertNotNull(parseTimestamp("2026-08-15 12:39:40.184428+00:00"))
    }

    /** The old unguarded OffsetDateTime.parse crashed the detail sheet here. */
    @Test
    fun `unparseable input returns null instead of throwing`() {
        assertNull(parseTimestamp("now"))
        assertNull(parseTimestamp("not a timestamp"))
        assertNull(parseTimestamp(""))
        assertNull(parseTimestamp(null))
    }

    @Test
    fun `unknown timestamps render as a dash`() {
        assertEquals("—", formatRelative(null, now))
        assertEquals("—", formatRelative("garbage", now))
    }

    @Test
    fun `very recent events read as just now`() {
        assertEquals("Just now", formatRelative(ago(5, ChronoUnit.SECONDS), now))
        assertEquals("Just now", formatRelative(ago(44, ChronoUnit.SECONDS), now))
    }

    @Test
    fun `minutes are singular at one and plural beyond`() {
        assertEquals("1 minute ago", formatRelative(ago(60, ChronoUnit.SECONDS), now))
        assertEquals("5 minutes ago", formatRelative(ago(5, ChronoUnit.MINUTES), now))
        assertEquals("59 minutes ago", formatRelative(ago(59, ChronoUnit.MINUTES), now))
    }

    @Test
    fun `hours take over after an hour`() {
        assertEquals("1 hour ago", formatRelative(ago(60, ChronoUnit.MINUTES), now))
        assertEquals("3 hours ago", formatRelative(ago(3, ChronoUnit.HOURS), now))
    }

    @Test
    fun `future timestamps do not render as negative`() {
        assertEquals("Just now", formatRelative(now.plusSeconds(30).toString(), now))
    }

    @Test
    fun `durations are compact and human`() {
        assertEquals("0s", formatDuration(0))
        assertEquals("45s", formatDuration(45))
        assertEquals("12m 34s", formatDuration(754))
        assertEquals("1h 1m", formatDuration(3_700))
    }
}

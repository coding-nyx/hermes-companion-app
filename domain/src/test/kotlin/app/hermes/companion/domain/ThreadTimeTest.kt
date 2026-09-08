package app.hermes.companion.domain

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadTimeTest {
    private val zone = ZoneOffset.UTC
    // 2026-09-08T12:00:00Z
    private val now = 1_788_868_800_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun relativeBuckets() {
        assertEquals("", ThreadTime.relative(0L, now, zone))
        assertEquals("now", ThreadTime.relative(now - 5_000, now, zone))
        assertEquals("now", ThreadTime.relative(now + 5_000, now, zone))
        assertEquals("5m", ThreadTime.relative(now - 5 * minute, now, zone))
        assertEquals("3h", ThreadTime.relative(now - 3 * hour, now, zone))
        assertEquals("2d", ThreadTime.relative(now - 2 * day, now, zone))
        assertEquals("Aug 25", ThreadTime.relative(now - 14 * day, now, zone))
        assertEquals("2025-09-08", ThreadTime.relative(now - 365 * day, now, zone))
    }

    @Test
    fun dayGroups() {
        assertEquals("UNDATED", ThreadTime.dayGroup(0L, now, zone))
        assertEquals("TODAY", ThreadTime.dayGroup(now - hour, now, zone))
        assertEquals("TODAY", ThreadTime.dayGroup(now + hour, now, zone))
        assertEquals("YESTERDAY", ThreadTime.dayGroup(now - day, now, zone))
        assertEquals("THIS WEEK", ThreadTime.dayGroup(now - 5 * day, now, zone))
        assertEquals("AUG 2026", ThreadTime.dayGroup(now - 14 * day, now, zone))
        assertEquals("2025", ThreadTime.dayGroup(now - 400 * day, now, zone))
    }
}

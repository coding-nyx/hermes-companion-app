package app.hermes.companion.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Compact, glanceable time for the thread rail and chat header. Mono-width friendly:
 * `now` · `5m` · `3h` · `2d` · `Sep 4` · `2025-12-01`. Pure function of (then, now, zone).
 */
object ThreadTime {
    private val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val isoDay = DateTimeFormatter.ISO_LOCAL_DATE

    fun relative(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        if (epochMs <= 0L) return ""
        val delta = nowMs - epochMs
        if (delta < 0L) return "now"
        val sec = delta / 1_000
        val min = sec / 60
        val hour = min / 60
        return when {
            sec < 60 -> "now"
            min < 60 -> "${min}m"
            hour < 24 -> "${hour}h"
            hour < 24 * 7 -> "${hour / 24}d"
            else -> {
                val then = LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), zone)
                val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
                if (then.year == today.year) monthDay.format(then) else isoDay.format(then)
            }
        }
    }

    /** Group header for a time-sorted rail. `TODAY` · `YESTERDAY` · `THIS WEEK` · `SEP 2026` · `2025`. */
    fun dayGroup(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        if (epochMs <= 0L) return "UNDATED"
        val then = LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), zone)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val days = today.toEpochDay() - then.toEpochDay()
        return when {
            days <= 0L -> "TODAY"
            days == 1L -> "YESTERDAY"
            days < 7L -> "THIS WEEK"
            then.year == today.year -> DateTimeFormatter.ofPattern("MMM yyyy", Locale.US).format(then).uppercase(Locale.US)
            else -> then.year.toString()
        }
    }
}

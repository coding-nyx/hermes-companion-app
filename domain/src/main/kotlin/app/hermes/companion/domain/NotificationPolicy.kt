package app.hermes.companion.domain

/**
 * How a wake ping becomes a shade notification (A27.1).
 *
 * Everything here is pure so the mapping is unit-testable: which channel a ping lands on, how
 * loudly it announces itself, what badge it carries, and how it groups. The Android-specific
 * building lives in `WakeNotifier`.
 *
 * The problem this replaces: every companion notification went to one `IMPORTANCE_HIGH` "wake"
 * channel with the stock system info icon and a body like `coder · sess-123`. A run finishing
 * buzzed the phone exactly as hard as an approval blocking an agent, and nothing on screen said
 * which was which — so the user learned to ignore all of them.
 */
object NotificationPolicy {

    /** A shade channel: stable id, user-visible name, and how much it may interrupt. */
    data class Channel(
        val id: String,
        val title: String,
        val importance: Importance,
        val description: String,
    )

    /** Mirrors the `NotificationManager.IMPORTANCE_*` ladder without depending on Android. */
    enum class Importance { MIN, LOW, DEFAULT, HIGH }

    val APPROVALS = Channel(
        "hermes.approvals",
        "Approvals",
        Importance.HIGH,
        "An agent is blocked waiting for you to allow or deny something.",
    )
    val MENTIONS = Channel(
        "hermes.mentions",
        "Mentions & questions",
        Importance.DEFAULT,
        "An agent asked you something or named you in a room.",
    )
    val TURNS = Channel(
        "hermes.turns",
        "Turn activity",
        Importance.LOW,
        "Runs finishing and rooms going quiet. Silent; watch it in the shade.",
    )
    val CRON = Channel(
        "hermes.cron",
        "Scheduled tasks",
        Importance.HIGH,
        "Reminders and scheduled runs firing.",
    )
    val HEALTH = Channel(
        "hermes.health",
        "Fleet health",
        Importance.MIN,
        "Host and lane status. Silent and collapsed.",
    )

    /** Every channel to register at startup, so a ping never has to create one on the hot path. */
    val ALL: List<Channel> = listOf(APPROVALS, MENTIONS, TURNS, CRON, HEALTH)

    /**
     * Which channel a ping belongs on.
     *
     * `error` deliberately sits on [MENTIONS] rather than [HEALTH]: a silent error is one the
     * user discovers hours later. It is not blocking, so it does not earn [APPROVALS].
     */
    fun channelFor(type: String): Channel = when (type) {
        "approval.request", "room.approval" -> APPROVALS
        "clarify", "room.mention", "error" -> MENTIONS
        "run.completed", "room.quiet", "room.paused" -> TURNS
        else -> when {
            // Reserved for P10's cron surface; no producer emits these yet.
            type.startsWith("cron.") -> CRON
            type.startsWith("health.") -> HEALTH
            else -> TURNS
        }
    }

    /**
     * Scannable badge for the notification title. Null where a badge would be noise — a finished
     * run is already on a silent channel and needs no shouting.
     */
    fun pill(type: String): String? = when (type) {
        "approval.request" -> "APPROVAL"
        "room.approval" -> "APPROVAL · ROOM"
        "room.mention" -> "MENTION"
        "clarify" -> "QUESTION"
        "error" -> "ERROR"
        "room.quiet", "room.paused" -> "ROOM"
        else -> when {
            type.startsWith("cron.") -> "CRON"
            else -> null
        }
    }

    /**
     * One-letter mark for the agent, so a glance distinguishes profiles that share a channel.
     * Falls back to `?` rather than throwing on an empty profile.
     */
    fun glyph(profile: String): String {
        val ch = profile.trim().firstOrNull { it.isLetterOrDigit() } ?: return "?"
        return ch.uppercaseChar().toString()
    }

    /**
     * Who the message appears to be from: `knight · lab`, or just `knight` when the host is
     * unknown. The host matters because the same profile name exists on several hosts.
     */
    fun sender(profile: String, hostName: String): String {
        val p = profile.trim().ifBlank { "agent" }
        val h = hostName.trim()
        return if (h.isBlank()) p else "$p · $h"
    }

    /** Title line: the badge, then who it is from. */
    fun title(type: String, profile: String, hostName: String): String {
        val who = sender(profile, hostName)
        val badge = pill(type)
        return if (badge == null) who else "$badge · $who"
    }

    /**
     * Body text. Prefers a real snippet; otherwise a human phrase for the type rather than the
     * raw `session_id` the old builder showed.
     */
    fun body(type: String, snippet: String, roomId: String, sessionId: String): String {
        val s = snippet.trim()
        if (s.isNotEmpty()) return s
        val fallback = when (type) {
            "approval.request" -> "needs your approval to continue"
            "clarify" -> "asked you a question"
            "run.completed" -> "finished its run"
            "error" -> "hit an error"
            "room.quiet" -> "the room went quiet"
            "room.paused" -> "the room paused at its budget"
            "room.approval" -> "the room needs an approval"
            "room.mention" -> "you were mentioned"
            else -> type
        }
        val where = roomId.trim().ifBlank { sessionId.trim() }
        return if (where.isBlank()) fallback else "$fallback — $where"
    }

    /**
     * Notifications group per host, so a busy fleet collapses into one entry per host instead of
     * a flat wall. Falls back to a constant key so ungrouped pings still collapse together.
     */
    fun groupKey(origin: String): String = origin.trim().ifBlank { "hermes" }

    /** Stable per-thread id so a second ping for the same thread replaces the first. */
    fun notificationId(origin: String, sessionId: String, roomId: String): Int {
        val where = roomId.trim().ifBlank { sessionId.trim() }
        return (groupKey(origin) + "|" + where).hashCode()
    }

    /** Summary notification id for a host's group. Distinct from any member's id. */
    fun summaryId(origin: String): Int = ("summary|" + groupKey(origin)).hashCode()

    /** Summary line: "3 from lab" — enough to decide whether to expand. */
    fun summaryText(count: Int, hostName: String): String {
        val h = hostName.trim().ifBlank { "Hermes" }
        return if (count == 1) "1 update from $h" else "$count updates from $h"
    }
}

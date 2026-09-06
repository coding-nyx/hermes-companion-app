package app.hermes.companion.domain

import app.hermes.companion.model.RoomParticipant

/** Pure helpers for agent rooms (P21): glyphs, mention chips, backing-session filtering. */
object Rooms {
    const val OPERATOR = "operator"
    /** Host titles backing sessions `room:<id> <title>`; the thread rail hides them. */
    const val BACKING_TITLE_PREFIX = "room:"

    /** 3-letter machine glyph, mirrored from hermes-plugin/rooms.py `glyph()`. */
    fun glyph(profile: String?): String {
        if (profile == null || profile == OPERATOR) return "YOU"
        val p = profile.trim()
        return (if (p.length >= 3) p.substring(0, 3) else p.ifBlank { "???" }).uppercase()
    }

    fun isBackingSession(title: String): Boolean = title.trim().startsWith(BACKING_TITLE_PREFIX)

    /** Index of [speaker] among [participants]; drives the rail style (solid/dashed/dotted). */
    fun speakerIndex(speaker: String?, participants: List<RoomParticipant>): Int {
        if (speaker == null) return 0
        val idx = participants.indexOfFirst { it.profile == speaker }
        return if (idx < 0) 0 else idx
    }

    /** Insert `@GLYPH ` into a draft at the end, avoiding a double space or duplicate chip. */
    fun withMention(draft: String, glyph: String): String {
        val token = "@$glyph"
        if (draft.split(Regex("\\s+")).any { it.equals(token, ignoreCase = true) }) return draft
        val base = draft.trimEnd()
        return if (base.isEmpty()) "$token " else "$base $token "
    }

    /** Human label for a turn-end error code from the host. */
    fun turnErrorLabel(code: String): String = when (code) {
        "" -> ""
        "interrupted" -> "interrupted"
        "approval_required" -> "needs approval · answer it in that profile's thread"
        "upstream_timeout" -> "turn timed out"
        else -> code.replace('_', ' ')
    }
}

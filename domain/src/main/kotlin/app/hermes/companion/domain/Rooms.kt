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

    /**
     * Resolves the user-facing agent name for a room speaker.
     * Prefers a matching profile displayName (or profile id), falls back to the speaker id.
     */
    fun displayName(
        speaker: String?,
        participants: List<RoomParticipant> = emptyList(),
        profiles: List<app.hermes.companion.model.ProfileRef> = emptyList(),
    ): String {
        if (speaker == null || speaker == OPERATOR) return "YOU"
        val p = speaker.trim()
        // Remote participants arrive as `profile@host`: name the profile, tag the host.
        val host = hostOf(p)
        val local = if (host != null) p.substringBefore('@') else p
        val prof = profiles.firstOrNull { it.id.equals(local, ignoreCase = true) }
        val base = when {
            host == null && prof != null && prof.displayName.isNotBlank() -> prof.displayName
            else -> participants.firstOrNull { it.id.equals(p, ignoreCase = true) || (host == null && it.profile.equals(p, ignoreCase = true)) }?.profile ?: local
        }
        return if (host != null) "$base · $host" else base
    }

    /**
     * Header label for an assistant row or pending row.
     * In room mode (or when speaker is known), displays the agent name (never "HERMES").
     */
    fun speakerLabel(
        speaker: String?,
        participants: List<RoomParticipant> = emptyList(),
        profiles: List<app.hermes.companion.model.ProfileRef> = emptyList(),
    ): String = when {
        speaker != null -> displayName(speaker, participants, profiles)
        participants.size == 1 -> displayName(participants[0].profile, participants, profiles)
        participants.isNotEmpty() -> "AGENT"
        else -> "HERMES"
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
        "approval_timeout" -> "approval timed out"
        "upstream_timeout" -> "turn timed out"
        "peer_unreachable" -> "peer host unreachable"
        "peer_unknown" -> "peer not linked"
        "hermes_cli_missing" -> "no hermes CLI on host"
        "empty_reply" -> "no reply"
        else -> code.replace('_', ' ')
    }

    /** `bishop@hub-11` → `hub-11`; null for local participants. */
    fun hostOf(participantId: String?): String? =
        participantId?.substringAfter('@', "")?.takeIf { it.isNotBlank() }

    /** Row text for the floor state under the transcript. Empty while running/idle. */
    fun stateLabel(state: String, reason: String, turnsUsed: Int, budget: Int): String = when (state) {
        "quiet" -> "room quiet · $turnsUsed turns"
        "paused" -> when (reason) {
            "budget" -> "paused · budget of $budget turns reached"
            "time" -> "paused · time budget reached"
            "stall" -> "paused · agents are repeating themselves"
            "operator" -> "paused by you"
            else -> "paused"
        }
        else -> ""
    }

    /** Does the operator get addressed in this line? Lights the rail tick. */
    fun mentionsOperator(text: String): Boolean =
        Regex("(?<![\\w@])@(you|operator)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Header pill: `R 7/12` while the floor is live. */
    fun budgetPill(turnsUsed: Int, budget: Int): String = "R $turnsUsed/$budget"
}

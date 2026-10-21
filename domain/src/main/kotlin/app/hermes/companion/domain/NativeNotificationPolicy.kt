package app.hermes.companion.domain

import app.hermes.companion.model.ChatEvent

/** A local alert from an already authenticated, profile-scoped gateway connection. */
data class NativeNotification(val ping: WakePing, val dismiss: Boolean = false)

/**
 * Bounded deduplication for the native event dialect that the client actually decodes.
 *
 * Approvals have request IDs. Completions do not always have turn IDs, so Final/Completed
 * seal one observed turn until new activity, a new local submission, or distinct final
 * content. An indistinguishable final-only replay is not invented as another turn.
 * This is not a durable inbox, push subscription, or background delivery guarantee.
 */
class NativeNotificationPolicy(private val capacity: Int = 128) {
    init { require(capacity > 0) }

    private data class Owner(val origin: String, val profile: String, val session: String)
    private data class Turn(
        var localSubmission: String? = null,
        var finished: Boolean = false,
        var finalContent: String? = null,
        var outcome: String? = null,
        var notifiedRequest: String? = null,
        val requests: MutableSet<String> = linkedSetOf(),
    )
    private val turns = linkedMapOf<Owner, Turn>()

    @Synchronized
    fun accept(
        origin: String,
        profile: String,
        sessionId: String,
        event: ChatEvent,
        foregroundVisible: Boolean,
        title: String = "",
        localSubmission: String? = null,
    ): NativeNotification? {
        if (origin.isBlank() || profile.isBlank() || !DraftThread.isPersisted(sessionId)) return null
        val owner = Owner(origin.trim().trimEnd('/'), profile, sessionId)
        val turn = turns.getOrPut(owner) { Turn() }
        while (turns.size > capacity) turns.remove(turns.keys.first())
        if (localSubmission != null && localSubmission != turn.localSubmission) {
            turn.localSubmission = localSubmission
            turn.finished = false
            turn.finalContent = null
        }
        fun notice(type: String, dismiss: Boolean = false) = NativeNotification(
            WakePing(type, sessionId, profile, owner.origin, title = title), dismiss,
        )
        return when (event) {
            is ChatEvent.Approval -> {
                val request = event.prompt.requestId
                if (request.isBlank() || !turn.requests.add(request)) return null
                while (turn.requests.size > 32) turn.requests.remove(turn.requests.first())
                turn.finished = false
                if (foregroundVisible) return null
                turn.notifiedRequest = request
                notice(if (event.prompt.kind == "clarify") "clarify" else "approval.request")
            }
            is ChatEvent.PromptExpired -> {
                if (turn.notifiedRequest != event.requestId) return null
                turn.notifiedRequest = null
                notice("approval.request", dismiss = true)
            }
            is ChatEvent.Final, ChatEvent.Completed -> {
                val final = event as? ChatEvent.Final
                val outcome = if (final?.error?.isNotBlank() == true || final?.status in setOf("error", "failed")) "error" else "run.completed"
                // Retain only a bounded fingerprint, not message text, for duplicate detection.
                val content = final?.let {
                    if (it.text.isBlank() && it.error.isBlank()) null
                    else fingerprint("${it.status}\u0000${it.error}\u0000${it.text}")
                }
                val distinctFinal = content != null && turn.finalContent != null && content != turn.finalContent
                val errorUpgrade = outcome == "error" && turn.outcome != "error"
                if (turn.finished && !distinctFinal && !errorUpgrade) {
                    if (content != null) turn.finalContent = content
                    return null
                }
                turn.finished = true
                turn.finalContent = content
                turn.outcome = outcome
                turn.notifiedRequest = null
                if (foregroundVisible || final?.status in setOf("interrupted", "cancelled", "canceled")) null
                else notice(outcome)
            }
            is ChatEvent.Accepted,
            is ChatEvent.AssistantDelta,
            is ChatEvent.ReasoningDelta,
            is ChatEvent.Interim,
            is ChatEvent.ToolStarted,
            is ChatEvent.ToolCompleted -> {
                turn.finished = false
                turn.finalContent = null
                null
            }
            else -> null
        }
    }

    private fun fingerprint(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

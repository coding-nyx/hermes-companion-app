package app.hermes.companion

import app.hermes.companion.domain.WakePolicy
import app.hermes.companion.model.SessionRef

/** A notification tap may navigate; receiving a notification never does. */
internal object WakeNavigationPolicy {
    fun sameHost(first: String?, second: String?): Boolean =
        !first.isNullOrBlank() && !second.isNullOrBlank() &&
            hostKey(first)?.let { it == hostKey(second) } == true

    // The older fleet/display host key drops paths. Navigation must not do that:
    // /assistant-a and /assistant-b can be different credential-owning dashboards.
    private fun hostKey(origin: String): String? = WakePolicy.topicOwnerKey(origin)

    fun isCurrentConversation(state: CompanionState, request: DeepLinkRequest): Boolean {
        val origin = request.origin.ifBlank { state.origin.orEmpty() }
        if (!sameHost(state.origin, origin)) return false
        return if (request.roomId.isNotBlank()) state.openRoomId == request.roomId
        else !state.inRoom && state.activeProfileId == request.profileId && state.openSessionId == request.sessionId
    }

    fun requiresVoiceConfirmation(state: CompanionState, request: DeepLinkRequest): Boolean =
        state.isVoiceStreamActive && !isCurrentConversation(state, request)
}

/** A late roster refresh is data, never permission to undo a more recent selection. */
internal fun CompanionState.finishConnectionRoster(origin: String, profile: String, sessions: List<SessionRef>): CompanionState =
    if (!owns(origin, profile)) this else copy(loading = false, sessionsLoading = false, sessions = sessions)

/** A failed or superseded host connection must not replay an older notification tap. */
internal class PendingWakeNavigation {
    private var pending: DeepLinkRequest? = null

    fun enqueue(request: DeepLinkRequest) { pending = request }
    fun clear() { pending = null }
    fun connectingTo(origin: String) {
        if (!WakeNavigationPolicy.sameHost(pending?.origin, origin)) clear()
    }
    fun takeForHost(origin: String): DeepLinkRequest? {
        val request = pending ?: return null
        if (!WakeNavigationPolicy.sameHost(request.origin, origin)) return null
        pending = null
        return request
    }
}

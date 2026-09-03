package app.hermes.companion.domain

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Machine inventory (profile list, status) has no profile query — it is how we learn the roster.
 * Session/chat/config calls always carry profile. Omitting it on those is a data-leak class bug.
 */
object DashboardUrls {
    fun machine(origin: String, path: String): String {
        val base = origin.trim().trimEnd('/')
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "$base$normalized"
    }

    fun rest(origin: String, path: String, profileId: String): String {
        val id = ProfileScope.requireProfileId(profileId)
        val joiner = if (path.contains('?')) "&" else "?"
        return machine(origin, path) + "${joiner}profile=${enc(id)}"
    }

    fun ws(origin: String, profileId: String, ticket: String? = null, token: String? = null): String {
        val id = ProfileScope.requireProfileId(profileId)
        val base = origin.trim().trimEnd('/')
            .replace(Regex("^http://"), "ws://")
            .replace(Regex("^https://"), "wss://")
        val q = mutableListOf("profile=${enc(id)}")
        if (!ticket.isNullOrBlank()) q += "ticket=${enc(ticket)}"
        if (!token.isNullOrBlank()) q += "token=${enc(token)}"
        return "$base/api/ws?${q.joinToString("&")}"
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

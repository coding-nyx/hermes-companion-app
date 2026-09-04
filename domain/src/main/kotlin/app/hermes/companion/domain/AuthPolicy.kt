package app.hermes.companion.domain

import app.hermes.companion.model.DashboardStatus

object AuthPolicy {
    const val OIDC_MESSAGE = "use Tailscale + basic, or wait"

    fun needsPassword(status: DashboardStatus): Boolean = status.authRequired

    fun passwordProvider(providers: List<String>): String? {
        val names = providers.map { it.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty()) return "basic"
        val match = names.firstOrNull { name ->
            val n = name.lowercase()
            n == "basic" || n.contains("password") || n == "local"
        }
        return match
    }

    fun jsonLogin(provider: String, username: String, password: String): String =
        """{"provider":${json(provider)},"username":${json(username)},"password":${json(password)}}"""

    private fun json(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
        append('"')
    }
}

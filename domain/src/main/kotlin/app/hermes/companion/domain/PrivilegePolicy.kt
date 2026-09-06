package app.hermes.companion.domain

/**
 * Which operator actions need the user present (A12.3). Deny is always free;
 * sudo/secret and destructive commands need biometric or device PIN.
 */
object PrivilegePolicy {
    private val PRESENCE_KINDS = setOf("sudo", "secret", "credential", "credentials")

    private val DESTRUCTIVE = listOf(
        Regex("""\brm\s+-[A-Za-z]*r[A-Za-z]*f\b""", RegexOption.IGNORE_CASE),
        Regex("""\brm\s+-[A-Za-z]*f[A-Za-z]*r\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmkfs(\.\w+)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdd\b.+\b(if|of)=""", RegexOption.IGNORE_CASE),
        Regex("""\b(drop\s+(table|database)|truncate\s+table)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(shutdown|reboot|halt|poweroff)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bchmod\s+777\b""", RegexOption.IGNORE_CASE),
        Regex("""\|\s*(ba)?sh\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwipe\b""", RegexOption.IGNORE_CASE),
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;"""),
    )

    fun isDeny(choice: String): Boolean {
        val c = choice.trim().lowercase()
        return c == "deny" || c == "reject" || c == "cancel" || c == "no"
    }

    fun isDestructive(command: String): Boolean {
        val text = command.trim()
        if (text.isBlank()) return false
        return DESTRUCTIVE.any { it.containsMatchIn(text) }
    }

    fun requiresPresence(kind: String, command: String, choice: String = "once"): Boolean {
        if (isDeny(choice)) return false
        val k = kind.trim().lowercase()
        if (k in PRESENCE_KINDS) return true
        return isDestructive(command)
    }
}

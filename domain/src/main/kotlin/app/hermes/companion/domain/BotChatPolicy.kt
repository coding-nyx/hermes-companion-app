package app.hermes.companion.domain

/** Exact-title identity mirrors Hermes. New/reset inside a forever-chat must keep that identity. */
object BotChatPolicy {
    fun command(title: String?, text: String): String =
        if (title == "Bot Chat" && text.trim().split(Regex("\\s+"), limit = 2).first().lowercase() in setOf("/new", "/reset")) "/compact" else text
}

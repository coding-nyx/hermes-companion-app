package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BotChatPolicyTest {
    @Test fun canonicalNewAndResetKeepTheSameConversation() {
        assertEquals("/compact", BotChatPolicy.command("Bot Chat", "/new"))
        assertEquals("/compact", BotChatPolicy.command("Bot Chat", " /reset\nextra "))
        assertEquals("/new", BotChatPolicy.command("Ordinary chat", "/new"))
        assertEquals("/newspaper", BotChatPolicy.command("Bot Chat", "/newspaper"))
    }
}

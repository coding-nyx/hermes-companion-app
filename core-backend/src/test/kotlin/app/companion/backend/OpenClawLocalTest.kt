package app.companion.backend

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in smoke test against the operator's Mac. Reads secrets locally; never prints them. */
class OpenClawLocalTest {
    @Test fun localGatewayListsAgentsAndReadsAnIsolatedConversation() = runBlocking {
        assumeTrue(System.getenv("COMPANION_LIVE_OPENCLAW")=="1")
        val config=Json.parseToJsonElement(File(System.getProperty("user.home"),".openclaw/openclaw.json").readText()).jsonObject
        val auth=config.obj("gateway").obj("auth")
        val seedFile=File("/tmp/companion-prototype-live-seed")
        val backend=OpenClawBackend(AgentConnection.create(AgentRuntime.OPENCLAW,"http://127.0.0.1:18789","This Mac"),AgentCredentials(seedFile.readBytes(),auth.text("token"),auth.text("password")))
        try {
            val hello=backend.connect(); val bots=backend.bots(); assertTrue(bots.isNotEmpty())
            val bot=bots.first(); val thread=AgentThread("agent:${bot.id}:companion-prototype-validation",bot.id,bot.name)
            backend.history(thread)
            println("OpenClaw live: ${hello.version}; agents=${bots.size}; history=OK; chat=${hello.capabilities.chat}")
        } finally {backend.close()}
    }
}

package app.hermes.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.data.remote.*
import app.hermes.companion.domain.NativeGroupNames
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises production bot sockets on the phone. Only UUID-named fixture profiles are mutated. */
@RunWith(AndroidJUnit4::class)
class NativeBotsDeviceTest {
    @Test fun nativeBotsAndGroupsRoundTripAndCleanUpTheirFixtures() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val origin = requireNotNull(args.getString("nativeOrigin"))
        val owner = BotOwner(origin, args.getString("profile") ?: "default")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CompanionApp
        val credentials = app.operatorCreds.load(origin)
        val pool = HostClientPool()
        val client = pool.forOrigin(origin).apply { useLoopback(); sessionToken = credentials?.sessionToken?.takeIf { it.isNotBlank() } }
        if (client.sessionToken == null) assertNotNull(client.adoptLoopbackToken(origin))
        val api = DashboardNativeBotsApi(pool)
        val suffix = UUID.randomUUID().toString().take(8)
        val profiles = listOf("s22-$suffix-a", "s22-$suffix-b")
        val roomId = "s22-$suffix-group"
        var groupCreated = false
        try {
            val caps = api.capabilities(owner)
            assertTrue("Native group worker unavailable", caps.canCreate)
            val template = api.roster(owner).bots.first { it.owner.profile == "default" }
            check(template.model.isNotBlank() && template.provider.isNotBlank()) { "Default bot needs a configured model/provider for live reply verification" }
            profiles.forEachIndexed { i, profile -> api.create(owner, BotEdit(profile, "S22 test ${i + 1}",
                "Isolated device verification. Do not use tools.", model = template.model, provider = template.provider)) }
            val roster = api.roster(owner)
            val first = roster.bots.first { it.owner.profile == profiles.first() }
            val canonical = api.canonical(first, "s22-$suffix-canonical")
            assertEquals(canonical.id, api.canonical(first, "s22-$suffix-second-open").id)
            api.configure(first, BotEdit(first.owner.profile, "S22 updated", "Isolated device verification", section = "S22 verification"))
            val edited = api.roster(owner).bots.first { it.owner.profile == first.owner.profile }
            assertEquals("S22 updated", edited.title); assertEquals("S22 verification", edited.section)
            val members = profiles.mapIndexed { index, profile -> NativeGroupMember("m$index", profile, "test$index", "S22 test ${index + 1}") }
            val automaticName = NativeGroupNames.resolve("", members.map { it.name })
            val create = NativeBotOperation("s22-$suffix-create", owner, "create-group", roomId, automaticName, members = members)
            val group = api.createGroup(create); groupCreated = true
            assertEquals(roomId, group.id); assertEquals(2, group.members.size)
            assertEquals(automaticName, group.name)
            assertEquals(group.id, api.createGroup(create).id)
            assertEquals("S22 renamed", api.rename(owner, roomId, "S22 renamed", "s22-$suffix-rename").name)
            val send = NativeBotOperation("s22-$suffix-message", owner, "send", roomId,
                "This is an isolated Companion S22 integration test. Reply exactly S22_OK. Do not use tools, contact anyone, or change files.", "s22-$suffix-thread")
            val event = api.send(send)
            assertEquals(event.id, api.send(send).id)
            assertEquals(send.thread, event.thread)
            withTimeout(120_000) {
                while (true) {
                    val page = api.log(owner, roomId, 0)
                    assertEquals(1, page.events.count { it.id == event.id })
                    if (page.events.any { it.kind == "message.member" && it.text.contains("S22_OK") }) break
                    check(page.events.none { it.kind == "turn.failed" }) { "Native group member failed: ${page.events.filter { it.kind == "turn.failed" }.map { it.text }}" }
                    delay(1_000)
                }
            }
            api.stop(owner, roomId, "s22-$suffix-stop")
            api.disband(owner, roomId, "s22-$suffix-disband"); groupCreated = false
            assertTrue(api.groups(owner).none { it.id == roomId })
        } finally {
            withContext(NonCancellable) {
                if (groupCreated) api.disband(owner, roomId, "s22-$suffix-cleanup")
                // Deletion is limited to this test's exact, randomly generated profile names.
                withContext(Dispatchers.IO) {
                    val http = OkHttpClient()
                    profiles.forEach { profile ->
                        val request = Request.Builder().url("$origin/api/profiles/$profile").delete()
                            .header("Authorization", "Bearer ${client.sessionToken.orEmpty()}").build()
                        http.newCall(request).execute().use { response -> check(response.isSuccessful || response.code == 404) { "Fixture cleanup failed: ${response.code}" } }
                    }
                }
                pool.evict(origin)
            }
        }
    }
}

package app.hermes.companion

import app.hermes.companion.data.local.NativeBotOperationStore
import app.hermes.companion.data.remote.NativeBotsApi
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotModeControllerTest {
    private val owner = BotOwner("https://host/a", "ash")
    private val members = listOf(NativeGroupMember("a", "ash", "ash", "Ash"), NativeGroupMember("b", "research", "research", "Research"))
    private fun group(id: String = "group") = NativeGroup(id, id, members, "install", 1)
    private fun event(seq: Long, id: String = "e$seq") = NativeGroupEvent(id, seq, "message.member", "ash", "text $seq", "topic")
    private open inner class Api : NativeBotsApi {
        var pages: suspend (String, Long) -> NativeGroupPage = { _, after -> NativeGroupPage(emptyList(), after, after, false, "install", 1) }
        var status: suspend (String) -> NativeGroupSnapshot = { NativeGroupSnapshot(group(it)) }
        var submit: suspend (NativeBotOperation) -> NativeGroupEvent = { event(1, BotModeController.userEventId(it.id)) }
        var canon: suspend (NativeBot, String) -> SessionRef = { bot, _ -> SessionRef("canonical", bot.owner.profile, "Bot Chat", 0) }
        val sends = mutableListOf<NativeBotOperation>(); val approvals = mutableListOf<NativeGroupAction>()
        val creates = mutableListOf<NativeBotOperation>()
        override suspend fun roster(owner: BotOwner) = BotRoster(listOf(NativeBot(owner, "Ash"), NativeBot(owner.copy(profile = "research"), "Research")))
        override suspend fun configure(bot: NativeBot, edit: BotEdit) {}
        override suspend fun create(owner: BotOwner, edit: BotEdit) {}
        override suspend fun canonical(bot: NativeBot, operationId: String) = canon(bot, operationId)
        override suspend fun capabilities(owner: BotOwner) = NativeGroupCapabilities(true,
            setOf("groups.create", "groups.list", "groups.send", "groups.approve", "groups.retry", "groups.stop", "groups.disband", "groups.rename"), setOf("idempotent_send"), setOf("ash", "research"))
        override suspend fun groups(owner: BotOwner) = listOf(group())
        override suspend fun createGroup(operation: NativeBotOperation): NativeGroup { creates += operation; return group(operation.target).copy(name = operation.text) }
        override suspend fun group(owner: BotOwner, id: String) = status(id)
        override suspend fun log(owner: BotOwner, id: String, after: Long) = pages(id, after)
        override suspend fun send(operation: NativeBotOperation): NativeGroupEvent { sends += operation; return submit(operation) }
        override suspend fun rename(owner: BotOwner, id: String, name: String, eventId: String) = group(id).copy(name = name)
        override suspend fun stop(owner: BotOwner, id: String, cancelId: String) {}
        override suspend fun disband(owner: BotOwner, id: String, cancelId: String) {}
        override suspend fun approve(owner: BotOwner, id: String, action: NativeGroupAction, choice: String) { approvals += action }
        override suspend fun retry(owner: BotOwner, id: String, action: NativeGroupAction) {}
    }
    private inner class Fixture(test: TestScope, val api: Api = Api(), val journal: NativeBotOperationStore = NativeBotOperationStore()) : AutoCloseable {
        val state = MutableStateFlow(CompanionState(origin = owner.origin, activeProfileId = owner.profile, tab = MainTab.BOTS))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val controller = BotModeController(state, scope, api, journal, io = StandardTestDispatcher(test.testScheduler))
        override fun close() { scope.cancel() }
    }
    @Test fun standaloneAndNativeMethodRejectionsBlockBotsUntilAnExplicitSuccessfulRefresh() = runTest {
        for (code in listOf("rpc_404", "rpc_-32601")) {
            var calls = 0
            var upgraded = false
            val api = object : Api() {
                override suspend fun roster(owner: BotOwner): BotRoster {
                    calls++
                    if (!upgraded) throw DashboardException(code, "unknown method profiles.list")
                    return super.roster(owner)
                }
            }
            Fixture(this, api).use { f ->
                runCurrent()
                assertTrue(f.state.value.botMode.unsupported)
                assertFalse(f.state.value.botMode.loading)
                assertTrue(f.state.value.botMode.error!!.contains(if (code == "rpc_404") "Companion standalone" else "Update the host"))
                f.state.value = f.state.value.copy(tab = MainTab.THREADS); runCurrent()
                f.state.value = f.state.value.copy(tab = MainTab.BOTS); runCurrent()
                assertEquals(1, calls)
                upgraded = true
                f.controller.refresh(); runCurrent()
                assertEquals(2, calls)
                assertFalse(f.state.value.botMode.unsupported)
                assertNull(f.state.value.botMode.error)
                assertEquals(2, f.state.value.botMode.bots.size)
            }
        }
    }
    @Test fun missingResourcesAndAuthenticationErrorsDoNotMasqueradeAsAnUnsupportedGateway() = runTest {
        for ((code, message) in listOf("rpc_404" to "unknown_session", "http_401" to "Unauthorized", "ws_failed" to "Network unavailable")) {
            val api = object : Api() {
                override suspend fun roster(owner: BotOwner): BotRoster = throw DashboardException(code, message)
            }
            Fixture(this, api).use { f ->
                runCurrent()
                assertFalse(f.state.value.botMode.unsupported)
                assertEquals(message, f.state.value.botMode.error)
            }
        }
    }
    @Test fun everyReplayPageIsDrainedBeforeAdvancingTheCursor() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            val cursors = mutableListOf<Long>()
            f.api.pages = { _, after -> cursors += after; when (after) {
                0L -> NativeGroupPage(listOf(event(1), event(2)), 2, 3, true, "install", 1)
                else -> NativeGroupPage(listOf(event(3)), 3, 3, false, "install", 1)
            } }
            f.controller.openGroup(group()); runCurrent()
            assertEquals(listOf(0L, 2L), cursors); assertEquals(listOf(1L, 2L, 3L), f.state.value.botMode.events.map { it.sequence })
            assertEquals(3L, f.state.value.botMode.cursor)
        }
    }
    @Test fun unnamedCreationPersistsTheGeneratedNameAndRecoveryDoesNotRenameIt() = runTest {
        val journal = NativeBotOperationStore()
        val api = object : Api() {
            override suspend fun createGroup(operation: NativeBotOperation): NativeGroup {
                creates += operation
                throw java.io.IOException("lost create acknowledgment")
            }
        }
        Fixture(this, api, journal).use { f ->
            runCurrent(); f.controller.createGroup("", listOf("ash", "research")); runCurrent()
            val pending = journal.list(owner).single()
            assertEquals("Ash, Research", pending.text)
            assertEquals(pending, api.creates.single())
        }
        Fixture(this, api, journal).use { f ->
            runCurrent(); val pending = journal.list(owner).single()
            f.state.value = f.state.value.copy(botMode = f.state.value.botMode.copy(groups = listOf(group().copy(name = pending.text))))
            f.controller.recover(pending); runCurrent()
            assertEquals(api.creates[0], api.creates[1])
            assertEquals("Ash, Research", api.creates[1].text)
        }
    }
    @Test fun nonAdvancingPageAndAuthorityMismatchNeverConsumeHistory() = runTest {
        Fixture(this).use { f ->
            runCurrent(); f.api.pages = { _, _ -> NativeGroupPage(listOf(event(1)), 0, 2, true, "wrong-install", 1) }
            f.controller.openGroup(group()); runCurrent()
            assertEquals(0L, f.state.value.botMode.cursor); assertTrue(f.state.value.botMode.events.isEmpty()); assertNotNull(f.state.value.botMode.error)
        }
    }
    @Test fun lateHistoryCannotCrossGatewaysWithIdenticalGroupIds() = runTest {
        Fixture(this).use { f ->
            runCurrent(); val reply = CompletableDeferred<NativeGroupPage>()
            f.api.pages = { _, _ -> withContext(NonCancellable) { reply.await() } }
            f.controller.openGroup(group()); runCurrent()
            f.state.value = f.state.value.copy(origin = "https://host/b"); runCurrent()
            reply.complete(NativeGroupPage(listOf(event(1)), 1, 1, false, "install", 1)); runCurrent()
            assertTrue(f.state.value.botMode.events.isEmpty()); assertEquals("https://host/b", f.state.value.botMode.owner?.origin)
        }
    }
    @Test fun doubleSendIsAdmittedOnceAndLaterTypingSurvivesItsReceipt() = runTest {
        Fixture(this).use { f ->
            runCurrent(); f.controller.openGroup(group()); runCurrent()
            val reply = CompletableDeferred<NativeGroupEvent>(); f.api.submit = { reply.await() }
            f.controller.draft("first"); f.controller.send(); f.controller.send(); runCurrent()
            assertEquals(1, f.api.sends.size); assertEquals(1, f.journal.list(owner).size)
            f.controller.draft("next"); reply.complete(event(1)); runCurrent()
            assertEquals("next", f.state.value.botMode.draft); assertTrue(f.journal.list(owner).isEmpty())
        }
    }
    @Test fun lostAckRecoveryUsesSameEventAndThreadAfterControllerRecreation() = runTest {
        val journal = NativeBotOperationStore(); val api = Api()
        Fixture(this, api, journal).use { f ->
            runCurrent(); f.controller.openGroup(group()); runCurrent()
            api.submit = { throw java.io.IOException("lost ack") }
            f.controller.draft("once"); f.controller.reply("existing-topic"); f.controller.send(); runCurrent()
            assertEquals("", f.state.value.botMode.draft); assertEquals(1, journal.list(owner).size)
        }
        Fixture(this, api, journal).use { f ->
            runCurrent(); val pending = f.state.value.botMode.pending.single()
            api.submit = { event(1, BotModeController.userEventId(it.id)) }
            f.controller.openGroup(group()); runCurrent(); f.controller.recover(pending); runCurrent()
            assertEquals(api.sends[0], api.sends[1]); assertEquals("existing-topic", api.sends[1].thread)
            assertTrue(journal.list(owner).isEmpty())
        }
    }
    @Test fun replayReceiptResolvesUnknownDeliveryWithoutSendingAgain() = runTest {
        val journal = NativeBotOperationStore()
        val pending = NativeBotOperation("key", owner, "send", "group", "text", "thread")
        journal.save(pending)
        Fixture(this, journal = journal).use { f ->
            runCurrent(); f.api.pages = { _, _ -> NativeGroupPage(listOf(event(1, BotModeController.userEventId("key"))), 1, 1, false, "install", 1) }
            f.controller.openGroup(group()); runCurrent()
            assertTrue(journal.list(owner).isEmpty()); assertTrue(f.api.sends.isEmpty())
        }
    }
    @Test fun botDoubleTapAndLateCompletionNeverNavigateAfterLeavingBots() = runTest {
        Fixture(this).use { f ->
            runCurrent(); val response = CompletableDeferred<SessionRef>(); var calls = 0; var navigations = 0
            f.api.canon = { _, _ -> calls++; response.await() }
            val bot = f.state.value.botMode.bots.first()
            f.controller.openBot(bot) { _, _ -> navigations++ }; f.controller.openBot(bot) { _, _ -> navigations++ }; runCurrent()
            f.state.value = f.state.value.copy(tab = MainTab.GATEWAY); runCurrent()
            response.complete(SessionRef("canonical", "ash", "Bot Chat", 0)); runCurrent()
            assertEquals(1, calls); assertEquals(0, navigations)
        }
    }
    @Test fun canonicalCreationClaimSurvivesUnknownOutcome() = runTest {
        Fixture(this).use { f ->
            runCurrent(); val keys = mutableListOf<String>()
            f.api.canon = { _, key -> keys += key; throw java.io.IOException("lost create") }
            val bot = f.state.value.botMode.bots.first()
            f.controller.openBot(bot) { _, _ -> }; runCurrent(); f.controller.openBot(bot) { _, _ -> }; runCurrent()
            assertEquals(2, keys.size); assertEquals(keys[0], keys[1]); assertEquals(1, f.journal.list(owner).size)
        }
    }
    @Test fun approvalDoubleTapIsScopedToTheExactPendingAttempt() = runTest {
        Fixture(this).use { f ->
            runCurrent(); val action = NativeGroupAction("approval", "m", "t", 3, "request")
            f.api.status = { NativeGroupSnapshot(group(), actions = listOf(action)) }
            f.controller.openGroup(group()); runCurrent()
            f.controller.approve(action, "once"); f.controller.approve(action, "once"); runCurrent()
            assertEquals(listOf(action), f.api.approvals)
            f.controller.approve(action.copy(generation = 2), "once"); runCurrent()
            assertEquals(1, f.api.approvals.size)
        }
    }
}

package app.hermes.companion

import app.hermes.companion.data.local.*
import app.hermes.companion.data.remote.DashboardException
import app.hermes.companion.data.remote.NativeBotsApi
import app.hermes.companion.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import java.util.UUID

/** Native groups deliberately have a separate namespace from plugin-backed Companion rooms. */
data class BotModeState(
    val owner: BotOwner? = null, val bots: List<NativeBot> = emptyList(), val groups: List<NativeGroup> = emptyList(),
    val capabilities: NativeGroupCapabilities? = null, val loading: Boolean = false,
    val error: String? = null, val groupError: String? = null, val unsupported: Boolean = false,
    val selected: NativeGroupSnapshot? = null, val events: List<NativeGroupEvent> = emptyList(),
    val cursor: Long = 0, val draft: String = "", val replyThread: String? = null,
    val busy: Set<String> = emptySet(), val pending: List<NativeBotOperation> = emptyList(),
    val savedBotVersion: Int = 0,
    val groupVerified: Boolean = false,
)

class BotModeController(
    private val state: MutableStateFlow<CompanionState>, private val scope: CoroutineScope,
    private val api: NativeBotsApi, private val journal: NativeBotOperationStore,
    private val drafts: ComposerDraftStore? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var epoch = 0L
    private var rosterJob: Job? = null
    private var pollJob: Job? = null
    private var opening: String? = null
    private var draftVersion = 0L
    private fun owner() = state.value.let { st -> st.origin?.let { origin -> st.activeProfileId?.let { BotOwner(app.hermes.companion.domain.GatewayBook.key(origin), it) } } }
    private fun owns(owner: BotOwner, generation: Long = epoch) = owner() == owner && epoch == generation
    private fun publish(owner: BotOwner, generation: Long, change: (BotModeState) -> BotModeState) {
        state.update { if (!owns(owner, generation)) it else it.copy(botMode = change(it.botMode)) }
    }
    private fun id() = UUID.randomUUID().toString()
    private fun unsupported(t: Throwable) = t is DashboardException && (t.code == "rpc_-32601" ||
        (t.code == "rpc_404" && t.message.orEmpty().matches(Regex("unknown method (profiles|groups)\\.[a-z_]+"))))
    private fun failure(t: Throwable) = when {
        unsupported(t) && (t as DashboardException).code == "rpc_404" ->
            "This gateway uses Companion standalone. Bots and group chats need a native Hermes dashboard. Your existing conversations are available in Chats."
        unsupported(t) -> "This Hermes host does not support this feature. Update the host and reconnect."
        else -> t.message ?: "Could not reach Hermes. Try refreshing."
    }
    private fun draftOwner(owner: BotOwner, group: String) = DraftOwner(owner.origin, owner.profile, "native-group:$group")

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.map { Triple(owner(), it.tab, it.botMode.selected?.group?.id) }.distinctUntilChanged().collect { (owner, tab, _) ->
                if (state.value.botMode.owner != owner) {
                    epoch++; rosterJob?.cancel(); pollJob?.cancel(); opening = null
                    state.update { it.copy(botMode = BotModeState(owner = owner)) }
                }
                if (tab != MainTab.BOTS) { pollJob?.cancel(); pollJob = null }
                else if (owner != null) {
                    if (state.value.botMode.bots.isEmpty() && !state.value.botMode.loading && !state.value.botMode.unsupported) refresh()
                    if (state.value.botMode.selected != null && pollJob?.isActive != true) startPolling()
                }
            }
        }
    }

    fun refresh() {
        val owner = owner() ?: return; val generation = epoch
        rosterJob?.cancel()
        publish(owner, generation) { it.copy(loading = true, error = null) }
        rosterJob = scope.launch {
            try {
                val roster = api.roster(owner)
                val pending = withContext(io) { journal.list(owner) }
                publish(owner, generation) { it.copy(owner = owner, bots = roster.bots, pending = pending, unsupported = false) }
                try {
                    val caps = api.capabilities(owner)
                    val groups = if ("groups.list" in caps.methods) api.groups(owner) else emptyList()
                    publish(owner, generation) { it.copy(capabilities = caps, groups = groups, groupError = null) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    publish(owner, generation) { it.copy(capabilities = null, groupError = failure(t)) }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                publish(owner, generation) { it.copy(error = failure(t), unsupported = unsupported(t)) }
            } finally { publish(owner, generation) { it.copy(loading = false) } }
        }
    }

    fun saveBot(edit: BotEdit, bot: NativeBot? = null) {
        val owner = owner() ?: return; val generation = epoch
        if (state.value.botMode.busy.isNotEmpty()) return
        publish(owner, generation) { it.copy(busy = it.busy + "save-bot", error = null) }
        scope.launch {
            try {
                if (bot == null) api.create(owner, edit) else { require(bot.owner.origin == owner.origin); api.configure(bot, edit) }
                if (owns(owner, generation)) {
                    publish(owner, generation) { it.copy(savedBotVersion = it.savedBotVersion + 1) }
                    refresh()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                publish(owner, generation) { it.copy(error = failure(t) + if (bot == null) " Refresh the roster before trying creation again." else "") }
            } finally { publish(owner, generation) { it.copy(busy = it.busy - "save-bot") } }
        }
    }

    /** One durable create claim per profile; no timeout can mint a different forever-chat. */
    fun openBot(bot: NativeBot, navigate: (BotOwner, SessionRef) -> Unit) {
        if (owner()?.origin != bot.owner.origin || opening != null) return
        val requestOwner = owner() ?: return; val generation = epoch
        opening = bot.owner.profile
        publish(requestOwner, generation) { it.copy(busy = it.busy + "open:${bot.owner.profile}", error = null) }
        scope.launch {
            try {
                val operation = withContext(io) {
                    journal.list(bot.owner).firstOrNull { it.kind == "canonical" } ?: NativeBotOperation(id(), bot.owner, "canonical", bot.owner.profile).also(journal::save)
                }
                val session = api.canonical(bot, operation.id)
                withContext(io) { journal.remove(bot.owner, operation.id) }
                if (owns(requestOwner, generation) && state.value.tab == MainTab.BOTS) navigate(bot.owner, session)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                publish(requestOwner, generation) { it.copy(error = failure(t)) }
            } finally {
                if (owns(requestOwner, generation)) opening = null
                publish(requestOwner, generation) { it.copy(busy = it.busy - "open:${bot.owner.profile}") }
            }
        }
    }

    fun createGroup(name: String, profiles: List<String>) {
        val owner = owner() ?: return
        val mode = state.value.botMode
        if (mode.capabilities?.canCreate != true || mode.busy.isNotEmpty()) return
        require(profiles.distinct().size in 2..6)
        val allowed = mode.capabilities.seatableProfiles ?: mode.bots.map { it.owner.profile }.toSet()
        require(profiles.all { it in allowed }) { "A selected bot cannot join this gateway's groups" }
        val handles = mutableSetOf("all", "everyone")
        val members = profiles.distinct().mapIndexed { index, profile ->
            val bot = mode.bots.first { it.owner.profile == profile }
            var handle = if (profile == "default") "hermes" else profile
            while (!handles.add(handle.lowercase())) handle += "-$index"
            NativeGroupMember("member-$index", profile, handle, bot.title.ifBlank { profile })
        }
        val resolvedName = app.hermes.companion.domain.NativeGroupNames.resolve(name, members.map { it.name }, mode.groups.map { it.name }.toSet())
        val op = NativeBotOperation(id(), owner, "create-group", "group-${id()}", resolvedName, members = members)
        runCreate(op)
    }
    private fun runCreate(op: NativeBotOperation) {
        val generation = epoch
        mutate(op.owner, "create-group") {
        withContext(io) { journal.save(op) }
        val group = api.createGroup(op)
        withContext(io) { journal.remove(op.owner, op.id) }
        if (owns(op.owner, generation)) { openGroup(group); refresh() }
        }
    }
    fun recover(op: NativeBotOperation) {
        if (owner() != op.owner) return
        // Both methods are natively idempotent. Recovery reuses the entire persisted envelope.
        when (op.kind) { "create-group" -> runCreate(op); "send" -> submit(op) }
    }

    fun openGroup(group: NativeGroup) {
        val owner = owner() ?: return
        epoch++; val generation = epoch; pollJob?.cancel()
        draftVersion++; val openingDraftVersion = draftVersion
        publish(owner, generation) { it.copy(selected = NativeGroupSnapshot(group), events = emptyList(), cursor = 0,
            draft = "", replyThread = null, error = null, busy = emptySet(), loading = false, groupVerified = false) }
        scope.launch {
            try {
                val (saved, pending) = withContext(io) {
                    drafts?.load(draftOwner(owner, group.id))?.text.orEmpty() to journal.list(owner)
                }
                val recovered = if (pending.any { it.kind == "send" && it.target == group.id && it.text == saved.trim() }) "" else saved
                publish(owner, generation) { it.copy(draft = if (draftVersion == openingDraftVersion) recovered else it.draft, pending = pending) }
                if (owns(owner, generation) && pollJob?.isActive != true) startPolling()
            } catch (t: Throwable) { if (t is CancellationException) throw t; publish(owner, generation) { it.copy(error = failure(t)) } }
        }
    }
    fun closeGroup() { epoch++; pollJob?.cancel(); pollJob = null; state.update { it.copy(botMode = it.botMode.copy(selected = null, events = emptyList(), cursor = 0, draft = "", replyThread = null, busy = emptySet(), loading = false)) } }
    fun draft(text: String) {
        val owner = owner() ?: return; val group = state.value.botMode.selected?.group ?: return
        draftVersion++
        try {
            drafts?.save(draftOwner(owner, group.id), ComposerDraft(text))
            state.update { it.copy(botMode = it.botMode.copy(draft = text)) }
        } catch (t: Throwable) { state.update { it.copy(botMode = it.botMode.copy(draft = text, error = failure(t))) } }
    }
    fun reply(thread: String?) { state.update { it.copy(botMode = it.botMode.copy(replyThread = thread)) } }
    fun mention(handle: String) { draft(state.value.botMode.draft.let { if (it.isBlank()) "@$handle " else "$it @$handle " }) }
    fun send() {
        val owner = owner() ?: return; val mode = state.value.botMode; val group = mode.selected?.group ?: return
        val text = mode.draft.trim(); if (text.isEmpty() || mode.busy.isNotEmpty() || !mode.groupVerified || "groups.send" !in mode.capabilities?.methods.orEmpty()) return
        if (text.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
            state.update { it.copy(botMode = it.botMode.copy(error = "Group messages must be at most 64 KB. Shorten this message before sending.")) }
            return
        }
        if (mode.pending.any { it.target == group.id && it.kind == "send" && it.text == text }) return
        submit(NativeBotOperation(id(), owner, "send", group.id, text, mode.replyThread ?: "thread-${id()}"))
    }
    private fun submit(op: NativeBotOperation) {
        val generation = epoch
        mutate(op.owner, "send") {
        withContext(io) { journal.save(op) }
        // Admission precedes clearing the submitted draft. Later text remains untouched.
        if (owns(op.owner, generation) && state.value.botMode.selected?.group?.id == op.target && state.value.botMode.draft.trim() == op.text) draft("")
        val event = api.send(op)
        withContext(io) { journal.remove(op.owner, op.id) }
        if (owns(op.owner, generation) && state.value.botMode.selected?.group?.id == op.target) {
            state.update { it.copy(botMode = it.botMode.copy(events = (it.botMode.events + event).distinctBy { e -> e.id }.sortedBy { e -> e.sequence })) }
        }
        }
    }

    private fun startPolling() {
        val owner = owner() ?: return; val generation = epoch; val group = state.value.botMode.selected?.group ?: return
        pollJob?.cancel()
        pollJob = scope.launch {
            var backoff = 2_000L
            while (owns(owner, generation) && state.value.tab == MainTab.BOTS && state.value.botMode.selected?.group?.id == group.id) {
                try { syncGroup(owner, generation, group.id); backoff = 2_000; delay(2_000) }
                catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    publish(owner, generation) { it.copy(error = failure(t), groupVerified = false) }
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
    }
    private suspend fun syncGroup(owner: BotOwner, generation: Long, groupId: String) {
        val snapshot = api.group(owner, groupId)
        if (!owns(owner, generation)) return
        val selected = state.value.botMode.selected?.group ?: return
        if (snapshot.group.authority != selected.authority || snapshot.group.epoch != selected.epoch)
            throw DashboardException("group_authority", "Group authority changed. Close and reopen the group to reconcile its history.")
        publish(owner, generation) { it.copy(selected = snapshot) }
        var pages = 0
        do {
            val cursor = state.value.botMode.cursor
            val page = api.log(owner, groupId, cursor)
            if (!owns(owner, generation)) return
            if (page.authority != selected.authority || page.epoch != selected.epoch || page.cursor < cursor ||
                page.events.zipWithNext().any { (a, b) -> b.sequence <= a.sequence } ||
                page.events.any { it.sequence <= cursor || it.sequence > page.cursor } || page.cursor > page.latest || (page.hasMore && page.cursor <= cursor))
                throw DashboardException("group_cursor", "Host returned inconsistent group history. Refresh before continuing.")
            val pending = withContext(io) {
                val records = journal.list(owner)
                val ids = page.events.map { it.id }.toSet()
                records.filter { it.kind == "send" && it.target == groupId && userEventId(it.id) in ids }.forEach { journal.remove(owner, it.id) }
                journal.list(owner)
            }
            publish(owner, generation) { it.copy(events = (it.events + page.events).distinctBy { e -> e.id }.sortedBy { e -> e.sequence }, cursor = page.cursor, pending = pending, error = null, groupVerified = !page.hasMore) }
            pages++
            if (!page.hasMore || pages >= 100) break
        } while (owns(owner, generation))
    }

    private fun mutate(owner: BotOwner, key: String, block: suspend () -> Unit) {
        if (owner() != owner || state.value.botMode.busy.isNotEmpty()) return
        val generation = epoch
        publish(owner, generation) { it.copy(busy = it.busy + key, error = null) }
        scope.launch {
            try { block() }
            catch (t: Throwable) { if (t is CancellationException) throw t; publish(owner, generation) { it.copy(error = failure(t) + " Check the group before repeating the action.") } }
            finally {
                try {
                    val pending = withContext(NonCancellable + io) { journal.list(owner) }
                    publish(owner, generation) { it.copy(busy = it.busy - key, pending = pending) }
                } catch (t: Throwable) { publish(owner, generation) { it.copy(busy = it.busy - key, error = "Pending operation storage is unavailable. ${failure(t)}") } }
            }
        }
    }
    private fun selectedAction(key: String, method: String, work: suspend (BotOwner, String) -> Unit) {
        val owner = owner() ?: return; val mode = state.value.botMode; val group = mode.selected?.group ?: return
        if (method !in mode.capabilities?.methods.orEmpty()) return
        if (method in setOf("groups.approve", "groups.retry", "groups.rename") && !mode.groupVerified) return
        mutate(owner, key) { work(owner, group.id) }
    }
    fun rename(name: String) { if (name.isBlank()) return; selectedAction("rename", "groups.rename") { owner, group -> api.rename(owner, group, name, id()) } }
    fun stop() = selectedAction("stop", "groups.stop") { owner, group -> api.stop(owner, group, id()) }
    fun disband() = selectedAction("disband", "groups.disband") { owner, group -> api.disband(owner, group, id()); if (owner() == owner) { closeGroup(); refresh() } }
    fun approve(action: NativeGroupAction, choice: String) = selectedAction(action.key, "groups.approve") { owner, group ->
        if (state.value.botMode.selected?.actions?.contains(action) == true) api.approve(owner, group, action, choice)
    }
    fun retry(action: NativeGroupAction) = selectedAction(action.key, "groups.retry") { owner, group ->
        if (state.value.botMode.selected?.actions?.contains(action) == true) api.retry(owner, group, action)
    }
    companion object {
        fun userEventId(id: String) = "user:" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

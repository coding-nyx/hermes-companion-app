package app.hermes.desk.providers

import app.hermes.desk.HermesBackend
import app.hermes.desk.feature.*
import app.hermes.companion.model.*

/** Select once per connect; coordinator invalidates old work before changing this delegate. */
class DeskProviderRouter(val hermes: HermesBackend, private val chatgpt: ChatGptBackend,
    private val grokFactory: (String) -> DeskBackend) : DeskBackend {
    private var active: DeskBackend = hermes
    override val label get() = active.label
    override val capabilities get() = active.capabilities
    override suspend fun connect(connection: DeskConnection): List<ProfileRef> {
        active = when {
            connection.origin == ChatGptBackend.ORIGIN -> chatgpt
            GrokBackend.isOrigin(connection.origin) -> grokFactory(connection.origin)
            else -> hermes
        }
        return active.connect(connection)
    }
    override suspend fun selectProfile(profile: String) = active.selectProfile(profile)
    override suspend fun history(binding: DeskBinding) = active.history(binding)
    override suspend fun createThread(profile: String) = active.createThread(profile)
    override fun events(profile: String) = active.events(profile)
    override fun belongsTo(binding: DeskBinding, eventSession: String?) = active.belongsTo(binding, eventSession)
    override fun turn(binding: DeskBinding, text: String) = active.turn(binding, text)
    override suspend fun pending(binding: DeskBinding) = active.pending(binding)
    override fun runtime(binding: DeskBinding) = active.runtime(binding)
    override suspend fun respond(binding: DeskBinding, prompt: ApprovalPrompt, choice: String) = active.respond(binding, prompt, choice)
    override suspend fun interrupt(binding: DeskBinding) = active.interrupt(binding)
    override suspend fun context(binding: DeskBinding) = active.context(binding)
    override suspend fun compact(binding: DeskBinding) = active.compact(binding)
    override suspend fun stageImage(binding: DeskBinding, jpeg: ByteArray) = active.stageImage(binding, jpeg)
    override suspend fun settings(binding: DeskBinding) = active.settings(binding)
    override suspend fun setting(binding: DeskBinding, key: String, value: String, confirmed: Boolean) = active.setting(binding, key, value, confirmed)
    override fun close() { active.close() }
}

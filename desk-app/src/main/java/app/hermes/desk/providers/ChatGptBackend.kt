package app.hermes.desk.providers

import app.hermes.companion.model.*
import app.hermes.desk.feature.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Native Responses transport. Never uses ChatGPT web endpoints or imports its conversations. */
class ResponsesTransport(private val http: OkHttpClient = ChatGptAuth.secureHttp(),
    private val endpoint: String = "https://api.openai.com/v1/responses", private val service: String = "ChatGPT") {
    fun events(body: JSONObject, bearer: String): Flow<JSONObject> = callbackFlow {
        val call = http.newCall(Request.Builder().url(endpoint).header("Authorization", "Bearer $bearer")
            .header("Accept", "text/event-stream").post(body.toString().toRequestBody("application/json".toMediaType())).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(IOException("$service connection ended. Check history before retrying.")) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) { close(IOException("$service request failed (${response.code}). Reconnect your account if authorization expired.")); return }
                    try {
                        val source = response.body?.source() ?: error("$service returned no stream")
                        val data = StringBuilder(); var bytes = 0L
                        fun dispatch() {
                            if (data.isEmpty()) return
                            val payload = data.toString().trimEnd('\n'); data.clear()
                            if (payload != "[DONE]") check(trySend(JSONObject(payload)).isSuccess) { "$service stream was cancelled" }
                        }
                        while (!source.exhausted()) {
                            val line = source.readUtf8LineStrict(262_144)
                            bytes += line.length
                            check(bytes <= 2_000_000) { "$service response exceeded the conversation limit" }
                            if (line.isEmpty()) dispatch()
                            else if (line.startsWith("data:")) { data.append(line.substring(5).removePrefix(" ")).append('\n'); check(data.length <= 262_144) }
                        }
                        dispatch(); close()
                    } catch (_: Exception) { close(IOException("$service stream ended before it could be confirmed. Check history before retrying.")) }
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(kotlinx.coroutines.channels.Channel.UNLIMITED)
}

/** Local, encrypted, account-scoped conversations; HTTP includes the complete text history. */
class ChatGptBackend(private val vault: ProviderVault, private val auth: ChatGptAuth,
    private val http: OkHttpClient = ChatGptAuth.secureHttp(), private val transport: ResponsesTransport = ResponsesTransport(),
    override val label: String = "ChatGPT", private val origin: String = ORIGIN,
    private val providerId: String = "chatgpt",
    private val profileSource: (suspend (DeskConnection) -> List<ProfileRef>)? = null,
    private val tokenSource: (suspend (String) -> String)? = null) : DeskBackend {
    override val capabilities = DeskCapabilities(decisions = false, compaction = false, companionChat = false, liveEvents = false)
    private var catalog = emptyList<ModelOption>()
    private val staged = ConcurrentHashMap<DeskBinding, ByteArray>()
    private val jobs = ConcurrentHashMap<DeskBinding, Job>()
    private fun documentKey(profile: String) = "$origin:$profile"
    private fun document(profile: String) = vault.readDocument(documentKey(profile)) ?: JSONObject().put("threads", JSONArray())
    private fun threads(profile: String) = document(profile).getJSONArray("threads")
    private fun thread(binding: DeskBinding): JSONObject {
        require(binding.origin == origin)
        val threads = threads(binding.profile)
        return (0 until threads.length()).map(threads::getJSONObject).singleOrNull { it.getString("id") == binding.session } ?: error("Conversation is unavailable")
    }
    private fun save(binding: DeskBinding, thread: JSONObject) {
        val document = document(binding.profile); val threads = document.getJSONArray("threads")
        val index = (0 until threads.length()).firstOrNull { threads.getJSONObject(it).getString("id") == binding.session } ?: error("Conversation is unavailable")
        threads.put(index, thread); vault.writeDocument(documentKey(binding.profile), document)
    }
    private fun ref(profile: String, thread: JSONObject) = SessionRef(thread.getString("id"), profile, thread.optString("title", "Desk conversation"), thread.optLong("updated"))
    override suspend fun connect(connection: DeskConnection): List<ProfileRef> {
        require(connection.origin == origin)
        profileSource?.let { return it(connection) }
        return vault.accounts().filter { it.refresh.isNotBlank() }.mapIndexed { index, account -> ProfileRef(account.key,
            account.email.ifBlank { "$label account ${index + 1}" } + " · " + account.clientId.takeLast(6), "") }
    }
    override suspend fun selectProfile(profile: String): List<SessionRef> {
        access(profile)
        val items = threads(profile)
        return (0 until items.length()).map { ref(profile, items.getJSONObject(it)) }.sortedByDescending { it.updatedAtEpochMs }
    }
    override suspend fun createThread(profile: String): SessionRef {
        val token = access(profile)
        val models = models(token)
        require(models.isNotEmpty()) { "This $label account returned no available models" }
        val document = document(profile)
        val thread = JSONObject().put("id", UUID.randomUUID().toString()).put("title", "Desk conversation")
            .put("updated", System.currentTimeMillis()).put("model", (models.firstOrNull { providerId == "grok" && it.id == "grok-4.7" } ?: models.first()).id).put("messages", JSONArray())
        document.getJSONArray("threads").put(thread); vault.writeDocument(documentKey(profile), document)
        return ref(profile, thread)
    }
    override suspend fun history(binding: DeskBinding): List<ChatMessage> {
        val messages = thread(binding).getJSONArray("messages")
        return (0 until messages.length()).map { messages.getJSONObject(it).let { row -> ChatMessage(row.getString("id"),
            if (row.getString("role") == "user") MessageRole.USER else MessageRole.ASSISTANT, row.getString("text")) } }
    }
    override fun events(profile: String): Flow<BusFrame> = emptyFlow()
    override fun belongsTo(binding: DeskBinding, eventSession: String?) = binding.session == eventSession
    override suspend fun pending(binding: DeskBinding): ApprovalPrompt? = null
    override suspend fun respond(binding: DeskBinding, prompt: ApprovalPrompt, choice: String) { error("This connection has no device tools") }
    override suspend fun interrupt(binding: DeskBinding) { jobs[binding]?.cancel() }
    override suspend fun stageImage(binding: DeskBinding, jpeg: ByteArray) {
        require(jpeg.size <= 1_000_000) { "Camera frame is too large" }
        staged.put(binding, jpeg.clone())?.fill(0)
    }
    override fun turn(binding: DeskBinding, text: String): Flow<ChatEvent> = flow {
        val job = currentCoroutineContext().job
        check(jobs.putIfAbsent(binding, job) == null) { "A ChatGPT turn is already running" }
        var row: JSONObject? = null; val answer = StringBuilder(); var completed = false
        try {
            val token = access(binding.profile)
            val thread = thread(binding)
            val messages = thread.getJSONArray("messages")
            require(messages.length() < 1000) { "This local conversation is full. Start a new conversation." }
            val image = staged.remove(binding)
            val input = try { request(thread, text, image) } finally { image?.fill(0) }
            val user = JSONObject().put("id", UUID.randomUUID().toString()).put("role", "user").put("text", text)
            // Photos are deliberately ephemeral. Earlier turns retain text; no hidden image library.
            messages.put(user); thread.put("updated", System.currentTimeMillis()); save(binding, thread)
            row = thread
            transport.events(input, token).collect { event ->
                when (event.optString("type")) {
                    "response.created" -> emit(ChatEvent.Accepted())
                    "response.output_text.delta" -> { val delta = event.getString("delta"); answer.append(delta); emit(ChatEvent.AssistantDelta(delta)) }
                    "response.completed" -> {
                        check(!completed) { "Duplicate completion" }; completed = true
                        val response = event.getJSONObject("response")
                        check(response.optString("status", "completed") == "completed") { "$label response was not completed" }
                        if (answer.isEmpty()) {
                            val output = response.optJSONArray("output") ?: JSONArray()
                            (0 until output.length()).forEach { index -> val content = output.getJSONObject(index).optJSONArray("content") ?: JSONArray()
                                (0 until content.length()).forEach { part -> val block = content.getJSONObject(part)
                                    if (block.optString("type") in setOf("output_text", "refusal")) answer.append(block.optString("text", block.optString("refusal"))) }
                            }
                        }
                    }
                    "response.failed", "response.incomplete", "error" -> error("$label did not complete this response. Check your plan usage or reconnect the account.")
                }
            }
            check(completed) { "$label stream ended without completion. The request may have been received; check history before retrying." }
            appendAnswer(binding, thread, answer.toString())
            row = null
            emit(ChatEvent.Final(answer.toString())); emit(ChatEvent.Completed)
        } finally {
            row?.let { appendAnswer(binding, it, answer.toString().takeIf(String::isNotBlank)?.plus("\n\n[Response interrupted; completion was not confirmed.]") ?: "[Response completion was not confirmed.]") }
            jobs.remove(binding, job); staged.remove(binding)?.fill(0)
        }
    }
    private fun appendAnswer(binding: DeskBinding, thread: JSONObject, text: String) {
        thread.getJSONArray("messages").put(JSONObject().put("id", UUID.randomUUID().toString()).put("role", "assistant").put("text", text))
        thread.put("updated", System.currentTimeMillis()); save(binding, thread)
    }
    internal fun request(thread: JSONObject, text: String, image: ByteArray?): JSONObject {
        val input = JSONArray()
        val messages = thread.getJSONArray("messages")
        var size = text.length
        (0 until messages.length()).forEach { index -> val row = messages.getJSONObject(index); size += row.getString("text").length
            input.put(JSONObject().put("role", row.getString("role")).put("content", row.getString("text"))) }
        require(size < 200_000) { "This conversation needs a new thread before continuing. History has not been truncated." }
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", text))
        image?.let { content.put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(it))) }
        input.put(JSONObject().put("role", "user").put("content", content))
        return JSONObject().put("model", thread.getString("model")).put("input", input).put("store", false).put("stream", true)
            .put("instructions", "You are Desk Companion, a thoughtful voice-first companion. Respond naturally and concisely. You can see an image only when attached to the current message. You cannot see the browser, control apps, read mail or perform device actions through this connection. Do not claim you performed actions without tool results.")
    }
    private suspend fun access(profile: String): String = tokenSource?.invoke(profile) ?: auth.access(profile).access
    private suspend fun models(token: String): List<ModelOption> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$origin/v1/models").header("Authorization", "Bearer $token").build()).execute().use {
            check(it.isSuccessful) { "Could not load $label models (${it.code})" }
            val body = JSONObject(it.body!!.string())
            val models = if (providerId == "chatgpt") body.getJSONArray("models") else body.getJSONArray("data")
            (0 until models.length()).map(models::getJSONObject).filter { model ->
                if (providerId == "chatgpt") model.optString("visibility") == "list" else model.optString("id").let { id ->
                    id.startsWith("grok") && listOf("imagine", "image", "video", "voice", "tts").none { word -> id.contains(word) }
                }
            }.map { model ->
                val id = if (providerId == "chatgpt") model.getString("slug") else model.getString("id")
                ModelOption(id, providerId, model.optString("display_name", id))
            }.also { result -> catalog = result }
        }
    }
    override suspend fun settings(binding: DeskBinding): NativeSessionSettings {
        val models = models(access(binding.profile))
        return NativeSessionSettings(thread(binding).getString("model"), providerId, null, null, models)
    }
    override suspend fun setting(binding: DeskBinding, key: String, value: String, confirmed: Boolean): NativeConfigResult {
        require(key == "model") { "Thinking options are controlled by this ChatGPT model" }
        val model = value.substringBefore(" --provider ")
        require(catalog.any { it.id == model }) { "Refresh the model catalog before selecting this model" }
        val thread = thread(binding).put("model", model); save(binding, thread)
        return NativeConfigResult(key, model, "session", false, false, "", "")
    }
    override fun close() { jobs.values.forEach(Job::cancel); jobs.clear(); staged.values.forEach { it.fill(0) }; staged.clear(); catalog = emptyList() }
    companion object { const val ORIGIN = "https://api.openai.com" }
}

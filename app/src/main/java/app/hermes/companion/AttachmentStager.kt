package app.hermes.companion

import android.content.Context
import android.net.Uri
import app.hermes.companion.domain.ChatContent
import app.hermes.companion.model.ChatAttachment
import app.hermes.companion.model.ChatBlockKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A picker result owns the conversation it started in, including close/reopen and promotion. */
internal class AttachmentStager(
    context: Context,
    private val state: MutableStateFlow<CompanionState>,
    private val scope: CoroutineScope,
    private val epoch: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val directory: File = File(context.filesDir, "draft-attachments"),
    private val maximum: (ChatBlockKind) -> Int = { if (it == ChatBlockKind.FILE) 10 * MIB else 25 * MIB },
    private val read: suspend (Uri, Int) -> ByteArray = { uri, limit ->
        context.contentResolver.openInputStream(uri)?.use { readLimited(it, limit) }
            ?: throw IllegalStateException("Attachment unavailable")
    },
) {
    private data class Target(val origin: String, val profile: String, val epoch: Long)
    private val pending = ConcurrentHashMap<String, Target>()
    private val preparation = Mutex() // Bound aggregate image memory as well as each file's input size.

    fun isPreparingCurrent(): Boolean = pending.values.any { owns(it) }

    fun stage(uri: Uri, mime: String, name: String) {
        val snapshot = state.value
        if (snapshot.inRoom) {
            state.update { it.copy(error = "Attachments are not available in agent rooms yet.") }
            return
        }
        val origin = snapshot.origin ?: return
        val profile = snapshot.activeProfileId ?: return
        if (snapshot.openSessionId == null) return
        val target = Target(origin, profile, epoch())
        val id = UUID.randomUUID().toString()
        val kind = ChatContent.kindOf(mime, name, uri.toString())
        val limit = maximum(kind)
        pending[id] = target
        val task = scope.launch(main) {
            var file: File? = null
            var published = false
            try {
                val attachment = withContext(io) {
                    preparation.withLock {
                        if (!owns(target)) return@withLock null
                        val raw = read(uri, limit)
                        var encoded: ByteArray? = null
                        try {
                            ensureActive()
                            if (raw.size > limit) throw AttachmentTooLarge(limit)
                            if (!owns(target)) return@withLock null
                            encoded = if (kind == ChatBlockKind.IMAGE) ImageCompress.jpeg(raw) else raw
                            if (encoded.size > limit) throw AttachmentTooLarge(limit)
                            ensureActive()
                            check(directory.isDirectory || directory.mkdirs()) { "Attachment storage unavailable" }
                            val output = File(directory, id)
                            file = output
                            output.writeBytes(encoded)
                            ChatAttachment(id, output.absolutePath, name.ifBlank { "file" },
                                if (kind == ChatBlockKind.IMAGE) "image/jpeg" else mime, kind, sizeBytes = encoded.size.toLong())
                        } finally { if (encoded !== raw) encoded?.fill(0); raw.fill(0) }
                    }
                }
                if (attachment != null && owns(target)) {
                    state.update { st ->
                        if (!owns(target, st)) st else st.copy(pendingAttachments = st.pendingAttachments + attachment,
                            attachOpen = false, error = null)
                    }
                    published = state.value.pendingAttachments.any { it.id == attachment.id }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (owns(target)) state.update { st ->
                    if (!owns(target, st)) st else st.copy(attachOpen = false, error = if (error is AttachmentTooLarge) {
                        val size = if (error.limit >= MIB) "${error.limit / MIB}MB" else "${error.limit} bytes"
                        "${kind.name.lowercase()} too large · max $size"
                    } else "Could not prepare attachment. Pick the file again.")
                }
            } finally {
                pending.remove(id)
                if (!published) file?.delete()
            }
        }
        task.invokeOnCompletion { pending.remove(id) }
    }

    private fun owns(target: Target, current: CompanionState = state.value): Boolean = current.let {
        it.origin == target.origin && it.activeProfileId == target.profile && !it.inRoom &&
            it.openSessionId != null && epoch() == target.epoch
    }

    companion object {
        private const val MIB = 1024 * 1024
        internal suspend fun readLimited(input: InputStream, limit: Int): ByteArray {
            require(limit > 0 && limit < Int.MAX_VALUE)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(minOf(16_384, limit + 1))
            var total = 0
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - total))
                    if (count == -1) return output.toByteArray()
                    check(count > 0) { "Attachment stream made no progress" }
                    total += count
                    if (total > limit) throw AttachmentTooLarge(limit)
                    output.write(buffer, 0, count)
                }
            } finally { buffer.fill(0); output.close() }
        }
    }
}

private class AttachmentTooLarge(val limit: Int) : IllegalArgumentException("Attachment exceeds its size limit")

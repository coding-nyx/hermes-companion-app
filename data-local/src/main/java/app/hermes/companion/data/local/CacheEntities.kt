package app.hermes.companion.data.local

import androidx.room.Entity
import androidx.room.Index
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.OutboxItem
import app.hermes.companion.model.SessionRef

@Entity(
    tableName = "sessions",
    primaryKeys = ["origin", "profileId", "id"],
)
data class SessionEntity(
    val origin: String,
    val profileId: String,
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val unread: Boolean,
)

@Entity(
    tableName = "messages",
    primaryKeys = ["origin", "profileId", "sessionId", "id"],
    indices = [Index(value = ["origin", "profileId", "sessionId"])],
)
data class MessageEntity(
    val origin: String,
    val profileId: String,
    val sessionId: String,
    val id: String,
    val ordinal: Int,
    val role: String,
    val text: String,
    val toolName: String?,
    val toolDetail: String?,
)

internal fun SessionEntity.toRef(): SessionRef = SessionRef(
    id = id,
    profileId = profileId,
    title = title,
    updatedAtEpochMs = updatedAtEpochMs,
    unread = unread,
)

internal fun SessionRef.toEntity(origin: String): SessionEntity = SessionEntity(
    origin = origin,
    profileId = profileId,
    id = id,
    title = title,
    updatedAtEpochMs = updatedAtEpochMs,
    unread = unread,
)

internal fun MessageEntity.toModel(): ChatMessage = ChatMessage(
    id = id,
    role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.ASSISTANT),
    text = text,
    toolName = toolName,
    toolDetail = toolDetail,
    streaming = false,
)

internal fun ChatMessage.toEntity(
    origin: String,
    profileId: String,
    sessionId: String,
    ordinal: Int,
): MessageEntity = MessageEntity(
    origin = origin,
    profileId = profileId,
    sessionId = sessionId,
    id = id,
    ordinal = ordinal,
    role = role.name,
    text = text,
    toolName = toolName,
    toolDetail = toolDetail,
)

@Entity(
    tableName = "outbox",
    primaryKeys = ["id"],
    indices = [Index(value = ["origin", "profileId", "createdAtEpochMs"])],
)
data class OutboxEntity(
    val id: String,
    val origin: String,
    val profileId: String,
    val sessionId: String,
    val text: String,
    val createdAtEpochMs: Long,
    val attempts: Int,
    val lastError: String,
)

internal fun persistableMessages(messages: List<ChatMessage>): List<ChatMessage> =
    messages.filter { !it.streaming && !it.queued && it.id.isNotBlank() }

internal fun OutboxEntity.toItem(): OutboxItem = OutboxItem(
    id = id,
    origin = origin,
    profileId = profileId,
    sessionId = sessionId,
    text = text,
    createdAtEpochMs = createdAtEpochMs,
    attempts = attempts,
    lastError = lastError,
)

internal fun OutboxItem.toEntity(): OutboxEntity = OutboxEntity(
    id = id,
    origin = origin,
    profileId = profileId,
    sessionId = sessionId,
    text = text,
    createdAtEpochMs = createdAtEpochMs,
    attempts = attempts,
    lastError = lastError,
)

internal fun OutboxItem.toQueuedMessage(): ChatMessage = ChatMessage(
    id = id,
    role = MessageRole.USER,
    text = text,
    queued = true,
)

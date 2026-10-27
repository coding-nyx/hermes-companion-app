package app.hermes.companion.chat

import androidx.compose.ui.unit.dp
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole

/** Space belongs to the boundary, never both adjacent rows. Tool/reasoning steps share a turn. */
internal fun transcriptGap(previous: ChatMessage?, current: ChatMessage) = transcriptGap(previous, current.role, current.speaker)

internal fun transcriptGap(previous: ChatMessage?, role: MessageRole, speaker: String?) = when {
    previous == null -> 0.dp
    previous.role == MessageRole.USER || role == MessageRole.USER -> 12.dp
    previous.speaker != speaker -> 12.dp
    else -> 4.dp
}

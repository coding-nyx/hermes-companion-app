package app.hermes.companion.domain

import app.hermes.companion.model.AgentEvent
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole

/** The phone's view of a structured coding-agent session: chat rows plus turn/approval state. */
data class AgentTranscriptState(
    val messages: List<ChatMessage> = emptyList(),
    val approval: ApprovalPrompt? = null,
    val turnActive: Boolean = false,
    val exited: Boolean = false,
    val exitCode: Int? = null,
    val costUsd: Double = 0.0,
    val agentSessionId: String = "",
    val model: String = "",
    /** Host transcript rows consumed so far; `GET …/transcript?after=` resumes from here. */
    val seq: Int = 0,
    val lastError: String = "",
    private val nextId: Int = 1,
) {
    val live: Boolean get() = !exited

    internal fun id(prefix: String) = "$prefix-$nextId" to copy(nextId = nextId + 1)
}

/**
 * Pure reducer from host `agent.*` events to chat rows (A23.3). Deltas extend the streaming
 * assistant row; a tool row closes it so the next delta opens a fresh paragraph; approvals are
 * held in [AgentTranscriptState.approval] until resolved.
 */
object AgentTranscript {
    fun replay(events: List<AgentEvent>): AgentTranscriptState = events.fold(AgentTranscriptState()) { st, ev -> reduce(st, ev) }

    fun reduce(state: AgentTranscriptState, event: AgentEvent): AgentTranscriptState {
        val st = state.copy(seq = state.seq + 1)
        return when (event) {
            is AgentEvent.Ready -> st.copy(
                agentSessionId = event.agentSessionId.ifBlank { st.agentSessionId },
                model = event.model.ifBlank { st.model },
            )
            is AgentEvent.User -> {
                val (id, next) = st.id("u")
                next.copy(messages = closeStreaming(next.messages) + ChatMessage(id = id, role = MessageRole.USER, text = event.text))
            }
            AgentEvent.TurnStart -> st.copy(turnActive = true, lastError = "")
            is AgentEvent.Delta -> {
                if (event.text.isEmpty()) return st
                val last = st.messages.lastOrNull()
                if (last != null && last.role == MessageRole.ASSISTANT && last.streaming) {
                    st.copy(messages = st.messages.dropLast(1) + last.copy(text = last.text + event.text))
                } else {
                    val (id, next) = st.id("a")
                    next.copy(messages = next.messages + ChatMessage(id = id, role = MessageRole.ASSISTANT, text = event.text, streaming = true))
                }
            }
            is AgentEvent.ToolStart -> {
                val row = ChatMessage(
                    id = "t-${event.toolId}", role = MessageRole.TOOL, text = "",
                    toolName = event.name, toolDetail = event.detail, toolRunning = true,
                )
                st.copy(messages = closeStreaming(st.messages) + row)
            }
            is AgentEvent.ToolComplete -> {
                val idx = st.messages.indexOfLast { it.role == MessageRole.TOOL && (it.id == "t-${event.toolId}" || (event.toolId.isBlank() && it.toolRunning)) }
                if (idx < 0) {
                    val row = ChatMessage(
                        id = "t-${event.toolId.ifBlank { st.seq.toString() }}", role = MessageRole.TOOL, text = event.detail,
                        toolName = event.name, toolDetail = if (event.error) "failed" else "", toolRunning = false,
                    )
                    st.copy(messages = st.messages + row)
                } else {
                    val row = st.messages[idx]
                    val detail = if (event.error) "failed · ${row.toolDetail.orEmpty()}".trimEnd(' ', '·') else row.toolDetail
                    val updated = row.copy(toolRunning = false, text = event.detail, toolDetail = detail)
                    st.copy(messages = st.messages.toMutableList().also { it[idx] = updated })
                }
            }
            is AgentEvent.Approval -> st.copy(approval = event.prompt)
            is AgentEvent.ApprovalResolved ->
                if (st.approval == null || st.approval.requestId == event.requestId) st.copy(approval = null) else st
            is AgentEvent.TurnEnd -> {
                var msgs = closeStreaming(st.messages)
                val hasText = msgs.lastOrNull()?.let { it.role == MessageRole.ASSISTANT && it.text.isNotBlank() } == true
                var next = st
                if (!hasText && event.text.isNotBlank()) {
                    val (id, n) = next.id("a")
                    next = n
                    msgs = msgs + ChatMessage(id = id, role = MessageRole.ASSISTANT, text = event.text)
                }
                next.copy(
                    messages = msgs, turnActive = false, approval = null,
                    costUsd = st.costUsd + (event.costUsd ?: 0.0),
                    lastError = if (event.isError) event.text.ifBlank { "turn failed" } else st.lastError,
                )
            }
            is AgentEvent.Error -> st.copy(lastError = event.message)
            is AgentEvent.Exit -> st.copy(
                messages = closeStreaming(st.messages), turnActive = false, approval = null, exited = true, exitCode = event.exitCode,
                lastError = if (event.exitCode != null && event.exitCode != 0) event.stderr.ifBlank { "exited ${event.exitCode}" } else st.lastError,
            )
        }
    }

    private fun closeStreaming(messages: List<ChatMessage>): List<ChatMessage> {
        val last = messages.lastOrNull() ?: return messages
        return if (last.role == MessageRole.ASSISTANT && last.streaming) messages.dropLast(1) + last.copy(streaming = false) else messages
    }
}

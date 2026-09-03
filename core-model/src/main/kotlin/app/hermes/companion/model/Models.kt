package app.hermes.companion.model

import kotlinx.serialization.Serializable

@Serializable
data class GatewayRef(
    val id: String,
    val origin: String,
    val label: String,
)

@Serializable
data class DashboardStatus(
    val authRequired: Boolean,
    val authProviders: List<String> = emptyList(),
    val version: String = "",
    val gatewayRunning: Boolean = false,
)

@Serializable
data class ProfileRef(
    val id: String,
    val displayName: String,
    val glyph: String,
    val model: String = "",
    val gateway: String = "",
    val sessionCount: Int = 0,
)

@Serializable
data class SessionRef(
    val id: String,
    val profileId: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val unread: Boolean = false,
)

enum class MessageRole { USER, ASSISTANT, TOOL }

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val toolName: String? = null,
    val toolDetail: String? = null,
    val streaming: Boolean = false,
)

@Serializable
data class ApprovalPrompt(
    val requestId: String,
    val kind: String = "approval",
    val command: String,
    val choices: List<String> = listOf("once", "deny"),
)

@Serializable
data class SessionChange(
    val op: String,
    val session: SessionRef,
)

sealed class ChatEvent {
    data class AssistantDelta(val text: String) : ChatEvent()
    data class ToolStarted(val name: String, val detail: String) : ChatEvent()
    data class ToolCompleted(val name: String, val detail: String, val durationMs: Long = 0) : ChatEvent()
    data class Approval(val prompt: ApprovalPrompt) : ChatEvent()
    data class PromptExpired(val requestId: String) : ChatEvent()
    data object Completed : ChatEvent()
}

sealed class BusFrame {
    data class Chat(val event: ChatEvent, val sessionId: String?) : BusFrame()
    data class SessionPatch(val change: SessionChange) : BusFrame()
    data object SessionRefetch : BusFrame()
}

@Serializable
data class GatewayHello(
    val changeEvents: Boolean = false,
    val heartbeat: Boolean = false,
    val instanceId: String = "",
)

enum class DeviceArm { DISARMED, ARMED, EXECUTING }

enum class HudState { OFF, ON, DEGRADED }

@Serializable
data class GatewayHud(
    val gateway: HudState = HudState.OFF,
    val telegram: HudState = HudState.OFF,
    val device: HudState = HudState.OFF,
    val api: HudState = HudState.OFF,
)

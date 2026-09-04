package app.hermes.companion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GatewayRef(
    val id: String,
    val origin: String,
    val label: String,
)

@Serializable
data class PlatformStatus(
    val name: String,
    val state: String = "",
    val error: String = "",
)

data class DashboardStatus(
    val authRequired: Boolean,
    val authProviders: List<String> = emptyList(),
    val version: String = "",
    val gatewayRunning: Boolean = false,
    val gatewayState: String = "",
    val platforms: List<PlatformStatus> = emptyList(),
    val memoryPressure: String = "",
    val diskPressure: String = "",
    val exitReason: String = "",
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
    val queued: Boolean = false,
)

@Serializable
data class OutboxItem(
    val id: String,
    val origin: String,
    val profileId: String,
    val sessionId: String,
    val text: String,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
    val lastError: String = "",
)

@Serializable
data class HistoryPage(
    val messages: List<ChatMessage>,
    val hasMore: Boolean,
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
    data class Rewound(val userRowIds: List<Long?>) : ChatEvent()
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

enum class PairingPhase { IDLE, WAITING, PAIRED }

@Serializable
data class DeviceCred(
    val deviceId: String,
    val profileId: String,
    val credential: String,
    val origin: String = "",
)

data class PairingStatus(
    val status: String,
    val deviceId: String = "",
    val profileId: String = "",
    val credential: String = "",
) {
    val approved: Boolean
        get() = status == "approved" && deviceId.isNotBlank() && credential.isNotBlank()
}

data class DeviceTicket(
    val ticket: String,
    val capabilities: List<String> = emptyList(),
    val ttlSec: Int = 30,
)

data class DeviceCommand(
    val commandId: String,
    val action: String,
    val argumentsJson: String = "{}",
    val toolCallId: String = "",
    val profile: String = "",
    val deviceId: String = "",
)

data class DeviceResult(
    val commandId: String,
    val ok: Boolean,
    val resultJson: String = "{}",
    val errorCode: String = "",
    val errorMessage: String = "",
)

data class SnapshotNode(
    val ref: String,
    val role: String,
    val text: String = "",
    val clickable: Boolean = false,
    val bounds: List<Int> = emptyList(),
)

@Serializable
data class ScreenSafeArea(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

enum class HudState { OFF, ON, DEGRADED }

@Serializable
data class GatewayHud(
    val gateway: HudState = HudState.OFF,
    val telegram: HudState = HudState.OFF,
    val discord: HudState = HudState.OFF,
    val api: HudState = HudState.OFF,
)

@Serializable
data class HostCpuMetrics(
    val percent: Double = 0.0,
    val cores: Int = 1,
    @SerialName("load_avg") val loadAvg: List<Double> = emptyList(),
)

@Serializable
data class HostMemoryMetrics(
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("used_bytes") val usedBytes: Long = 0L,
    @SerialName("free_bytes") val freeBytes: Long = 0L,
    val percent: Double = 0.0,
)

@Serializable
data class HostDiskMetrics(
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("used_bytes") val usedBytes: Long = 0L,
    @SerialName("free_bytes") val freeBytes: Long = 0L,
    val percent: Double = 0.0,
)

@Serializable
data class HostSystemMetrics(
    val platform: String = "",
    val release: String = "",
    val architecture: String = "",
    @SerialName("python_version") val pythonVersion: String = "",
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0L,
)

@Serializable
data class HostHermesProcess(
    val pid: Int = 0,
    val status: String = "online",
)

@Serializable
data class HostMetrics(
    val cpu: HostCpuMetrics = HostCpuMetrics(),
    val memory: HostMemoryMetrics = HostMemoryMetrics(),
    val disk: HostDiskMetrics = HostDiskMetrics(),
    val system: HostSystemMetrics = HostSystemMetrics(),
    val hermes: HostHermesProcess = HostHermesProcess(),
)

@Serializable
data class GitStatus(
    val branch: String = "main",
    val tracking: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    @SerialName("staged_files") val stagedFiles: List<String> = emptyList(),
    @SerialName("modified_files") val modifiedFiles: List<String> = emptyList(),
    @SerialName("untracked_files") val untrackedFiles: List<String> = emptyList(),
)

@Serializable
data class GitDiffSummary(
    @SerialName("raw_diff") val rawDiff: String = "",
    @SerialName("files_changed") val filesChanged: Int = 0,
    val insertions: Int = 0,
    val deletions: Int = 0,
)

@Serializable
data class GitBranches(
    val current: String = "",
    val branches: List<String> = emptyList(),
)

@Serializable
data class RemoteFsItem(
    val path: String = "",
    val name: String = "",
    @SerialName("is_dir") val isDir: Boolean = false,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    @SerialName("modified_ms") val modifiedMs: Long = 0L,
)

@Serializable
data class TerminalExecResult(
    val ok: Boolean = false,
    @SerialName("exit_code") val exitCode: Int = 0,
    val stdout: String = "",
    val stderr: String = "",
)

@Serializable
data class CronJob(
    val id: String,
    val name: String = "",
    val prompt: String = "",
    @SerialName("schedule_display") val scheduleDisplay: String = "",
    val enabled: Boolean = true,
    val state: String = "scheduled",
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val profile: String = "",
    val deliver: String = "",
)

@Serializable
data class ModelOption(
    val id: String,
    val provider: String,
    val name: String,
    val reasoning: Boolean = false,
    val fast: Boolean = false,
)

@Serializable
data class ModelCatalog(
    val currentModel: String = "",
    val currentProvider: String = "",
    val models: List<ModelOption> = emptyList(),
)

@Serializable
data class HermesUpdateStatus(
    @SerialName("current_version") val currentVersion: String = "",
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("can_apply") val canApply: Boolean = false,
    val behind: Int = 0,
    val summary: String = "",
    @SerialName("update_command") val updateCommand: String = "hermes update",
)

@Serializable
data class SavedGateway(
    val id: String,
    val name: String,
    val origin: String,
    val token: String? = null,
    val isActive: Boolean = false,
)


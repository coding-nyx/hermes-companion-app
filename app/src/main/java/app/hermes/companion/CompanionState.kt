package app.hermes.companion

import app.hermes.companion.console.TerminalLogEntry
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.WakePing
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.CronJob
import app.hermes.companion.model.DashboardStatus
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.model.GatewayHello
import app.hermes.companion.model.GatewayHud
import app.hermes.companion.model.GitDiffSummary
import app.hermes.companion.model.GitStatus
import app.hermes.companion.model.HermesUpdateStatus
import app.hermes.companion.model.HostMetrics
import app.hermes.companion.model.ModelCatalog
import app.hermes.companion.model.PairingPhase
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.SessionRef

enum class MainTab { THREADS, CONSOLE, REVIEW, REMINDERS, PROFILES, GATEWAY, DEVICE }

/** External `hermes-companion://open` request awaiting user confirmation (A7.10). */
data class DeepLinkRequest(val profileId: String, val sessionId: String)

data class CompanionState(
    val originInput: String = "",
    val origin: String? = null,
    val username: String = "",
    val password: String = "",
    val authRequired: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val profiles: List<ProfileRef> = emptyList(),
    val activeProfileId: String? = null,
    val sessions: List<SessionRef> = emptyList(),
    val tab: MainTab = MainTab.THREADS,
    val openSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val streaming: Boolean = false,
    val approval: ApprovalPrompt? = null,
    val historyHasMore: Boolean = false,
    val historyLoading: Boolean = false,
    val rewindTargetId: String? = null,
    val gatewayHello: GatewayHello? = null,
    val status: DashboardStatus? = null,
    val hud: GatewayHud = GatewayHud(),
    val pairingPhase: PairingPhase = PairingPhase.IDLE,
    val pairingCode: String = "",
    val deviceId: String? = null,
    val deviceProfileId: String? = null,
    val deviceLane: Boolean = false,
    val a11yBound: Boolean = false,
    val overlayGranted: Boolean = false,
    val notifyGranted: Boolean = false,
    val foregroundApp: String = "",
    val lastAudit: String = "",
    val arm: DeviceArm = DeviceArm.DISARMED,
    val ntfyTopic: String = "",
    val stayConnected: Boolean = false,
    val lastWake: WakePing? = null,
    val hostMetrics: HostMetrics? = null,
    val modelCatalog: ModelCatalog? = null,
    val cronJobs: List<CronJob> = emptyList(),
    val cronLoading: Boolean = false,
    val gitStatus: GitStatus? = null,
    val gitDiff: GitDiffSummary? = null,
    val gitSelectedFile: String? = null,
    val gitLoading: Boolean = false,
    val terminalLogs: List<TerminalLogEntry> = emptyList(),
    val terminalExecuting: Boolean = false,
    val savedGateways: List<SavedGateway> = emptyList(),
    val updateStatus: HermesUpdateStatus? = null,
    val awakeOnVoice: Boolean = false,
    val lockedAccess: Boolean = false,
    val isListeningVoice: Boolean = false,
    val protectedCustom: List<String> = emptyList(),
    val protectedError: String? = null,
    val pendingDeepLink: DeepLinkRequest? = null,
) {
    val visibleSessions: List<SessionRef>
        get() = ProfileScope.visibleSessions(sessions, activeProfileId)
    val activeProfile: ProfileRef?
        get() = profiles.find { it.id == activeProfileId }
    val openSession: SessionRef?
        get() = sessions.find { it.id == openSessionId }
}

internal fun CompanionState.mirror(node: DeviceNodeState): CompanionState = copy(
    pairingPhase = node.pairingPhase,
    pairingCode = node.pairingCode,
    deviceId = node.deviceId,
    deviceProfileId = node.deviceProfileId,
    deviceLane = node.laneOpen,
    a11yBound = node.a11yBound,
    foregroundApp = node.foregroundApp,
    lastAudit = node.lastAudit,
    arm = node.arm,
    protectedCustom = node.protectedCustom,
    protectedError = node.protectedError,
)

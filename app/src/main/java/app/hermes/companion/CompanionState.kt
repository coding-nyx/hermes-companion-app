package app.hermes.companion

import app.hermes.companion.console.TerminalLogEntry
import app.hermes.companion.domain.HostHealth
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.Rooms
import app.hermes.companion.domain.WakePing
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.ChatAttachment
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
import app.hermes.companion.model.RoomRef
import app.hermes.companion.model.GatewayChoice
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.SessionRef

enum class MainTab { THREADS, CONSOLE, REVIEW, REMINDERS, PROFILES, GATEWAY, DEVICE }

/** Real-time conversational voice stream lifecycle states. */
enum class VoiceStreamState { IDLE, LISTENING, THINKING, SPEAKING }

/** External `hermes-companion://open` request awaiting user confirmation (A7.10). */
data class DeepLinkRequest(val profileId: String, val sessionId: String, val origin: String = "")

data class CompanionState(
    val originInput: String = "",
    val connectChoices: List<GatewayChoice> = emptyList(),
    val origin: String? = null,
    /** Gateway-book name of [origin] (bare host if unnamed). Shown in the header and every notification. */
    val hostName: String = "",
    val username: String = "",
    val password: String = "",
    val authRequired: Boolean = false,
    val loading: Boolean = false,
    /** Thread roster in flight (profile switch / connect). Distinct from connect [loading]. */
    val sessionsLoading: Boolean = false,
    /** First history page in flight for the open thread. Cached tail still shown. */
    val transcriptLoading: Boolean = false,
    /** `rpc` or `rest` after the first history page lands (A18.8). */
    val historySource: String = "",
    val hostLoading: Boolean = false,
    val modelLoading: Boolean = false,
    val updateLoading: Boolean = false,
    val error: String? = null,
    val profiles: List<ProfileRef> = emptyList(),
    val activeProfileId: String? = null,
    val sessions: List<SessionRef> = emptyList(),
    val tab: MainTab = MainTab.THREADS,
    val openSessionId: String? = null,
    val openSessionRef: SessionRef? = null,
    // Agent rooms (P21). A room and a thread are never open at the same time.
    val rooms: List<RoomRef> = emptyList(),
    val roomsLoading: Boolean = false,
    val roomsError: String? = null,
    val openRoomId: String? = null,
    val openRoom: RoomRef? = null,
    /** Profile taking a turn right now in the open room. */
    val roomSpeaking: String? = null,
    val roomCreateOpen: Boolean = false,
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
    val deviceLabel: String = "",
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
    /** Per-origin `/api/status` classification (A8.4), keyed by [app.hermes.companion.domain.GatewayBook.key]. */
    val hostHealth: Map<String, HostHealth> = emptyMap(),
    val updateStatus: HermesUpdateStatus? = null,
    val awakeOnVoice: Boolean = false,
    val lockedAccess: Boolean = false,
    val biometricLock: Boolean = false,
    val appLocked: Boolean = false,
    val isListeningVoice: Boolean = false,
    val voiceStreamState: VoiceStreamState = VoiceStreamState.IDLE,
    val protectedCustom: List<String> = emptyList(),
    val protectedError: String? = null,
    val nlsBound: Boolean = false,
    val notifStreamEnabled: Boolean = false,
    val notifStreamOrigin: String? = null,
    val notifStreamProfile: String? = null,
    val pairedHosts: List<String> = emptyList(),
    val pendingDeepLink: DeepLinkRequest? = null,
    val pendingDelete: SessionRef? = null,
    /** Optional per-turn model on session.create / prompt.submit (A9.3). */
    val modelOverride: String = "",
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val attachOpen: Boolean = false,
) {
    val isVoiceStreamActive: Boolean
        get() = voiceStreamState != VoiceStreamState.IDLE
    val visibleSessions: List<SessionRef>
        get() = ProfileScope.visibleSessions(sessions, activeProfileId).filterNot { Rooms.isBackingSession(it.title) }
    val inRoom: Boolean
        get() = openRoomId != null
    val activeProfile: ProfileRef?
        get() = profiles.find { it.id == activeProfileId }
    /**
     * The open thread. Falls back to [openSessionRef] because a freshly created session can be
     * missing from the host's next `session.list` (empty threads are not persisted yet), and
     * without the fallback SEND became a silent no-op.
     */
    val openSession: SessionRef?
        get() = sessions.find { it.id == openSessionId } ?: openSessionRef?.takeIf { it.id == openSessionId }
}

internal fun CompanionState.mirror(node: DeviceNodeState): CompanionState = copy(
    pairingPhase = node.pairingPhase,
    pairingCode = node.pairingCode,
    deviceId = node.deviceId,
    deviceLabel = node.deviceLabel,
    deviceProfileId = node.deviceProfileId,
    deviceLane = node.laneOpen,
    a11yBound = node.a11yBound,
    foregroundApp = node.foregroundApp,
    lastAudit = node.lastAudit,
    arm = node.arm,
    protectedCustom = node.protectedCustom,
    protectedError = node.protectedError,
    nlsBound = node.nlsBound,
    notifStreamEnabled = node.notifStreamEnabled,
    notifStreamOrigin = node.notifStreamOrigin,
    notifStreamProfile = node.notifStreamProfile,
    pairedHosts = node.pairedHosts,
)

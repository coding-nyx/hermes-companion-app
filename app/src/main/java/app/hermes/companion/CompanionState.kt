package app.hermes.companion

import app.hermes.companion.console.TerminalLogEntry
import app.hermes.companion.domain.DraftThread
import app.hermes.companion.domain.HostHealth
import app.hermes.companion.domain.ProfileScope
import app.hermes.companion.domain.Rooms
import app.hermes.companion.domain.SessionLists
import app.hermes.companion.domain.ThreadSort
import app.hermes.companion.domain.WakePing
import app.hermes.companion.domain.AgentTranscriptState
import app.hermes.companion.model.AgentDirListing
import app.hermes.companion.model.AgentPane
import app.hermes.companion.model.AgentSession
import app.hermes.companion.model.AgentTool
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
import app.hermes.companion.model.PeerLink
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.RoomRef
import app.hermes.companion.model.GatewayChoice
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.SessionRef

enum class MainTab { THREADS, CONSOLE, REVIEW, REMINDERS, PROFILES, GATEWAY, DEVICE }

/** Real-time conversational voice stream lifecycle states. */
enum class VoiceStreamState { IDLE, LISTENING, THINKING, SPEAKING }

/** External `hermes-companion://open` request awaiting user confirmation (A7.10). */
data class DeepLinkRequest(val profileId: String, val sessionId: String, val origin: String = "", val roomId: String = "")

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
    /** Rail order (A18.9). Sticky per phone; applied in [visibleSessions]. */
    val threadSort: ThreadSort = ThreadSort.DEFAULT,
    /** Include host-archived threads (A18.12). Sticky; toggling refetches with `archived=include`. */
    val showArchived: Boolean = false,
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
    /** Last seen transcript seq per room id (unread dot on the ROOMS rail). */
    val roomSeen: Map<String, Int> = emptyMap(),
    /** Peer relays the active host can ask for turns (cross-host rooms). */
    val peers: List<PeerLink> = emptyList(),
    val peersLoading: Boolean = false,
    /** Profiles on each linked peer, by peer name (create sheet). */
    val peerProfiles: Map<String, List<ProfileRef>> = emptyMap(),
    /** Origin of a saved gateway a LINK HOSTS handshake is running against. */
    val peerLinking: String? = null,
    // Coding-agent sessions (P23).
    val agentTools: List<AgentTool> = emptyList(),
    val agentTmux: Boolean = true,
    val agentDefaultCwd: String = "",
    val agentToolsLoading: Boolean = false,
    val agentSessions: List<AgentSession> = emptyList(),
    val agentSessionsLoading: Boolean = false,
    val agentStarting: Boolean = false,
    val agentNewOpen: Boolean = false,
    val openAgentId: String? = null,
    val openAgent: AgentSession? = null,
    val agentPane: AgentPane = AgentPane(),
    val agentPaneLoading: Boolean = false,
    val agentCols: Int = 80,
    val agentInput: String = "",
    val agentError: String? = null,
    /** Directory picker in the NEW panel. */
    val agentDirs: AgentDirListing? = null,
    val agentDirsLoading: Boolean = false,
    val agentDirsError: String? = null,
    /** Structured (stream-json) session transcript while one is open. */
    val agentTranscript: AgentTranscriptState = AgentTranscriptState(),
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
    /** Profile-scoped rail, room backers and drafts removed, in the operator's chosen [threadSort]. */
    val visibleSessions: List<SessionRef>
        get() = SessionLists.sort(
            SessionLists.archivedVisible(
                ProfileScope.visibleSessions(sessions, activeProfileId).filterNot {
                    Rooms.isBackingSession(it.title) || DraftThread.isDraft(it)
                },
                showArchived,
            ),
            threadSort,
        )
    val inRoom: Boolean
        get() = openRoomId != null
    /** Rooms whose host seq is past what this phone has seen. */
    val unreadRooms: Set<String>
        get() = rooms.filter { it.seq > (roomSeen[it.id] ?: 0) }.map { it.id }.toSet()
    val activeProfile: ProfileRef?
        get() = profiles.find { it.id == activeProfileId }
    /**
     * The open thread. Falls back to [openSessionRef] for the local draft placeholder (never
     * on the rail) and for a freshly created session that the host has not persisted yet.
     * Without the fallback SEND became a silent no-op.
     */
    val openSession: SessionRef?
        get() = sessions.find { it.id == openSessionId } ?: openSessionRef?.takeIf { it.id == openSessionId }

    /** Drop previous host's metrics/catalog so a switch cannot show stale telemetry. */
    fun dropHostScoped(): CompanionState = copy(
        hostMetrics = null,
        modelCatalog = null,
        cronJobs = emptyList(),
        gitStatus = null,
        gitDiff = null,
        gitSelectedFile = null,
        terminalLogs = emptyList(),
        agentSessions = emptyList(),
        agentTools = emptyList(),
        openAgentId = null,
        openAgent = null,
        agentPane = AgentPane(),
        agentTranscript = AgentTranscriptState(),
        updateStatus = null,
        hostLoading = true,
        modelLoading = true,
        cronLoading = true,
        gitLoading = false,
    )
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

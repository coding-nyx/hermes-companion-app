package app.hermes.hands

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.hermes.companion.design.ActionKind
import app.hermes.companion.device.DevicePrivacy
import app.hermes.hands.engine.ShizukuStatus
import app.hermes.hands.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SECURE_SETTINGS_GRANT_COMMAND =
    "adb shell pm grant app.hermes.hands android.permission.WRITE_SECURE_SETTINGS"

@Composable
private fun AccessibilitySwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Hands Accessibility", modifier = Modifier.weight(1f), style = HandsType.Body)
        Switch(checked = enabled, onCheckedChange = onChange,
            modifier = Modifier.testTag("hands.accessibility.toggle")
                .semantics { contentDescription = "Hands Accessibility" })
    }
}

fun buildAgentPairingInstruction(code: String, endpoints: List<String>, fingerprint: String): String {
    val endpoint = endpoints.firstOrNull() ?: "https://<PHONE_IP>:8766"
    return """
Connect to my phone via Hands:
- Endpoint: $endpoint
- TLS Fingerprint (SHA-256): $fingerprint
- Pairing Code: $code

Please initiate pairing with your client_name using the pairing endpoint (POST /v1/pair) or your Hands pairing tool. I will approve your authentication on my phone.
""".trimIndent()
}

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as HandsApplication).runtime
    @Volatile private var manualApprovalId: String? = null
    @Volatile private var manualClientId: String? = null
    private val preparingToken = java.util.concurrent.atomic.AtomicBoolean(false)
    private lateinit var permissions: HandsPermissionController
    private val requestedTab = MutableStateFlow(0)
    private val notification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startHost() else runtime.message("Allow Hands notifications so the Stop control stays available.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions = HandsPermissionController(this, runtime)
        requestedTab.value = if (intent.getStringExtra("hands_page") == "access") 2 else 0
        enableEdgeToEdge()
        setContent {
            val state by runtime.state.collectAsStateWithLifecycle()
            val engine by runtime.shizuku.status.collectAsStateWithLifecycle()
            val remote by runtime.remoteAccess.collectAsStateWithLifecycle()
            val pairing by runtime.remotePairing.state.collectAsStateWithLifecycle()
            val authorization by runtime.localAuthorization.collectAsStateWithLifecycle()
            val grants by permissions.state.collectAsStateWithLifecycle()
            val notificationSharing by runtime.notifications.state.collectAsStateWithLifecycle()
            val privacyActive by DevicePrivacy.activeFlow.collectAsStateWithLifecycle()
            val destination by requestedTab.collectAsStateWithLifecycle()
            var token by remember { mutableStateOf<Pair<String, String>?>(null) }
            LaunchedEffect(authorization) {
                if (token != null && token?.first != authorization) { finishManualToken(false); token = null }
            }
            val clientVersion by runtime.tokens.revision.collectAsStateWithLifecycle()
            val clients = remember(clientVersion) { runtime.tokens.clients() }

            HandsTheme {
                HandsScreen(
                    state = state,
                    engine = engine,
                    clients = clients,
                    onStart = {
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            notification.launch(Manifest.permission.POST_NOTIFICATIONS) else startHost()
                    },
                    onStopService = { startService(Intent(this, HandsHostService::class.java).setAction(HandsHostService.STOP)) },
                    onAllow = {
                        act {
                            if (!Settings.canDrawOverlays(this)) {
                                overlaySettings()
                                runtime.message("Allow Display over other apps, then return to Hands.")
                            } else runtime.approve()
                        }
                    },
                    onDisarm = runtime::disarm,
                    onBackend = { runtime.configure(backend = it) },
                    onTarget = { runtime.configure(target = it) },
                    onShizuku = {
                        act {
                            if (engine.permissionGranted) runtime.shizuku.requestPermission()
                            else permissions.external { runtime.shizuku.requestPermission() }
                        }
                    },
                    onOpenShizuku = {
                        act {
                            startActivity(
                                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                            )
                        }
                    },
                    onAccessibility = { permissions.request("accessibility") },
                    onAccessibilityToggle = permissions::toggleAccessibility,
                    onOverlay = { permissions.request("overlay") },
                    onStartScreen = {
                        lifecycleScope.launch {
                            actSuspend {
                                check(state.serving) { "Start the service before opening the agent screen." }
                                withContext(Dispatchers.IO) { runtime.openPreview() }
                            }
                        }
                    },
                    onCreateToken = {
                        if (preparingToken.compareAndSet(false, true)) lifecycleScope.launch {
                            actSuspend {
                                try {
                                    val issued = withContext(Dispatchers.IO) {
                                        val id = runtime.beginLocalAuthorization().also { manualApprovalId = it }
                                        runtime.issueLocalClient("Local API client", id).also { manualClientId = it.first.id }
                                    }
                                    if (runtime.localAuthorization.value == manualApprovalId) token = checkNotNull(manualApprovalId) to issued.second
                                    else finishManualToken(false)
                                } catch (failure: Exception) {
                                    finishManualToken(false)
                                    throw failure
                                } finally { preparingToken.set(false) }
                            }
                        }
                    },
                    onRevoke = { runtime.disarm(); runtime.tokens.revokeAll() },
                    onProtect = { value -> act { runtime.protect(value) } },
                    onNotifications = { permissions.request("service_notifications") },
                    remote = remote,
                    onRemote = { enabled ->
                        lifecycleScope.launch {
                            actSuspend {
                                withContext(Dispatchers.IO) { if (enabled) runtime.enableRemote() else runtime.disableRemote() }
                            }
                        }
                    },
                    onPair = { lifecycleScope.launch { actSuspend { withContext(Dispatchers.IO) { runtime.openRemotePairing() } } } },
                    onCopyFingerprint = {
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Hands TLS fingerprint", remote.fingerprint))
                    },
                    onRevokeClient = runtime::revokeClient,
                    permissions = grants,
                    onPermission = permissions::request,
                    onRefreshPermissions = permissions::refresh,
                    onCopySecureGrant = {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("Hands secure settings setup", SECURE_SETTINGS_GRANT_COMMAND),
                        )
                    },
                    notificationSharing = notificationSharing,
                    onNotificationSharing = { startActivity(Intent(this, NotificationSettingsActivity::class.java)) },
                    onStopSharing = { runtime.notifications.close() },
                    privacyActive = privacyActive,
                    onPrivateEntry = runtime.secureEntry::open,
                    initialTab = destination,
                )

                if (pairing.open || pairing.pending != null) {
                    Dialog(
                        onDismissRequest = runtime::closePairingDialog,
                        properties = DialogProperties(
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false,
                            securePolicy = SecureFlagPolicy.SecureOn,
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xCC000000))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = runtime::closePairingDialog,
                                )
                                .padding(horizontal = 20.dp, vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { /* prevent backdrop dismiss on card click */ },
                                    )
                            ) {
                                RemotePairingPanel(
                                    pairing = pairing,
                                    remote = remote,
                                    onApprove = runtime.remotePairing::approve,
                                    onDeny = runtime.remotePairing::deny,
                                    onClose = runtime::closePairingDialog,
                                    onShare = ::sharePrompt,
                                    onCopyPrompt = ::copyPrompt,
                                )
                            }
                        }
                    }
                }

                token?.let { (approvalId, secret) ->
                    AlertDialog(
                        onDismissRequest = { finishManualToken(false); token = null },
                        shape = RoundedCornerShape(22.dp),
                        containerColor = HandsTheme.colors.surfaceElevated,
                        titleContentColor = HandsTheme.colors.textPrimary,
                        textContentColor = HandsTheme.colors.textSecondary,
                        title = { Text("Client token", style = HandsType.Title) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "Copy this token to your client's private credential storage. Closing without copying revokes it. Control still requires approval on this phone.",
                                    style = HandsType.Body,
                                )
                                GlassInsetCard {
                                    SelectionContainer {
                                        Text(secret, style = HandsType.Mono.copy(color = HandsTheme.colors.accent))
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            ActionButton(
                                "Copy token",
                                onClick = {
                                    val clip = ClipData.newPlainText("Hands token", secret)
                                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                                    finishManualToken(true)
                                    token = null
                                },
                                kind = ActionKind.PRIMARY,
                            )
                        },
                        dismissButton = {
                            ActionButton("Close", onClick = { finishManualToken(false); token = null })
                        },
                        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::permissions.isInitialized) permissions.returned()
        runtime.shizuku.refresh()
        runtime.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab.value = if (intent.getStringExtra("hands_page") == "access") 2 else 0
    }

    override fun onDestroy() {
        finishManualToken(false)
        if (::permissions.isInitialized) permissions.close()
        super.onDestroy()
    }

    private fun finishManualToken(copied: Boolean) {
        val clientId = manualClientId.also { manualClientId = null }
        val approvalId = manualApprovalId.also { manualApprovalId = null }
        if (!copied && clientId != null) runCatching { runtime.tokens.revoke(clientId) }
        if (approvalId != null) runtime.endLocalAuthorization(approvalId)
    }

    private fun startHost() {
        act { ContextCompat.startForegroundService(this, Intent(this, HandsHostService::class.java)) }
    }

    private fun overlaySettings() {
        permissions.request("overlay")
    }

    private fun sharePrompt(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hands Agent Connection")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(sendIntent, "Share Agent Connection Instructions"))
    }

    private fun copyPrompt(text: String) {
        val clip = ClipData.newPlainText("Hands Agent Instruction", text)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        runtime.message("Copied agent connection instructions to clipboard.")
    }

    private fun act(block: () -> Unit) {
        runCatching(block).onFailure { runtime.message(it.message ?: "Could not complete this operation.") }
    }

    private suspend fun actSuspend(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            runtime.message(t.message ?: "Could not complete this operation.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandsScreen(
    state: HandsState,
    engine: ShizukuStatus,
    clients: List<ClientTokens.Client>,
    onStart: () -> Unit,
    onStopService: () -> Unit,
    onAllow: () -> Unit,
    onDisarm: () -> Unit,
    onBackend: (String) -> Unit,
    onTarget: (String) -> Unit,
    onShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onAccessibility: () -> Unit,
    onOverlay: () -> Unit,
    onStartScreen: () -> Unit,
    onCreateToken: () -> Unit,
    onRevoke: () -> Unit,
    onProtect: (Set<String>) -> Unit,
    onNotifications: () -> Unit = {},
    remote: RemoteAccessState = RemoteAccessState(),
    onRemote: (Boolean) -> Unit = {},
    onPair: () -> Unit = {},
    onCopyFingerprint: () -> Unit = {},
    onRevokeClient: (String) -> Unit = {},
    permissions: List<HandsPermission> = emptyList(),
    onPermission: (String) -> Unit = {},
    onRefreshPermissions: () -> Unit = {},
    onCopySecureGrant: () -> Unit = {},
    notificationSharing: HandsNotificationState = HandsNotificationState(),
    onNotificationSharing: () -> Unit = {},
    onStopSharing: () -> Unit = {},
    privacyActive: Boolean = false,
    onPrivateEntry: () -> Unit = {},
    initialTab: Int = 0,
    onAccessibilityToggle: (Boolean) -> Unit = { onAccessibility() },
) {
    var tab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var protectDraft by remember(state.protectedPackages) { mutableStateOf(state.protectedPackages.joinToString("\n")) }

    Column(
        Modifier
            .fillMaxSize()
            .background(HandsTheme.colors.background)
            .safeDrawingPadding()
            .testTag("hands.screen")
    ) {
        PageToolbar("Hands", "${if (state.target == "agent") "AGENT" else "PHONE"} · ${if (state.serving) "ONLINE" else "OFF"}")

        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            GlassSegmentedControl(
                items = listOf("Control", "Clients", "Access", "Alerts"),
                selectedIndex = tab,
                onSelect = { tab = it },
            )
        }

        Column(
            Modifier
                .weight(1f)
                .clipToBounds()
                .testTag("hands.viewport")
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (tab == 0) {
                HandsSection("Session") {
                    if (privacyActive) {
                        Text("Private entry is shielding remote access.", style = HandsType.Body)
                        ActionButton("Finish private entry", onPrivateEntry, kind = ActionKind.PRIMARY)
                    }
                    ProtocolStatus(
                        "${if (state.armed) "Active controller" else "Control"}",
                        if (state.armed) state.client else if (state.serving) "Ready for a controller" else "Off"
                    )
                    Text(state.message, style = HandsType.Body, modifier = Modifier.testTag("hands.message"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.serving) ActionButton("Start service", onStart, Modifier.testTag("hands.start"), ActionKind.PRIMARY)
                        if (state.armed) ActionButton("Stop control", onDisarm, Modifier.testTag("hands.disarm"), ActionKind.DANGER)
                        if (state.serving) ActionButton("Stop service", onStopService, Modifier.testTag("hands.stop.service"), kind = ActionKind.GHOST)
                    }
                }

                HandsSection("Work screen") {
                    Choice("Separate agent screen", "Work in another display while you use your phone. Requires Shizuku and Android 14+.", state.target == "agent") { onTarget("agent") }
                    Choice("Phone screen", "The agent operates the screen you are using.", state.target == "primary") { onTarget("primary") }
                    if (state.target == "agent") {
                        Text(
                            if (state.displayId > 0) "Agent display ${state.displayId} · floating preview" else "No agent display open",
                            style = HandsType.Caption,
                        )
                        ActionButton(
                            if (state.displayId > 0) "Stop agent screen" else "Open agent screen",
                            if (state.displayId > 0) onDisarm else onStartScreen,
                            Modifier.testTag("hands.agent.open"),
                            enabled = state.serving,
                        )
                    }
                }

                HandsSection("Control engine") {
                    Choice("Hybrid", "Accessibility on the phone screen; Shizuku for the separate screen and when Accessibility is unavailable.", state.backend == "hybrid") { onBackend("hybrid") }
                    Choice("Shizuku", "Use the privileged Android backend for every screen action.", state.backend == "shizuku") { onBackend("shizuku") }
                    Choice("Accessibility", "Operate the phone screen through Android Accessibility.", state.backend == "accessibility") { onBackend("accessibility") }
                    Text(engine.message, style = HandsType.Caption, modifier = Modifier.testTag("hands.shizuku.status"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(if (engine.permissionGranted) "Connect Shizuku" else "Allow Shizuku", onShizuku, enabled = engine.running)
                        ActionButton(if (engine.installed) "Open Shizuku" else "Get Shizuku", onOpenShizuku)
                        ActionButton("Floating screen permission", onOverlay)
                        ActionButton("Notifications", onNotifications)
                    }
                    AccessibilitySwitch(permissions.firstOrNull { it.id == "accessibility" }?.granted == true, onAccessibilityToggle)
                }

                HandsSection("Protected apps") {
                    Text(
                        "Use exact package names or prefix.* rules. These apps cannot be opened, read or controlled. Saving changes ends the current session.",
                        style = HandsType.Caption,
                    )
                    OutlinedTextField(
                        value = protectDraft,
                        onValueChange = { protectDraft = it },
                        label = { Text("Package names · one per line") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        minLines = 2,
                    )
                    ActionButton(
                        "Save protected apps",
                        { onProtect(protectDraft.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()) },
                    )
                }
            } else if (tab == 1) {
                HandsSection("Connect an agent") {
                    Text(
                        "Any authorized agent or app can use Hands. Your model and conversations stay in the agent you choose.",
                        style = HandsType.Body,
                    )
                    Text(
                        "Use the encrypted HTTP API directly or the optional MCP bridge over LAN or Tailscale. Companion can connect locally.",
                        style = HandsType.Caption,
                    )
                }

                HandsSection("LAN / Tailscale") {
                    ProtocolStatus("Remote access", if (remote.enabled) "On · encrypted TLS" else "Off")
                    Text(remote.message, style = HandsType.Caption)
                    if (remote.enabled) {
                        if (remote.endpoints.isEmpty()) {
                            Text("No network address available. Connect Wi-Fi or Tailscale.", style = HandsType.Body)
                        }
                        remote.endpoints.forEach { endpoint ->
                            SelectionContainer { Text(endpoint, style = HandsType.Mono) }
                        }
                        Text("Phone fingerprint · SHA-256", style = HandsType.Caption)
                        SelectionContainer { Text(remote.fingerprint.chunked(8).joinToString(" "), style = HandsType.MonoSmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton("Pair remote agent", onPair, Modifier.testTag("hands.remote.pair"), ActionKind.PRIMARY)
                            ActionButton("Copy fingerprint", onCopyFingerprint)
                            ActionButton("Turn remote access off", { onRemote(false) }, kind = ActionKind.DANGER)
                        }
                    } else {
                        Text(
                            if (remote.autoStart)
                                "LAN / Tailscale encrypted access starts automatically when the service is running. Start the service on the Control tab."
                            else
                                "Turn this on to accept paired agents at this phone's network addresses. Pairing needs your approval and control requires the service to be running.",
                            style = HandsType.Body,
                        )
                        ActionButton(
                            if (remote.starting) "Starting encrypted access…" else "Enable LAN / Tailscale",
                            { onRemote(true) },
                            Modifier.testTag("hands.remote.enable"),
                            ActionKind.PRIMARY,
                            enabled = state.serving && !remote.starting,
                        )
                        if (!state.serving) Text("Start the service on the Control tab first.", style = HandsType.Caption)
                    }
                }

                HandsSection("Local connection") {
                    KeyValueRow("Phone endpoint", "127.0.0.1:8765")
                    Text("Create a token for a client using an ADB or SSH tunnel. Companion connects from its Device tab.", style = HandsType.Caption)
                    ActionButton("Create local token", onCreateToken, Modifier.testTag("hands.token.create"), ActionKind.PRIMARY)
                }

                HandsSection("Approved clients · ${clients.size}") {
                    if (clients.isEmpty()) Text("No clients connected yet.", style = HandsType.Body)
                    clients.forEach { client ->
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                client.label,
                                style = HandsType.Body,
                                modifier = Modifier.weight(1f).widthIn(min = 120.dp).padding(vertical = 12.dp),
                            )
                            ActionButton(
                                "Revoke",
                                { onRevokeClient(client.id) },
                                Modifier.semantics { contentDescription = "Revoke access for ${client.label}" },
                                ActionKind.DANGER,
                            )
                        }
                    }
                    ActionButton("Revoke all clients", onRevoke, kind = ActionKind.DANGER, enabled = clients.isNotEmpty())
                }
            } else if (tab == 2) {
                HandsSection("Mobile permissions") {
                    Text(
                        "Grant only the access you want agents to use. SMS, files and other direct data APIs still need an armed session. Notification sharing has its own approval in Alerts.",
                        style = HandsType.Body,
                    )
                    permissions.forEach { access ->
                        GroupSurface(padded = true) {
                            ProtocolStatus(access.title, if (access.granted) "Allowed" else "Off")
                            Text(access.summary, style = HandsType.Caption)
                            if (access.id == "secure_settings") {
                                if (!access.granted) {
                                    Text("Run this once from a connected computer with Android debugging enabled:", style = HandsType.Caption)
                                    SelectionContainer {
                                        Text(SECURE_SETTINGS_GRANT_COMMAND, style = HandsType.MonoSmall,
                                            modifier = Modifier.testTag("hands.secure_settings.command"))
                                    }
                                    ActionButton("Copy command", onCopySecureGrant,
                                        Modifier.testTag("hands.secure_settings.copy"), ActionKind.PRIMARY)
                                }
                                ActionButton("Refresh status", onRefreshPermissions,
                                    Modifier.testTag("hands.secure_settings.refresh"))
                            } else if (access.id == "accessibility") {
                                AccessibilitySwitch(access.granted, onAccessibilityToggle)
                            } else {
                                ActionButton(
                                    if (access.granted) "Manage" else "Allow",
                                    { onPermission(access.id) },
                                    Modifier.testTag("hands.permission.${access.id}"),
                                    if (access.granted) ActionKind.GHOST else ActionKind.PRIMARY,
                                )
                            }
                        }
                    }
                    Text(
                        "Android can restrict SMS and call-log grants for some installers. If a request is blocked, use this app's Android permission settings.",
                        style = HandsType.Caption,
                    )
                    ActionButton("Android app settings", { onPermission("app_settings") })
                }
            } else {
                HandsSection("Notifications") {
                    ProtocolStatus("Sharing", if (notificationSharing.enabled) { if (notificationSharing.paused) "Paused" else "On" } else "Off")
                    Text(notificationSharing.message, style = HandsType.Body)
                    Text(
                        "Choose one approved client, specific apps and a content limit. Only that client receives the passive inbox. Sharing never wakes an agent or injects messages into a conversation.",
                        style = HandsType.Caption,
                    )
                    Text(
                        "${notificationSharing.packages.size} apps · ${notificationSharing.content} · ${notificationSharing.queued} queued",
                        style = HandsType.MonoSmall,
                    )
                    ActionButton(
                        "Choose notification sharing",
                        onNotificationSharing,
                        Modifier.testTag("hands.notifications.configure"),
                        ActionKind.PRIMARY,
                        enabled = state.serving,
                    )
                    if (!state.serving) Text("Start the service on Control first.", style = HandsType.Caption)
                    if (notificationSharing.enabled) ActionButton("Stop sharing", onStopSharing, kind = ActionKind.DANGER)
                    ActionButton("Notification access", { onPermission("notifications") })
                }
            }
        }
    }
}

/** Secure live dialog with iOS frosted glass card and Share to Agent pairing workflow. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun RemotePairingPanel(
    pairing: PairingState,
    remote: RemoteAccessState,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onClose: () -> Unit,
    onShare: ((String) -> Unit)? = null,
    onCopyPrompt: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .88f).dp
    val instruction = remember(pairing.code, remote.endpoints, remote.fingerprint) {
        buildAgentPairingInstruction(pairing.code, remote.endpoints, remote.fingerprint)
    }

    GlassModalCard(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 500.dp)
            .heightIn(max = maxHeight),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SheetHeading("Pair an agent", onClose)

            pairing.pending?.let { pending ->
                GlassStatusPill("Agent Connection Request", "PENDING", active = true)

                GlassInsetCard {
                    Text(
                        "Allow ${pending.clientName} to connect?",
                        style = HandsType.Title.copy(color = HandsTheme.colors.textPrimary),
                    )
                    Text(
                        "Request from ${pending.peerAddress}",
                        style = HandsType.MonoSmall.copy(color = HandsTheme.colors.textSecondary),
                    )
                }

                Text(
                    "This agent receives its own revocable credential. Screen control still requires local session approval.",
                    style = HandsType.Body.copy(color = HandsTheme.colors.textSecondary),
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        "Approve agent",
                        onApprove,
                        Modifier.testTag("hands.pair.approve"),
                        ActionKind.PRIMARY,
                    )
                    ActionButton("Deny", onDeny, kind = ActionKind.DANGER)
                }
            } ?: run {
                Text(
                    "Share these instructions with your agent or enter this code in your agent's pairing setup, then approve its request here.",
                    style = HandsType.Body.copy(color = HandsTheme.colors.textSecondary),
                )

                GlassInsetCard {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = pairing.code,
                                style = HandsType.LargeTitle.copy(
                                    fontSize = 32.sp,
                                    letterSpacing = 4.sp,
                                    color = HandsTheme.colors.accent,
                                    fontWeight = FontWeight.Bold,
                                ),
                                modifier = Modifier.testTag("hands.pair.code"),
                            )
                            Text(
                                "Expires in ${pairing.expiresInSeconds} seconds · one use",
                                style = HandsType.Caption.copy(color = HandsTheme.colors.textTertiary),
                            )
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        "Share to agent",
                        onClick = {
                            if (onShare != null) onShare(instruction)
                            else {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, instruction)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Agent Pairing Instructions"))
                            }
                        },
                        modifier = Modifier.testTag("hands.pair.share"),
                        kind = ActionKind.PRIMARY,
                    )
                    ActionButton(
                        "Copy instruction",
                        onClick = {
                            if (onCopyPrompt != null) onCopyPrompt(instruction)
                            else {
                                val clip = ClipData.newPlainText("Hands Agent Instruction", instruction)
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                            }
                        },
                        modifier = Modifier.testTag("hands.pair.copy_prompt"),
                        kind = ActionKind.GHOST,
                    )
                }

                GlassInsetCard {
                    Text(
                        "Connection endpoints:",
                        style = HandsType.Caption.copy(color = HandsTheme.colors.textTertiary, fontWeight = FontWeight.SemiBold),
                    )
                    remote.endpoints.forEach { endpoint ->
                        SelectionContainer {
                            Text(endpoint, style = HandsType.MonoSmall.copy(color = HandsTheme.colors.textPrimary))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Verify the phone fingerprint before sending the code:",
                        style = HandsType.Caption.copy(color = HandsTheme.colors.textTertiary, fontWeight = FontWeight.SemiBold),
                    )
                    SelectionContainer {
                        Text(
                            remote.fingerprint.chunked(8).joinToString(" "),
                            style = HandsType.MonoSmall.copy(color = HandsTheme.colors.textSecondary),
                        )
                    }
                }
            }
            if (pairing.message.isNotEmpty()) {
                Text(pairing.message, style = HandsType.Caption.copy(color = HandsTheme.colors.textTertiary))
            }
        }
    }
}

@Composable
private fun HandsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(padded = true, modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        content()
    }
}

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(HandsShapes.Small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = HandsType.Subtitle.copy(color = HandsTheme.colors.textPrimary))
            Text(detail, style = HandsType.Caption.copy(color = HandsTheme.colors.textSecondary))
        }
    }
}

package app.hermes.hands

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermes.hands.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotificationChoices(
    val enabled: Boolean,
    val clientId: String?,
    val packages: Set<String>,
    val content: String,
    val retentionMinutes: Int,
    val includeCalls: Boolean,
)

/** Authority changes are rendered only after existing agent work has been revoked/drained. */
class NotificationSettingsActivity : ComponentActivity() {
    private val runtime get() = (application as HandsApplication).runtime
    private var approvalId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        approvalId = savedInstanceState?.getString("local_approval_id")

        setContent {
            val current by runtime.localAuthorization.collectAsStateWithLifecycle()
            val notificationState by runtime.notifications.state.collectAsStateWithLifecycle()
            var preparing by remember { mutableStateOf(approvalId == null) }
            var failure by remember { mutableStateOf<String?>(null) }
            val revision by runtime.tokens.revision.collectAsStateWithLifecycle()
            val clients = remember(revision) { runtime.tokens.clients() }

            LaunchedEffect(Unit) {
                if (approvalId == null) {
                    var owned: String? = null
                    try {
                        approvalId = withContext(Dispatchers.IO) {
                            runtime.beginLocalAuthorization().also { owned = it }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        owned?.let(runtime::endLocalAuthorization)
                        throw cancelled
                    } catch (error: Exception) {
                        owned?.let(runtime::endLocalAuthorization)
                        failure = error.message ?: "Could not prepare approval."
                    } finally {
                        preparing = false
                    }
                }
            }

            LaunchedEffect(current, preparing) {
                if (!preparing && approvalId != null && runtime.localAuthorization.value != approvalId && !isFinishing) {
                    finish()
                }
            }

            HandsTheme {
                if (preparing || approvalId == null) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(HandsTheme.colors.background)
                            .safeDrawingPadding()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PageToolbar("Notification sharing", "LOCAL APPROVAL")
                        Text(failure ?: "Stopping control before approval…", style = HandsType.Body)
                        GlassButton("Cancel", { finish() }, kind = GlassButtonKind.GHOST)
                    }
                } else {
                    val accessGranted = MobilePermissions.states(this).firstOrNull { it.id == "notifications" }?.granted == true
                    NotificationConsentScreen(
                        initial = notificationState,
                        clients = clients,
                        accessGranted = accessGranted,
                        onSave = { choice ->
                            runCatching {
                                runtime.configureNotifications(
                                    choice.enabled,
                                    choice.clientId,
                                    choice.packages,
                                    choice.content,
                                    choice.retentionMinutes,
                                    choice.includeCalls,
                                    checkNotNull(approvalId),
                                )
                                finish()
                            }.onFailure {
                                failure = it.message ?: "Could not save notification sharing."
                            }
                        },
                        onClose = { finish() },
                        failure = failure,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        approvalId?.let { outState.putString("local_approval_id", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) approvalId?.let(runtime::endLocalAuthorization)
        super.onDestroy()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun NotificationConsentScreen(
    initial: HandsNotificationState,
    clients: List<ClientTokens.Client>,
    accessGranted: Boolean,
    onSave: (NotificationChoices) -> Unit,
    onClose: () -> Unit,
    failure: String? = null,
) {
    HandsTheme {
        NotificationConsentContent(
            initial = initial,
            clients = clients,
            accessGranted = accessGranted,
            onSave = onSave,
            onClose = onClose,
            failure = failure,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NotificationConsentContent(
    initial: HandsNotificationState,
    clients: List<ClientTokens.Client>,
    accessGranted: Boolean,
    onSave: (NotificationChoices) -> Unit,
    onClose: () -> Unit,
    failure: String? = null,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(initial.enabled) }
    val defaultRecipient = initial.clientId?.takeIf { id -> clients.any { it.id == id } }
        ?: clients.firstOrNull()?.id
    var recipient by remember { mutableStateOf(defaultRecipient) }
    var packages by remember { mutableStateOf(initial.packages.ifEmpty { setOf("*") }) }
    var specificApps by remember { mutableStateOf(initial.packages.filter { it != "*" }.toSet()) }
    var content by remember { mutableStateOf(if (initial.content.isBlank()) "full" else initial.content) }
    var retention by remember { mutableIntStateOf(initial.retentionMinutes) }
    var calls by remember { mutableStateOf(initial.includeCalls) }
    var choosingApps by remember { mutableStateOf(false) }

    val hasValidRecipient = clients.any { it.id == recipient }
    val canSave = !enabled || (accessGranted && packages.isNotEmpty() && hasValidRecipient)

    Column(
        Modifier
            .fillMaxSize()
            .background(HandsTheme.colors.background)
            .safeDrawingPadding()
            .testTag("hands.notifications.consent"),
    ) {
        PageToolbar("Notification sharing", if (enabled) "SHARING ENABLED" else "SHARING OFF")
        Column(
            Modifier
                .weight(1f)
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SheetHeading(
                title = if (choosingApps) "Observed apps" else "Notification approval",
                onClose = if (choosingApps) ({
                    packages = specificApps
                    choosingApps = false
                }) else onClose,
            )

            if (choosingApps) {
                HandsNotificationAppPicker(
                    selected = specificApps,
                    onSelect = { pkg, selected ->
                        specificApps = if (selected) specificApps + pkg else specificApps - pkg
                        packages = specificApps
                    },
                    onDone = {
                        packages = specificApps
                        choosingApps = false
                    },
                )
            } else {
                Text(
                    text = "Choose exactly who receives notifications and what they may read. Agents cannot expand this approval.",
                    style = HandsType.Body.copy(color = HandsTheme.colors.textSecondary),
                )

                // Warning & shortcut if notification access is not granted in Android settings
                if (!accessGranted) {
                    GlassCard(borderStroke = BorderStroke(1.dp, HandsTheme.colors.warning)) {
                        Text(
                            text = "Notification access is required in Android settings before notifications can be shared.",
                            style = HandsType.Body.copy(color = HandsTheme.colors.warning),
                        )
                        GlassButton(
                            label = "Open Android notification access",
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                            kind = GlassButtonKind.PRIMARY,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Master Toggle Card
                GlassCard {
                    GlassPreferenceToggle(
                        title = "Share notifications",
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        summary = "Stream incoming notifications to the chosen agent.",
                        modifier = Modifier.testTag("hands.notifications.enabled"),
                    )
                }

                // Recipient Section
                GlassGroupedSection("Recipient") {
                    if (clients.isEmpty()) {
                        Text(
                            text = "No approved client. Connect an app or agent from the Clients tab first.",
                            style = HandsType.Body.copy(color = HandsTheme.colors.textSecondary),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        clients.forEach { client ->
                            GlassPreferenceRow(
                                title = client.label,
                                summary = "Client ${client.id.take(8)}",
                                selected = recipient == client.id,
                                value = if (recipient == client.id) "Selected" else null,
                                onClick = { recipient = client.id },
                                modifier = Modifier.testTag("hands.notifications.client.${client.id}"),
                            )
                        }
                    }
                }

                // Scope Section
                val isAllApps = "*" in packages
                GlassGroupedSection("Scope") {
                    GlassPreferenceRow(
                        title = "All notifications",
                        summary = "Share notifications from all apps (except protected & suppressed apps)",
                        selected = isAllApps,
                        value = if (isAllApps) "Selected" else null,
                        onClick = { packages = setOf("*") },
                        modifier = Modifier.testTag("hands.notifications.scope.all"),
                    )

                    GlassPreferenceRow(
                        title = "Specific apps only",
                        summary = if (isAllApps) {
                            if (specificApps.isEmpty()) "Tap to select apps" else "${specificApps.size} apps configured"
                        } else {
                            if (packages.isEmpty()) "Tap to select at least 1 app" else "${packages.size} selected"
                        },
                        selected = !isAllApps,
                        value = if (!isAllApps) "${packages.size} selected" else null,
                        onClick = {
                            if (isAllApps) {
                                packages = specificApps
                            }
                            choosingApps = true
                        },
                        modifier = Modifier.testTag("hands.notifications.apps"),
                    )
                }

                // Allowed Content Section
                GlassGroupedSection("Allowed content") {
                    listOf(
                        "metadata" to "App and event only",
                        "title" to "Include title",
                        "full" to "Include title and message",
                    ).forEach { (mode, label) ->
                        GlassPreferenceRow(
                            title = label,
                            selected = content == mode,
                            value = if (content == mode) "Selected" else null,
                            onClick = { content = mode },
                            modifier = Modifier.testTag("hands.notifications.content.$mode"),
                        )
                    }

                    GlassPreferenceToggle(
                        title = "Call notifications",
                        checked = calls,
                        onCheckedChange = { calls = it },
                        summary = "Call alerts only; not call audio or complete call state.",
                    )
                }

                // Retention Section
                GlassGroupedSection("Retention") {
                    listOf(2, 15, 60).forEach { minutes ->
                        GlassPreferenceRow(
                            title = "$minutes minutes",
                            selected = retention == minutes,
                            value = if (retention == minutes) "Selected" else null,
                            onClick = { retention = minutes },
                        )
                    }
                }

                Text(
                    text = "The inbox is passive and bounded. No agent wakes or chat injection happens automatically. Hands and Companion service events and Telegram clients are excluded. Changing approval discards queued events.",
                    style = HandsType.Caption.copy(color = HandsTheme.colors.textTertiary),
                )

                // Failure strip
                failure?.let {
                    GlassStatusStrip(it, tone = HandsTheme.colors.danger)
                }

                // Explanatory guidance if Save is disabled while sharing is toggled ON
                if (enabled && !canSave) {
                    GlassInsetCard {
                        Text(
                            text = "To start sharing, please complete:",
                            style = HandsType.Body.copy(
                                color = HandsTheme.colors.warning,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            ),
                        )
                        if (!accessGranted) {
                            Text("• Grant Android Notification access above", style = HandsType.Caption.copy(color = HandsTheme.colors.textPrimary))
                        }
                        if (clients.isEmpty()) {
                            Text("• Connect an agent on the Clients tab", style = HandsType.Caption.copy(color = HandsTheme.colors.textPrimary))
                        } else if (!hasValidRecipient) {
                            Text("• Select a recipient agent above", style = HandsType.Caption.copy(color = HandsTheme.colors.textPrimary))
                        }
                        if (packages.isEmpty()) {
                            Text("• Choose at least 1 observed app or select All notifications above", style = HandsType.Caption.copy(color = HandsTheme.colors.textPrimary))
                        }
                    }
                }

                // Action Buttons
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GlassButton(
                        label = "Save sharing",
                        onClick = { onSave(NotificationChoices(enabled, recipient, packages, content, retention, calls)) },
                        modifier = Modifier.testTag("hands.notifications.save"),
                        kind = GlassButtonKind.PRIMARY,
                        enabled = canSave,
                    )
                    GlassButton(
                        label = "Cancel",
                        onClick = onClose,
                        kind = GlassButtonKind.GHOST,
                    )
                }
            }
        }
    }
}

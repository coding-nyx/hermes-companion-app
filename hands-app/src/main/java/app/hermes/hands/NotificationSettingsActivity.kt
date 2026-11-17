package app.hermes.hands

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermes.companion.design.ActionKind
import app.hermes.companion.design.GroupedSection
import app.hermes.companion.design.PreferenceRow
import app.hermes.companion.design.PreferenceToggle
import app.hermes.companion.design.StatusStrip
import app.hermes.companion.device.NotificationAppPicker
import app.hermes.hands.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotificationChoices(val enabled: Boolean, val clientId: String?, val packages: Set<String>,
    val content: String, val retentionMinutes: Int, val includeCalls: Boolean)

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
                    try { approvalId = withContext(Dispatchers.IO) { runtime.beginLocalAuthorization().also { owned = it } } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { owned?.let(runtime::endLocalAuthorization); throw cancelled }
                    catch (error: Exception) { owned?.let(runtime::endLocalAuthorization); failure = error.message ?: "Could not prepare approval." }
                    finally { preparing = false }
                }
            }
            LaunchedEffect(current, preparing) {
                if (!preparing && approvalId != null && runtime.localAuthorization.value != approvalId && !isFinishing) finish()
            }
            HandsTheme {
                if (preparing || approvalId == null) Column(Modifier.fillMaxSize().background(HandsTheme.colors.background).safeDrawingPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PageToolbar("Notification sharing", "LOCAL APPROVAL")
                    Text(failure ?: "Stopping control before approval…", style = HandsType.Body)
                    ActionButton("Cancel", { finish() })
                } else NotificationConsentScreen(notificationState, clients,
                    MobilePermissions.states(this).first { it.id == "notifications" }.granted,
                    onSave = { choice ->
                        runCatching {
                            runtime.configureNotifications(choice.enabled, choice.clientId, choice.packages, choice.content,
                                choice.retentionMinutes, choice.includeCalls, checkNotNull(approvalId))
                            finish()
                        }.onFailure { failure = it.message ?: "Could not save notification sharing." }
                    }, onClose = { finish() }, failure = failure)
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { approvalId?.let { outState.putString("local_approval_id", it) }; super.onSaveInstanceState(outState) }
    override fun onDestroy() { if (!isChangingConfigurations) approvalId?.let(runtime::endLocalAuthorization); super.onDestroy() }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun NotificationConsentScreen(initial: HandsNotificationState, clients: List<ClientTokens.Client>, accessGranted: Boolean,
    onSave: (NotificationChoices) -> Unit, onClose: () -> Unit, failure: String? = null) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var recipient by remember { mutableStateOf(initial.clientId) }
    var packages by remember { mutableStateOf(initial.packages) }
    var content by remember { mutableStateOf(initial.content) }
    var retention by remember { mutableIntStateOf(initial.retentionMinutes) }
    var calls by remember { mutableStateOf(initial.includeCalls) }
    var choosingApps by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(HandsTheme.colors.background).safeDrawingPadding().testTag("hands.notifications.consent")) {
        PageToolbar("Notification sharing", "REMOTE CONTROL PAUSED")
        Column(Modifier.weight(1f).clipToBounds().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SheetHeading(if (choosingApps) "Observed apps" else "Phone approval", onClose = if (choosingApps) ({ choosingApps = false }) else onClose)
            if (choosingApps) NotificationAppPicker(packages) { pkg, selected ->
                packages = if (selected) packages + pkg else packages - pkg
            } else {
                Text("Choose exactly who receives notifications and what they may read. Agents cannot expand this approval.", style = HandsType.Body)
                if (!accessGranted) StatusStrip("Allow notification access on Hands' Access tab before starting sharing.", tone = HandsTheme.colors.warning)
                PreferenceToggle("Share notifications", enabled, { enabled = !enabled }, modifier = Modifier.testTag("hands.notifications.enabled"))
                GroupedSection("Recipient") {
                    if (clients.isEmpty()) PreferenceRow("No approved client", "Connect an app or agent from Clients first.")
                    clients.forEach { client -> PreferenceRow(client.label, "Client ${client.id.take(8)}", selected = recipient == client.id,
                        value = if (recipient == client.id) "Selected" else null, onClick = { recipient = client.id },
                        modifier = Modifier.testTag("hands.notifications.client.${client.id}")) }
                }
                GroupedSection("Allowed content") {
                    PreferenceRow("Observed apps", "${packages.size} selected", value = "Choose", onClick = { choosingApps = true },
                        modifier = Modifier.testTag("hands.notifications.apps"))
                    listOf("metadata" to "App and event only", "title" to "Include title", "full" to "Include title and message").forEach { (mode, label) ->
                        PreferenceRow(label, selected = content == mode, value = if (content == mode) "Selected" else null,
                            onClick = { content = mode }, modifier = Modifier.testTag("hands.notifications.content.$mode"))
                    }
                    PreferenceToggle("Call notifications", calls, { calls = !calls }, summary = "Selected-app call alerts only; not call audio or complete call state.")
                }
                GroupedSection("Retention") {
                    listOf(2, 15, 60).forEach { minutes -> PreferenceRow("$minutes minutes", selected = retention == minutes,
                        value = if (retention == minutes) "Selected" else null, onClick = { retention = minutes }) }
                }
                Text("The inbox is passive and bounded. No agent wakes or chat injection happens automatically. Hands and Companion service events and Telegram clients are excluded. Changing approval discards queued events.", style = HandsType.Caption)
                failure?.let { StatusStrip(it, tone = HandsTheme.colors.danger) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Save sharing", { onSave(NotificationChoices(enabled, recipient, packages, content, retention, calls)) },
                        Modifier.testTag("hands.notifications.save"), ActionKind.PRIMARY,
                        enabled = !enabled || (accessGranted && packages.isNotEmpty() && clients.any { it.id == recipient }))
                    ActionButton("Cancel", onClose)
                }
            }
        }
    }
}

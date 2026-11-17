package app.hermes.hands

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.ActionKind
import app.hermes.hands.ui.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit, local approval. No credentials in deep links, logs or launch extras. */
class ConnectActivity : ComponentActivity() {
    private var approvalId: String? = null
    private val runtime get() = (application as HandsApplication).runtime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val caller = callingPackage ?: run { setResult(Activity.RESULT_CANCELED); finish(); return }
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(caller, 0)).toString() }.getOrDefault(caller)
        approvalId = savedInstanceState?.getString("local_approval_id")
        val incomingProtection = intent.getStringArrayListExtra("hands_protected_packages").orEmpty().toSet()
        setContent { HandsTheme {
            var failure by remember { mutableStateOf<String?>(null) }
            var preparing by remember { mutableStateOf(approvalId == null) }
            val currentApproval by runtime.localAuthorization.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                if (approvalId == null) {
                    var prepared: String? = null
                    try {
                        approvalId = withContext(Dispatchers.IO) {
                            runtime.beginLocalAuthorization().also { prepared = it }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        prepared?.let(runtime::endLocalAuthorization)
                        throw cancelled
                    } catch (error: Exception) {
                        prepared?.let(runtime::endLocalAuthorization)
                        failure = error.message ?: "Could not prepare local approval."
                    } finally { preparing = false }
                }
            }
            LaunchedEffect(currentApproval, preparing) {
                if (!preparing && approvalId != null && runtime.localAuthorization.value != approvalId && !isFinishing) {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            }
            Column(Modifier.fillMaxSize().background(HandsTheme.colors.background).safeDrawingPadding()) {
                PageToolbar("Connect to Hands", "LOCAL APPROVAL")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    GlassCard(padded = true, modifier = Modifier.fillMaxWidth()) {
                        if (preparing) Text("Stopping control before local approval…", style = HandsType.Body)
                        else {
                            Text("Allow $label to connect?", style = HandsType.Title)
                            Text("$caller will receive its own revocable client token. It can request control while the Hands service is running. You can stop the service or revoke its access at any time.", style = HandsType.Body)
                            if (incomingProtection.isNotEmpty()) Text("Add this client's ${incomingProtection.size} protected apps to Hands. Existing protection rules stay in place.", style = HandsType.Body)
                        }
                    }
                    failure?.let { Text(it, style = HandsType.Body.copy(color = HandsTheme.colors.danger)) }
                    if (!preparing) ActionButton("Allow connection", {
                        var issuedClientId: String? = null
                        runCatching {
                            val id = checkNotNull(approvalId) { "approval_cancelled" }
                            val (client, token) = runtime.issueLocalClient(label, id, incomingProtection)
                            issuedClientId = client.id
                            ContextCompat.startForegroundService(this@ConnectActivity, Intent(this@ConnectActivity, HandsHostService::class.java))
                            setResult(Activity.RESULT_OK, Intent().putExtra("hands_token", token).putExtra("hands_endpoint", "http://127.0.0.1:8765"))
                            runtime.endLocalAuthorization(id)
                            finish()
                        }.onFailure {
                            issuedClientId?.let { id -> runCatching { runtime.tokens.revoke(id) } }
                            failure = it.message ?: "Could not create this connection."
                        }
                    }, kind = ActionKind.PRIMARY, enabled = approvalId != null && currentApproval == approvalId)
                    ActionButton("Cancel", { setResult(Activity.RESULT_CANCELED); finish() })
                }
            }
        } }
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

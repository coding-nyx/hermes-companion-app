package app.hermes.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import app.hermes.companion.domain.WakePolicy
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import app.hermes.companion.device.HandsService
import app.hermes.companion.device.LiveOverlay
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.domain.DeviceLanePolicy
import app.hermes.companion.model.DeviceArm

class MainActivity : ComponentActivity() {
    private val reconnectNonce = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val originExtra = intent.getStringExtra(EXTRA_ORIGIN)
        val autoConnect = intent.getBooleanExtra(EXTRA_AUTOCONNECT, originExtra != null)
        setContent {
            CompanionTheme {
                val app = application as CompanionApp
                val vm: CompanionViewModel = viewModel(factory = CompanionViewModel.factory(app))
                val state by vm.state.collectAsStateWithLifecycle()
                val reconnect by reconnectNonce.collectAsStateWithLifecycle()
                val overlay = remember { LiveOverlay(app) }
                DisposableEffect(Unit) { onDispose { overlay.hide() } }
                val notifyLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    vm.setNotifyGranted(granted || notifyAllowed())
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            val enabled = Settings.Secure.getString(
                                contentResolver,
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            )
                            vm.setA11yBound(DeviceLanePolicy.a11yEnabled(enabled))
                            vm.setOverlayGranted(LiveOverlay.allowed(this@MainActivity))
                            vm.setNotifyGranted(notifyAllowed())
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                LaunchedEffect(reconnect, originExtra, autoConnect) {
                    val origin = intent.getStringExtra(EXTRA_ORIGIN) ?: originExtra
                    val auto = intent.getBooleanExtra(EXTRA_AUTOCONNECT, autoConnect)
                    if (origin != null) {
                        vm.onOriginChange(origin)
                        if (auto) vm.connect(origin)
                    } else if (state.stayConnected && state.origin == null && state.originInput.isNotBlank()) {
                        vm.connect()
                    }
                }
                LaunchedEffect(intent.dataString, intent.action) {
                    WakePolicy.parseDeepLink(intent.dataString.orEmpty())?.let { (session, profile) ->
                        vm.openWake(profile, session)
                    }
                }
                LaunchedEffect(state.lastWake) {
                    state.lastWake?.let { WakeNotifier.show(this@MainActivity, it) }
                }
                LaunchedEffect(state.stayConnected) {
                    val stay = Intent(app, StayConnectedService::class.java)
                    if (state.stayConnected) {
                        runCatching { ContextCompat.startForegroundService(app, stay) }
                    } else {
                        app.stopService(stay)
                    }
                }
                LaunchedEffect(state.arm) {
                    val intent = Intent(app, HandsService::class.java)
                    if (state.arm == DeviceArm.DISARMED) {
                        overlay.hide()
                        app.stopService(intent)
                    } else {
                        overlay.show()
                        runCatching { ContextCompat.startForegroundService(app, intent) }
                    }
                }
                CompanionShell(
                    state = state,
                    onOriginChange = vm::onOriginChange,
                    onUsernameChange = vm::onUsernameChange,
                    onPasswordChange = vm::onPasswordChange,
                    onConnect = { vm.connect() },
                    onSelectProfile = vm::selectProfile,
                    onTab = vm::selectTab,
                    onOpenSession = vm::openSession,
                    onNewThread = vm::newThread,
                    onCloseChat = vm::closeChat,
                    onDraftChange = vm::onDraftChange,
                    onSend = vm::send,
                    onInterrupt = vm::interrupt,
                    onApproval = vm::respondApproval,
                    onLoadOlder = vm::loadOlder,
                    onRewind = vm::beginRewind,
                    onCancelRewind = vm::cancelRewind,
                    onPair = vm::startPair,
                    onCancelPair = vm::cancelPair,
                    onRevokePair = vm::revokePair,
                    onArm = vm::arm,
                    onDisarm = vm::disarm,
                    onEnableA11y = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onEnableOverlay = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                    onEnableNotify = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.setNotifyGranted(true)
                        }
                    },
                    onNtfyTopicChange = vm::onNtfyTopicChange,
                    onSaveNtfy = vm::saveNtfy,
                    onToggleStay = vm::toggleStayConnected,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reconnectNonce.value++
    }

    private fun notifyAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_AUTOCONNECT = "autoconnect"
    }
}

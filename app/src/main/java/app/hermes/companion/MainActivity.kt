package app.hermes.companion

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
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
import app.hermes.companion.device.LiveOverlay
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.domain.DeviceLanePolicy
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.voice.VoiceInputManager
import app.hermes.companion.voice.WakeWordService

class MainActivity : ComponentActivity() {
    private val reconnectNonce = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enable display wake and show when locked for privileged companion access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        val app = application as CompanionApp
        // Only intents this process minted (wake / stay-connected notifications) carry the nonce.
        // Anything else is another app: origin extras are ignored and deep links need confirmation.
        val originExtra = if (trusted(intent, app)) intent.getStringExtra(EXTRA_ORIGIN) else null
        val autoConnect = intent.getBooleanExtra(EXTRA_AUTOCONNECT, originExtra != null)
        setContent {
            CompanionTheme {
                val vm: CompanionViewModel = viewModel(factory = CompanionViewModel.factory(app))
                val state by vm.state.collectAsStateWithLifecycle()
                val reconnect by reconnectNonce.collectAsStateWithLifecycle()
                val overlay = remember { LiveOverlay(app) }
                val voiceManager = remember { VoiceInputManager(this@MainActivity) }
                DisposableEffect(Unit) {
                    onDispose {
                        overlay.hide()
                        voiceManager.stopListening()
                    }
                }

                val notifyLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    vm.setNotifyGranted(granted || notifyAllowed())
                }

                val audioLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        vm.setVoiceListening(true)
                        voiceManager.startListening(
                            onPartial = { vm.onDraftChange(it) },
                            onResult = { vm.onVoiceTranscript(it) },
                            onError = { vm.setVoiceListening(false) },
                            onStateChange = { vm.setVoiceListening(it) },
                        )
                    }
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

                            if (state.lockedAccess) {
                                val km = getSystemService(KeyguardManager::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    km?.requestDismissKeyguard(this@MainActivity, null)
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(reconnect, originExtra, autoConnect) {
                    val own = trusted(intent, app)
                    val origin = (if (own) intent.getStringExtra(EXTRA_ORIGIN) else null) ?: originExtra
                    val auto = own && intent.getBooleanExtra(EXTRA_AUTOCONNECT, autoConnect)
                    if (origin != null) {
                        vm.onOriginChange(origin)
                        if (auto) vm.connect(origin)
                    } else if (state.origin == null && state.originInput.isNotBlank()) {
                        vm.connect()
                    }
                }

                LaunchedEffect(reconnect, intent.dataString, intent.action) {
                    WakePolicy.parseDeepLink(intent.dataString.orEmpty())?.let { (session, profile) ->
                        if (trusted(intent, app)) vm.openWake(profile, session)
                        else vm.requestDeepLink(profile, session)
                        // Consume so a config change does not replay the request.
                        intent.data = null
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

                // HandsService follows arm state inside DeviceNodeCoordinator; the Activity only owns the overlay.
                LaunchedEffect(state.arm) {
                    if (state.arm == DeviceArm.DISARMED) overlay.hide() else overlay.show()
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
                    onToggleAwakeOnVoice = vm::toggleAwakeOnVoice,
                    onToggleLockedAccess = vm::toggleLockedAccess,
                    onAddProtected = vm::addProtectedPackage,
                    onRemoveProtected = vm::removeProtectedPackage,
                    onConfirmDeepLink = vm::confirmDeepLink,
                    onDismissDeepLink = vm::dismissDeepLink,
                    onExecuteTerminal = vm::executeTerminal,
                    onClearTerminal = vm::clearTerminalLogs,
                    onRefreshGit = vm::loadGitStatus,
                    onSelectGitFile = vm::loadGitDiff,
                    onCloseGitDiff = vm::closeGitDiff,
                    onStageGitFile = vm::stageGitFile,
                    onCommitGit = vm::commitGit,
                    onRefreshCron = vm::loadCronJobs,
                    onTriggerCron = vm::triggerCronJob,
                    onToggleCron = vm::toggleCronJob,
                    onSwitchModel = vm::switchModel,
                    onSelectGateway = vm::selectGateway,
                    onAddGateway = vm::addSavedGateway,
                    onCheckUpdate = vm::checkUpdates,
                    onApplyUpdate = vm::applyUpdate,
                    onVoiceClick = {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            vm.setVoiceListening(true)
                            voiceManager.startListening(
                                onPartial = { vm.onDraftChange(it) },
                                onResult = { vm.onVoiceTranscript(it) },
                                onError = { vm.setVoiceListening(false) },
                                onStateChange = { vm.setVoiceListening(it) },
                            )
                        } else {
                            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reconnectNonce.value++
    }

    private fun trusted(intent: Intent?, app: CompanionApp): Boolean =
        intent?.getStringExtra(EXTRA_NONCE) == app.launchNonce

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
        const val EXTRA_NONCE = "launch_nonce"
    }
}

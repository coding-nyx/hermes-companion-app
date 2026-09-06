package app.hermes.companion

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import app.hermes.companion.domain.WakePolicy
import app.hermes.companion.model.ChatBlock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.hermes.companion.domain.PrivilegePolicy
import app.hermes.companion.model.DeviceArm
import app.hermes.companion.voice.VoiceInputManager
import app.hermes.companion.voice.WakeWordService

class MainActivity : FragmentActivity() {
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
                // Activity-result pickers briefly ON_STOP the activity; skip biometric lock while outstanding.
                var pickerOutstanding by remember { mutableStateOf(false) }
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

                var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
                val audioLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        pendingAudioAction?.invoke()
                    }
                    pendingAudioAction = null
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, pickerOutstanding) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) vm.setFleetHealthForeground(true)
                        if (event == Lifecycle.Event.ON_STOP) {
                            vm.setFleetHealthForeground(false)
                            if (!pickerOutstanding) vm.lockIfEnabled()
                        }
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
                    WakePolicy.parseDeepLink(intent.dataString.orEmpty())?.let { link ->
                        if (trusted(intent, app)) vm.openWake(link.origin, link.profile, link.sessionId)
                        else vm.requestDeepLink(link.origin, link.profile, link.sessionId)
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

                val photoPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    pickerOutstanding = false
                    uri?.let {
                        vm.queueAttachment(
                            it,
                            contentResolver.getType(it) ?: "image/jpeg",
                            displayName(it),
                        )
                    }
                }
                val videoPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    pickerOutstanding = false
                    uri?.let {
                        vm.queueAttachment(
                            it,
                            contentResolver.getType(it) ?: "video/mp4",
                            displayName(it),
                        )
                    }
                }
                val filePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    pickerOutstanding = false
                    uri?.let {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        vm.queueAttachment(
                            it,
                            contentResolver.getType(it) ?: "application/octet-stream",
                            displayName(it),
                        )
                    }
                }
                var cameraUri by remember { mutableStateOf<Uri?>(null) }
                val takePicture = rememberLauncherForActivityResult(
                    ActivityResultContracts.TakePicture(),
                ) { ok ->
                    pickerOutstanding = false
                    if (ok) cameraUri?.let { vm.queueAttachment(it, "image/jpeg", "camera.jpg") }
                }
                val launchCamera: () -> Unit = {
                    val file = java.io.File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    cameraUri = uri
                    takePicture.launch(uri)
                }
                val cameraPerm = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) launchCamera() else pickerOutstanding = false
                }

                val gate = remember { BiometricGate(this@MainActivity) }
                DisposableEffect(state.appLocked) {
                    if (state.appLocked) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    onDispose { }
                }
                LaunchedEffect(state.appLocked) {
                    if (state.appLocked) {
                        gate.authenticate(
                            title = "Unlock Hermes",
                            onSuccess = vm::unlock,
                            onFail = { msg -> if (msg.isNotBlank()) vm.noteError(msg) },
                        )
                    }
                }

                Box(Modifier.fillMaxSize()) {
                CompanionShell(
                    state = state,
                    onOriginChange = vm::onOriginChange,
                    onUsernameChange = vm::onUsernameChange,
                    onPasswordChange = vm::onPasswordChange,
                    onConnect = { vm.connect() },
                    onSelectConnectGateway = vm::selectConnectChoice,
                    onForgetConnectGateway = vm::forgetConnectChoice,
                    onSelectProfile = vm::selectProfile,
                    onTab = vm::selectTab,
                    onOpenSession = vm::openSession,
                    onNewThread = vm::newThread,
                    onRequestDelete = vm::requestDelete,
                    onConfirmDelete = vm::confirmDelete,
                    onCancelDelete = vm::cancelDelete,
                    onCloseChat = vm::closeChat,
                    onDraftChange = vm::onDraftChange,
                    onSend = vm::send,
                    onInterrupt = vm::interrupt,
                    onApproval = { choice ->
                        val prompt = state.approval
                        if (prompt != null && PrivilegePolicy.requiresPresence(prompt.kind, prompt.command, choice)) {
                            gate.authenticate(
                                title = "Confirm ${prompt.kind}",
                                subtitle = prompt.command.take(120),
                                onSuccess = {
                                    dismissKeyguardIfNeeded()
                                    vm.respondApproval(choice)
                                },
                                onFail = { msg -> if (msg.isNotBlank()) vm.noteError(msg) },
                            )
                        } else {
                            vm.respondApproval(choice)
                        }
                    },
                    onLoadOlder = vm::loadOlder,
                    onRewind = vm::beginRewind,
                    onCancelRewind = vm::cancelRewind,
                    onPair = vm::startPair,
                    onRepairPair = vm::repair,
                    onApprovePair = vm::approvePair,
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
                    onDisconnect = vm::disconnect,
                    onToggleAwakeOnVoice = vm::toggleAwakeOnVoice,
                    onToggleLockedAccess = vm::toggleLockedAccess,
                    onToggleBiometricLock = {
                        val enabling = !state.biometricLock
                        gate.authenticate(
                            title = if (enabling) "Enable biometric lock" else "Disable biometric lock",
                            onSuccess = { vm.setBiometricLock(enabling) },
                            onFail = { msg -> if (msg.isNotBlank()) vm.noteError(msg) },
                        )
                    },
                    onRenameDevice = vm::renameDeviceLabel,
                    onAddProtected = vm::addProtectedPackage,
                    onRemoveProtected = { pkg ->
                        gate.authenticate(
                            title = "Remove denylist rule",
                            subtitle = pkg,
                            onSuccess = { vm.removeProtectedPackage(pkg) },
                            onFail = { msg -> if (msg.isNotBlank()) vm.noteError(msg) },
                        )
                    },
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
                    onRefreshModels = vm::loadModelCatalog,
                    onSelectGateway = vm::selectGateway,
                    onAddGateway = vm::addSavedGateway,
                    onCheckUpdate = vm::checkUpdates,
                    onApplyUpdate = vm::applyUpdate,
                    onVoiceClick = {
                        val action = {
                            vm.setVoiceListening(true)
                            voiceManager.startListening(
                                onPartial = { vm.onDraftChange(it) },
                                onResult = { vm.onVoiceTranscript(it) },
                                onError = { vm.setVoiceListening(false) },
                                onStateChange = { vm.setVoiceListening(it) },
                            )
                        }
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            action()
                        } else {
                            pendingAudioAction = action
                            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onToggleVoiceStream = {
                        val action = {
                            vm.toggleVoiceStream()
                        }
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            action()
                        } else {
                            pendingAudioAction = action
                            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onToggleAttach = vm::toggleAttach,
                    onPickPhoto = {
                        pickerOutstanding = true
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickCamera = {
                        pickerOutstanding = true
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            launchCamera()
                        } else {
                            cameraPerm.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onPickVideo = {
                        pickerOutstanding = true
                        videoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                    onPickFile = {
                        pickerOutstanding = true
                        filePicker.launch(arrayOf("*/*"))
                    },
                    onRemoveAttachment = vm::removeAttachment,
                    onOpenMedia = { openMedia(it) },
                    onFetchMedia = vm::fetchMedia,
                )
                if (state.appLocked) {
                    BiometricLockOverlay(
                        onUnlock = {
                            gate.authenticate(
                                title = "Unlock Hermes",
                                onSuccess = vm::unlock,
                                onFail = { msg -> if (msg.isNotBlank()) vm.noteError(msg) },
                            )
                        },
                    )
                }
                }
            }
        }
    }

    private fun dismissKeyguardIfNeeded() {
        val km = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km?.isKeyguardLocked == true) {
            km.requestDismissKeyguard(this, null)
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

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun openMedia(block: ChatBlock) {
        val url = block.url
        if (url.isBlank()) return
        val intent = when {
            url.startsWith("http") -> Intent(Intent.ACTION_VIEW, Uri.parse(url))
            java.io.File(url).isFile -> Intent(Intent.ACTION_VIEW).apply {
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.files",
                    java.io.File(url),
                )
                setDataAndType(uri, block.mime.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            else -> return
        }
        runCatching { startActivity(intent) }
    }

    companion object {
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_AUTOCONNECT = "autoconnect"
        const val EXTRA_NONCE = "launch_nonce"
    }
}

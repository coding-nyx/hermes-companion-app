package app.hermes.desk

import android.Manifest
import android.app.KeyguardManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.domain.PrivilegePolicy
import app.hermes.companion.domain.WakePolicy
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.desk.feature.*
import app.hermes.desk.vision.DeskCameraController
import androidx.compose.ui.unit.dp

class DeskActivity : FragmentActivity() {
    private lateinit var vm: DeskViewModel
    private var charging by mutableStateOf(false)
    private var foregroundVisible by mutableStateOf(false)
    private var pendingMicrophone = false
    private var authentication: BiometricPrompt? = null
    private var voiceConsent by mutableStateOf(false)
    private var automaticVoice by mutableStateOf(true)
    private var connections by mutableStateOf(false)
    private var voiceSetup by mutableStateOf(false)
    private var presets by mutableStateOf(false)
    private lateinit var vision: DeskCameraController
    private var frontCamera by mutableStateOf(false)
    private var pendingCamera: Pair<DeskBinding, Boolean>? = null
    private var cameraBinding: DeskBinding? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingCamera; pendingCamera = null
        if (granted && pending != null && vm.state.value.binding == pending.first && foregroundVisible) startCamera(pending.first, pending.second)
        else if (!granted) vm.coordinator.error("Camera permission was declined; nothing is shared")
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = pendingMicrophone; pendingMicrophone = false
        if (granted && requested && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startVoice()
        else if (!granted) vm.coordinator.error("Microphone permission was declined. Text chat is still available.")
    }
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            charging = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = ViewModelProvider(this)[DeskViewModel::class.java]
        vision = DeskCameraController(this, this, onFrame = { binding, jpeg, once ->
            if (vm.state.value.binding == binding && foregroundVisible) {
                vm.visionSharing(true); vm.visionFrame(jpeg)
                if (once) { vm.send("Describe what you can see in this camera view."); vision.stop(); cameraBinding = null }
            } else jpeg.fill(0)
        }, onStopped = { vm.visionSharing(false) }, onError = { vm.coordinator.error(it) },
            onStarted = { if (cameraBinding == vm.state.value.binding && foregroundVisible) vm.visionSharing(true) else vision.stop() })
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.localActions.collect { command ->
                    if (!foregroundVisible || command.binding != vm.state.value.binding || getSystemService(KeyguardManager::class.java).isDeviceLocked) return@collect
                    vm.stopVoice(); vision.stop(); cameraBinding = null
                    try {
                        val intent = when (command.action) {
                            DeskPresets.Action.OPEN_BROWSER -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
                            DeskPresets.Action.OPEN_SETTINGS -> Intent(android.provider.Settings.ACTION_SETTINGS)
                        }
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) { vm.coordinator.error("No app is available for this preset on the tablet") }
                }
            }
            LaunchedEffect(state.binding, state.connected, state.loading, foregroundVisible) {
                if (cameraBinding != null && cameraBinding != state.binding) { vision.stop(); cameraBinding = null }
                if (!vm.automaticEntryHandled && foregroundVisible && state.connected && !state.loading && state.binding != null) {
                    vm.automaticEntryHandled = true
                    if (vm.shouldStartVoiceOnEntry) requestVoice()
                }
            }
            DisposableEffect(state.keepAwake, state.connected, charging, foregroundVisible) {
                if (state.keepAwake && state.connected && charging && foregroundVisible) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            CompanionTheme {
                DeskScreen(state, DeskActions(
                    connect = vm::connect, profile = vm.coordinator::openProfile, thread = vm.coordinator::openSession,
                    newThread = vm.coordinator::newThread, draft = vm.coordinator::draft,
                    send = { vm.stopVoice(); vm.send() }, talk = ::requestVoice, endVoice = { vm.endVoice(); vision.stop(); cameraBinding = null },
                    interrupt = vm.coordinator::interrupt, decision = ::decision,
                    refresh = vm.coordinator::refreshHistory, dismissUncertain = vm.coordinator::dismissUncertain,
                    ambient = vm.coordinator::ambient, preferences = vm::preferences,
                    disconnect = { authentication?.cancelAuthentication(); vision.stop(); cameraBinding = null; vm.disconnect() }, openChat = ::openChat,
                    dismissError = { vm.coordinator.error(null) },
                    mute = vm::toggleMute, compact = vm.coordinator::compact, speechMode = vm::speechMode,
                    stopVision = { vision.stop(); cameraBinding = null },
                    acknowledgeCompaction = vm.coordinator::acknowledgeCompaction,
                    loadModelSettings = vm.coordinator::loadSettings, setSetting = { key, value, confirmed ->
                        if (key == "model") { vision.stop(); cameraBinding = null }
                        vm.coordinator.setSetting(key, value, confirmed)
                    },
                    connections = { connections = true }, voiceSetup = { voiceSetup = true }, presets = { presets = true },
                ), cameraContent = {
                    Text("The agent receives a recent frame with your messages while sharing. Camera imagery stays off the home screen.", color = app.hermes.companion.design.CompanionColor.TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!frontCamera, { vision.stop(); frontCamera = false }, label = { Text("Rear camera") })
                        FilterChip(frontCamera, { vision.stop(); frontCamera = true }, label = { Text("Front camera") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { requestCamera(false) }, enabled = state.binding != null && !state.loading) { Text("Share with agent") }
                        OutlinedButton(onClick = { requestCamera(true) }, enabled = state.canSend) { Text("Look at this") }
                    }
                    if (state.visionSharing) TextButton(onClick = { vision.stop(); cameraBinding = null }) { Text("Stop sharing") }
                })
                if (connections) DeskConnections(vm, state, { connections = false }, { url ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                })
                if (voiceSetup) DeskVoiceSetup(vm, state, this, { voiceSetup = false }, ::requestVoice)
                if (presets) AlertDialog(onDismissRequest = { presets = false }, title = { Text("Device presets") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("These presets act on this tablet. Say the phrase during an active voice conversation, or tap a button.")
                        OutlinedButton(onClick = { presets = false; vm.preset(DeskPresets.Action.OPEN_BROWSER) }) { Text("Open browser") }
                        OutlinedButton(onClick = { presets = false; vm.preset(DeskPresets.Action.OPEN_SETTINGS) }) { Text("Open tablet settings") }
                        Text("Reading the browser, controlling other apps, and targeting your phone or computer are planned next. Opening an app ends voice and camera sharing on this screen.")
                    }
                }, confirmButton = { TextButton({ presets = false }) { Text("Done") } })
                if (voiceConsent) AlertDialog(onDismissRequest = { voiceConsent = false }, title = { Text("Your desk voice") },
                    text = { Column {
                        Text("While Desk is open, microphone audio goes to the selected speech service. Hermes uses your host's configured transcription and voice. Camera sharing is separate.")
                        Row { Checkbox(automaticVoice, { automaticVoice = it }); Text("Start voice when I open Desk") }
                    } },
                    confirmButton = { TextButton(onClick = { vm.rememberVoiceConsent(automaticVoice); voiceConsent = false; requestVoice() }) { Text("Start voice") } },
                    dismissButton = { TextButton(onClick = { voiceConsent = false }) { Text("Later") } })
            }
        }
    }
    private fun requestVoice() {
        if (!vm.voiceConsentKnown) { voiceConsent = true; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
        else { pendingMicrophone = true; microphone.launch(Manifest.permission.RECORD_AUDIO) }
    }
    private fun requestCamera(once: Boolean) {
        val binding = vm.state.value.binding ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera(binding, once)
        else { pendingCamera = binding to once; cameraPermission.launch(Manifest.permission.CAMERA) }
    }
    private fun startCamera(binding: DeskBinding, once: Boolean) {
        if (!foregroundVisible || getSystemService(KeyguardManager::class.java).isDeviceLocked || binding != vm.state.value.binding) return
        cameraBinding = binding
        vision.start(binding, frontCamera, once)
    }
    private fun startVoice() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        vm.startVoice()
    }
    private fun decision(prompt: ApprovalPrompt, choice: String) {
        val binding = vm.state.value.binding ?: return
        if (!PrivilegePolicy.requiresPresence(prompt.kind, prompt.command, choice)) {
            vm.coordinator.respond(binding, prompt.requestId, choice); return
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            vm.coordinator.error("Set a device PIN or biometric before approving this action"); return
        }
        if (authentication != null) return
        vm.stopVoice()
        authentication = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authentication = null
                vm.coordinator.respond(binding, prompt.requestId, choice, presenceVerified = true)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { authentication = null }
        }).also { it.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Confirm Hermes action")
            .setSubtitle("Approve the pending request on this device").setAllowedAuthenticators(authenticators).build()) }
    }
    private fun openChat() {
        val binding = vm.state.value.binding ?: return
        vm.stopVoice()
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WakePolicy.deepLink(binding.session, binding.profile, binding.origin))).setPackage("app.hermes.companion")) }
        catch (_: ActivityNotFoundException) { vm.coordinator.error("Install Hermes Companion to open this conversation there") }
    }
    override fun onStart() { super.onStart(); foregroundVisible = true }
    override fun onStop() {
        foregroundVisible = false; pendingMicrophone = false
        pendingCamera = null; vision.stop(); cameraBinding = null
        if (!isChangingConfigurations) vm.stopVoice()
        authentication?.cancelAuthentication(); authentication = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() { vision.close(); unregisterReceiver(battery); super.onDestroy() }
}

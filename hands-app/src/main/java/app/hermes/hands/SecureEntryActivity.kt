package app.hermes.hands

import android.graphics.drawable.ColorDrawable
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewStructure
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityRecord
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import app.hermes.companion.design.ActionButton
import app.hermes.companion.design.ActionKind
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.PreferenceToggle
import app.hermes.hands.ui.*

/** Phone-local private input. Values never enter Compose state, saved state, intents or logs. */
class SecureEntryActivity : ComponentActivity() {
    private var privateInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setHideOverlayWindows(true)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        window.setBackgroundDrawable(ColorDrawable(DarkHandsColors.background.toArgb()))
        enableEdgeToEdge()
        val controller = (application as HandsApplication).runtime.secureEntry
        onBackPressedDispatcher.addCallback(this) {
            val state = controller.state.value
            if (state.status == "pending") state.requestId?.let(controller::cancel)
            privateInput?.text?.clear()
            finish()
        }
        setContent {
            val state by controller.state.collectAsState()
            HandsTheme {
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                SecureEntryScreen(state.requestId, state.status, state.packageName, state.label, state.privacyActive,
                    onSubmit = { id, value -> controller.submit(id, value).also { accepted -> if (accepted) finish() } },
                    onCancel = controller::cancel,
                    onRelease = { if (controller.releaseLocally()) finish() },
                    onLeave = { finish() }, onInputReady = { privateInput = it })
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        if (event.flags and obscured != 0) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onStop() {
        privateInput?.text?.clear()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        privateInput?.text?.clear()
        super.onSaveInstanceState(outState)
    }
}

private class PrivateInputHandle {
    var view: EditText? = null
    fun clear() { view?.text?.clear() }
}

/** Defense in depth for Android assist, accessibility events and hierarchy state. */
internal class PrivateValueEditText(context: Context) : EditText(context) {
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = null
        info.contentDescription = "Private value"
        info.hintText = "Private value"
        info.isPassword = true
    }
    override fun sendAccessibilityEventUnchecked(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        sanitizeAccessibilityRecord(event)
        for (index in 0 until event.recordCount) sanitizeAccessibilityRecord(event.getRecord(index))
        super.sendAccessibilityEventUnchecked(event)
    }
    private fun sanitizeAccessibilityRecord(record: AccessibilityRecord) {
        record.text.clear()
        record.beforeText = null
        record.contentDescription = "Private value"
        record.fromIndex = -1
        record.toIndex = -1
        record.addedCount = 0
        record.removedCount = 0
        record.itemCount = 0
    }
    // EditText's parent implementation persists the private value in a Parcelable.
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onSaveInstanceState(): Parcelable? = null
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onRestoreInstanceState(state: Parcelable?) = Unit
    override fun onProvideStructure(structure: ViewStructure) {
        super.onProvideStructure(structure)
        structure.setText("")
    }
    override fun onProvideAutofillStructure(structure: ViewStructure, flags: Int) {
        super.onProvideAutofillStructure(structure, flags)
        structure.setText("")
    }
}

/** Only non-secret request metadata is accepted as state. The value lives in the native view. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SecureEntryScreen(
    requestId: String?,
    status: String,
    packageName: String,
    label: String,
    privacyActive: Boolean,
    onSubmit: (String, String) -> Boolean,
    onCancel: (String) -> Unit,
    onRelease: () -> Unit,
    onLeave: () -> Unit,
    onInputReady: (EditText?) -> Unit = {},
) {
    val context = LocalContext.current
    val field = remember { PrivateInputHandle() }
    var inputError by remember(requestId, status) { mutableStateOf<String?>(null) }
    var acknowledged by remember(requestId, status) { mutableStateOf(false) }
    val pending = privacyActive && status == "pending" && requestId != null
    DisposableEffect(requestId, status) {
        onDispose { field.clear(); field.view = null; onInputReady(null) }
    }
    fun hideKeyboard() {
        val input = field.view ?: return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }
    fun submit() {
        val id = requestId ?: return
        val value = field.view?.text?.toString().orEmpty()
        if (value.isEmpty()) {
            inputError = "Enter a value first."
            return
        }
        field.clear()
        hideKeyboard()
        if (!onSubmit(id, value)) inputError = "The field could not be filled. Remote access remains paused."
    }
    Column(Modifier.fillMaxSize().background(HandsTheme.colors.background).systemBarsPadding().imePadding()
        .testTag("privateEntry.surface")) {
        PageToolbar("Private entry", if (privacyActive) "REMOTE ACCESS PAUSED" else "PHONE ONLY")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CompanionSpace.Lg),
            verticalArrangement = Arrangement.spacedBy(CompanionSpace.Lg)) {
            if (pending) {
                Text("Fill the selected field", style = HandsType.Title)
                GroupSurface(padded = true) {
                    Text("Target app", style = HandsType.Caption)
                    Text(packageName.take(255), style = HandsType.Mono, modifier = Modifier.testTag("privateEntry.target"))
                    if (label.isNotBlank()) {
                        Text("Agent request", style = HandsType.Caption, modifier = Modifier.padding(top = CompanionSpace.Sm))
                        // Plain text, never interpreted as markup or instructions.
                        Text(label.filterNot { it.isISOControl() }.take(160), style = HandsType.Body,
                            modifier = Modifier.testTag("privateEntry.label"))
                    }
                }
                Text("Enter the value on this phone. Only the fill status is returned to the agent.", style = HandsType.Body)
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                key(requestId) {
                    AndroidView(factory = { ctx ->
                        PrivateValueEditText(ctx).apply {
                            isSaveEnabled = false
                            isSaveFromParentEnabled = false
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                            filterTouchesWhenObscured = true
                            setSingleLine(true)
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            transformationMethod = PasswordTransformationMethod.getInstance()
                            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
                            hint = "Private value"
                            textSize = 16f
                            background = null
                            val inset = (12 * resources.displayMetrics.density).toInt()
                            setPadding(inset, inset, inset, inset)
                            setTextColor(0xFFF5F5F7.toInt())
                            setHintTextColor(0xFFA1A1A6.toInt())
                            setOnEditorActionListener { _, action, _ ->
                                if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
                            }
                            field.view = this
                            onInputReady(this)
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .background(Color(0x1FFFFFFF), shape)
                        .border(1.dp, Color(0x24FFFFFF), shape).testTag("privateEntry.input"))
                }
                inputError?.let { Text(it, style = HandsType.Caption, color = HandsTheme.colors.danger,
                    modifier = Modifier.testTag("privateEntry.error")) }
                Text("Remote access stays paused after filling. Hide the value before ending private entry.",
                    style = HandsType.Caption)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm), verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
                    ActionButton("FILL FIELD", ::submit, kind = ActionKind.PRIMARY, modifier = Modifier.testTag("privateEntry.fill"))
                    ActionButton("CANCEL", { field.clear(); hideKeyboard(); requestId?.let(onCancel) },
                        modifier = Modifier.testTag("privateEntry.cancel"))
                }
            } else if (privacyActive) {
                Text(when (status) {
                    "filled" -> "Field filled privately"
                    "cancelled", "canceled" -> "Entry cancelled"
                    "failed" -> "Field was not filled"
                    "expired" -> "Private entry expired"
                    "recovered" -> "Private entry needs your review"
                    else -> "Remote access is paused"
                }, style = HandsType.Title, modifier = Modifier.testTag("privateEntry.status"))
                GroupSurface(padded = true) {
                    Text("Use Home or Recents to finish in the target app. Hide sensitive values before returning here.", style = HandsType.Body)
                    Text("Phone tools remain blocked until you end private entry. Hands will stay disarmed afterward.",
                        style = HandsType.Caption, modifier = Modifier.padding(top = CompanionSpace.Sm))
                }
                PreferenceToggle("Sensitive values are no longer visible", acknowledged, { acknowledged = !acknowledged },
                    summary = "Check the target app before allowing remote access again.",
                    modifier = Modifier.testTag("privateEntry.acknowledge"))
                ActionButton("END PRIVATE ENTRY", onRelease, enabled = acknowledged,
                    kind = ActionKind.PRIMARY, modifier = Modifier.testTag("privateEntry.end"))
                ActionButton("LEAVE ACCESS PAUSED", onLeave, modifier = Modifier.testTag("privateEntry.leave"))
            } else {
                Text("No private entry is active", style = HandsType.Title)
                Text("An agent can request a private field fill while Hands is armed. You enter the value here on this phone.", style = HandsType.Body)
                ActionButton("CLOSE", onLeave, modifier = Modifier.testTag("privateEntry.close"))
            }
        }
    }
}

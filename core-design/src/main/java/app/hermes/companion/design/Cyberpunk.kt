package app.hermes.companion.design

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Decorative movement is opt-in. Android's global remove-animation setting always wins. */
object CompanionAppearance {
    private const val FILE = "companion_appearance"
    private const val MOTION = "decorative_motion"
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun motionEnabled(context: Context) = preferences(context).getBoolean(MOTION, false)
    fun systemMotionEnabled(context: Context) = ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    fun setMotionEnabled(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean(MOTION, enabled).apply()
}

val LocalCompanionMotion = staticCompositionLocalOf { false }

@Composable
internal fun rememberCompanionMotion(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(CompanionAppearance.motionEnabled(context) && CompanionAppearance.systemMotionEnabled(context)) }
    DisposableEffect(context) {
        fun refresh() { enabled = CompanionAppearance.motionEnabled(context) && CompanionAppearance.systemMotionEnabled(context) }
        val preferences = CompanionAppearance.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return enabled
}

/** A chromatic terminal signature, never applied to reading text or editable content.
 * Its optional one-shot displacement settles in 160 ms, without flashes or repeated loops. */
@Composable
fun SignalSignature(modifier: Modifier = Modifier, label: String = "HERMES / COMPANION") {
    val movement = LocalCompanionMotion.current
    val phase = remember { Animatable(0f) }
    LaunchedEffect(movement) {
        phase.snapTo(if (movement) 1f else 0f)
        if (movement) phase.animateTo(0f, tween(160))
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val signal = CompanionColor.Signal
        val violet = CompanionColor.Accent
        Canvas(Modifier.size(20.dp, 16.dp).clearAndSetSemantics { }) {
            val offset = phase.value * 2.dp.toPx()
            drawLine(violet, Offset(0f, size.height * .25f), Offset(size.width * .7f + offset, size.height * .25f), 2.dp.toPx())
            drawLine(signal, Offset(size.width * .3f - offset, size.height * .55f), Offset(size.width, size.height * .55f), 2.dp.toPx())
            drawLine(signal.copy(alpha = .45f), Offset(0f, size.height * .85f), Offset(size.width * .5f, size.height * .85f), 1.dp.toPx())
        }
        Text(label, style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Explicit connection/context control with a full touch target and an accessible value. */
@Composable
fun ContextControl(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, accent: Color = CompanionColor.Signal) {
    val shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
    Column(modifier.heightIn(min = CompanionSpace.Touch).clip(shape)
        .background(CompanionColor.VoidElevated).border(1.dp, CompanionColor.LineStrong, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "$label: $value. Change $label" }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = CompanionType.MonoSmall.copy(color = accent))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = CompanionType.Label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾", style = CompanionType.Label, color = accent, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

/** Status always has a textual value; the colored edge is supplemental. */
@Composable
fun ProtocolStatus(label: String, value: String, modifier: Modifier = Modifier, tone: Color = CompanionColor.Signal) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(3.dp, 26.dp).background(tone).clearAndSetSemantics { })
        Column(Modifier.weight(1f)) {
            Text(label, style = CompanionType.Caption)
            Text(value, style = CompanionType.Label, color = tone)
        }
    }
}

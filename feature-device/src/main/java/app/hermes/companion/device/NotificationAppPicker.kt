package app.hermes.companion.device

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import app.hermes.companion.domain.NotificationStreamPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class NotificationApp(val packageName: String, val label: String, val icon: Bitmap? = null)

/** Package visibility is scoped to launchable apps; no broad installed-package permission. */
internal fun notificationApps(context: Context, selected: Set<String>): List<NotificationApp> {
    val pm = context.packageManager
    val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.applicationInfo }
    val selectedApps = selected.mapNotNull { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
    val apps = (launchable + selectedApps).distinctBy { it.packageName }.filter {
        it.packageName != context.packageName && !NotificationStreamPolicy.isStreamSuppressed(it.packageName)
    }.map { info ->
        NotificationApp(info.packageName, runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
            runCatching { pm.getApplicationIcon(info).toBitmap(48, 48) }.getOrNull())
    }
    // Keep removed / unavailable apps visible so a saved selection is always revocable.
    return (apps + (selected - apps.map { it.packageName }.toSet()).map { NotificationApp(it, it) })
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

internal fun filterNotificationApps(apps: List<NotificationApp>, query: String): List<NotificationApp> =
    apps.filter { it.label.contains(query.trim(), ignoreCase = true) || it.packageName.contains(query.trim(), ignoreCase = true) }

@Composable
internal fun NotificationAppPicker(selected: Set<String>, onSelect: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<NotificationApp>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(context, selected) {
        apps = withContext(Dispatchers.IO) { notificationApps(context, selected) }
        loaded = true
    }
    NotificationAppGrid(apps, selected, onSelect, loaded)
}

@Composable
internal fun NotificationAppGrid(apps: List<NotificationApp>, selected: Set<String>, onSelect: (String, Boolean) -> Unit, loaded: Boolean = true) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${selected.size} ${if (selected.size == 1) "app" else "apps"} selected", style = CompanionType.RowTitle, modifier = Modifier.testTag("device.stream.apps.count"))
        Text("Only selected apps are observed. Protected apps and the built-in echo exclusions still take priority. Removing an app discards its pending notifications.", style = CompanionType.Caption)
        HairlineField(query, { query = it }, placeholder = "Search apps", keyboardType = androidx.compose.ui.text.input.KeyboardType.Text, modifier = Modifier.testTag("device.stream.apps.search"))
        val filtered = filterNotificationApps(apps, query)
        if (!loaded) Text("Loading apps…", style = CompanionType.Body)
        else if (filtered.isEmpty()) Text("No matching apps", style = CompanionType.Body)
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 300.dp && fontScale < 1.5f) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                filtered.chunked(columns).forEach { row ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { app ->
                            val chosen = app.packageName in selected
                            Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 112.dp)
                                .background(CompanionColor.VoidSurface)
                                .border(1.dp, if (chosen) CompanionColor.Signal else CompanionColor.Line)
                                .toggleable(chosen, role = Role.Checkbox, onValueChange = { onSelect(app.packageName, it) })
                                .padding(16.dp).testTag("device.stream.app.${app.packageName}"),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (app.icon != null) Image(app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp))
                                    else Box(Modifier.size(36.dp).background(CompanionColor.SignalSubtle), contentAlignment = Alignment.Center) {
                                        Text(app.label.take(1).uppercase(), style = CompanionType.RowTitle, color = CompanionColor.Signal)
                                    }
                                    Text(if (chosen) "Selected" else "Observe", style = CompanionType.Caption, color = if (chosen) CompanionColor.Signal else CompanionColor.TextDim)
                                }
                                Text(app.label, style = CompanionType.RowTitle)
                                Text(app.packageName, style = CompanionType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { contentDescription = app.packageName })
                            }
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

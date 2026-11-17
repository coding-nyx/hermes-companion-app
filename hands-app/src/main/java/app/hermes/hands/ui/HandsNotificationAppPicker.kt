package app.hermes.hands.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.device.NotificationApp
import app.hermes.companion.device.filterNotificationApps
import app.hermes.companion.device.notificationApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * iOS-styled frosted glass application picker for Hands notification sharing.
 */
@Composable
fun HandsNotificationAppPicker(
    selected: Set<String>,
    onSelect: (String, Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<NotificationApp>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(context, selected) {
        apps = withContext(Dispatchers.IO) { notificationApps(context, selected) }
        loaded = true
    }

    HandsNotificationAppGrid(
        apps = apps,
        selected = selected,
        onSelect = onSelect,
        onDone = onDone,
        loaded = loaded,
        modifier = modifier,
    )
}

@Composable
internal fun HandsNotificationAppGrid(
    apps: List<NotificationApp>,
    selected: Set<String>,
    onSelect: (String, Boolean) -> Unit,
    onDone: () -> Unit,
    loaded: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = HandsTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) { filterNotificationApps(apps, query) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Count & Header
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${selected.size} ${if (selected.size == 1) "app" else "apps"} selected",
                style = HandsType.Subtitle.copy(color = colors.textPrimary, fontWeight = FontWeight.Bold),
                modifier = Modifier.testTag("device.stream.apps.count"),
            )
            Text(
                text = "Only selected apps are observed. Protected apps and built-in echo exclusions still take priority. Removing an app discards its pending notifications.",
                style = HandsType.Caption.copy(color = colors.textSecondary),
            )
        }

        // iOS-style glass search input
        GlassSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = "Search apps",
            modifier = Modifier.testTag("device.stream.apps.search"),
        )

        // Loading or Empty states
        if (!loaded) {
            Text("Loading apps…", style = HandsType.Body.copy(color = colors.textSecondary))
        } else if (filtered.isEmpty()) {
            Text("No matching apps", style = HandsType.Body.copy(color = colors.textSecondary))
        }

        // App cards grid
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 340.dp && fontScale < 1.4f) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filtered.chunked(columns).forEach { row ->
                    Row(
                        modifier = Modifier.height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { app ->
                            val chosen = app.packageName in selected
                            HandsAppCard(
                                app = app,
                                chosen = chosen,
                                onToggle = { onSelect(app.packageName, it) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .testTag("device.stream.app.${app.packageName}"),
                            )
                        }
                        if (row.size < columns) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Bottom Done button for clear confirmation
        GlassButton(
            label = "Done (${selected.size} selected)",
            onClick = onDone,
            kind = GlassButtonKind.PRIMARY,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("hands.notifications.apps.done"),
        )
    }
}

@Composable
private fun HandsAppCard(
    app: NotificationApp,
    chosen: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HandsTheme.colors
    val cardShape = RoundedCornerShape(16.dp)

    val borderStroke = if (chosen) {
        BorderStroke(1.5.dp, colors.accent)
    } else {
        BorderStroke(1.dp, colors.strokeGlass)
    }

    val cardBg = if (chosen) {
        if (colors.isDark) Color(0x280A84FF) else Color(0x18007AFF)
    } else {
        colors.surfaceGlass
    }

    Column(
        modifier = modifier
            .clip(cardShape)
            .background(cardBg)
            .border(borderStroke, cardShape)
            .toggleable(
                value = chosen,
                role = Role.Checkbox,
                onValueChange = onToggle,
            )
            .padding(14.dp)
            .defaultMinSize(minHeight = 90.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = app.icon
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        style = HandsType.Subtitle.copy(color = colors.accent, fontWeight = FontWeight.Bold),
                    )
                }
            }

            GlassStatusPill(
                label = "",
                statusText = if (chosen) "Selected" else "Observe",
                active = chosen,
            )
        }

        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = app.label,
                style = HandsType.Body.copy(
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = HandsType.Caption.copy(color = colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = app.packageName },
            )
        }
    }
}

/**
 * iOS-styled frosted glass search field.
 */
@Composable
fun GlassSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = HandsTheme.colors
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        textStyle = HandsType.Body.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(colors.surfaceGlass)
            .border(
                BorderStroke(if (focused) 1.5.dp else 1.dp, if (focused) colors.accent else colors.strokeGlass),
                shape,
            )
            .semantics { contentDescription = placeholder },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "🔍",
                    style = HandsType.Caption.copy(fontSize = 14.sp),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = HandsType.Body.copy(color = colors.textTertiary),
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceElevated)
                            .clickable { onQueryChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "✕",
                            style = HandsType.Caption.copy(
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
            }
        },
    )
}

package app.hermes.hands.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.design.ActionKind

enum class GlassButtonKind {
    PRIMARY,
    GHOST,
    DANGER,
    SUCCESS,
}

/**
 * An iOS-styled frosted glass card surface with smooth rounded corners,
 * rich acrylic base, specular top sheen, and chamfered glass lighting.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = HandsShapes.Medium,
    elevationLevel: Int = 1,
    borderStroke: BorderStroke? = null,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HandsTheme.colors
    val baseColor = if (elevationLevel > 1) colors.surfaceElevated else colors.surfaceGlass
    val sheen = if (colors.isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0x1AFFFFFF),
                Color(0x04FFFFFF),
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0x66FFFFFF),
                Color(0x0DFFFFFF),
            )
        )
    }
    val defaultStroke = BorderStroke(
        1.dp,
        if (colors.isDark) {
            Brush.verticalGradient(
                listOf(
                    colors.strokeStrong.copy(alpha = 0.5f),
                    colors.strokeGlass.copy(alpha = 0.2f),
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    colors.strokeStrong.copy(alpha = 0.35f),
                    colors.strokeGlass.copy(alpha = 0.12f),
                )
            )
        }
    )
    val stroke = borderStroke ?: defaultStroke

    Column(
        modifier = modifier
            .clip(shape)
            .background(baseColor)
            .background(sheen)
            .border(stroke, shape)
            .then(if (padded) Modifier.padding(16.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * An iOS-styled frosted glass modal card for dialogs and popovers.
 * Provides high-opacity acrylic backing, specular lighting, and rounded corners (24dp)
 * so that modal content is crisp and completely legible without background interference.
 */
@Composable
fun GlassModalCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HandsTheme.colors
    val baseColor = if (colors.isDark) Color(0xF7181C28) else Color(0xFAFFFFFF)
    val sheen = if (colors.isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0x2EFFFFFF),
                Color(0x08FFFFFF),
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0x80FFFFFF),
                Color(0x14FFFFFF),
            )
        )
    }
    val border = BorderStroke(
        1.dp,
        if (colors.isDark) {
            Brush.verticalGradient(
                listOf(
                    Color(0x66FFFFFF),
                    Color(0x26FFFFFF),
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color(0x33000000),
                    Color(0x14000000),
                )
            )
        }
    )

    Column(
        modifier = modifier
            .clip(shape)
            .background(baseColor)
            .background(sheen)
            .border(border, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/**
 * An inset well / sunken glass capsule for secondary information (codes, requester info, network addresses).
 */
@Composable
fun GlassInsetCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HandsTheme.colors
    val insetBg = if (colors.isDark) Color(0x40000000) else Color(0x0A000000)
    val border = BorderStroke(
        1.dp,
        if (colors.isDark) Color(0x2EFFFFFF) else Color(0x14000000)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(insetBg)
            .border(border, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * An iOS-styled action button with smooth squircle/pill geometry and a minimum 48dp touch target.
 */
@Composable
fun GlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: GlassButtonKind = GlassButtonKind.GHOST,
    enabled: Boolean = true,
    busy: Boolean = false,
    shape: Shape = RoundedCornerShape(14.dp),
) {
    val colors = HandsTheme.colors
    val active = enabled && !busy

    val bg = when (kind) {
        GlassButtonKind.PRIMARY -> if (active) colors.accent else colors.accent.copy(alpha = 0.4f)
        GlassButtonKind.DANGER -> if (active) colors.danger else colors.danger.copy(alpha = 0.4f)
        GlassButtonKind.SUCCESS -> if (active) colors.success else colors.success.copy(alpha = 0.4f)
        GlassButtonKind.GHOST -> if (active) colors.surfaceElevated else colors.surfaceGlass.copy(alpha = 0.2f)
    }

    val fg = when (kind) {
        GlassButtonKind.PRIMARY, GlassButtonKind.DANGER, GlassButtonKind.SUCCESS -> Color.White
        GlassButtonKind.GHOST -> if (active) colors.textPrimary else colors.textTertiary
    }

    val border = when (kind) {
        GlassButtonKind.PRIMARY, GlassButtonKind.DANGER, GlassButtonKind.SUCCESS -> null
        GlassButtonKind.GHOST -> BorderStroke(1.dp, if (active) colors.strokeGlass else colors.strokeGlass.copy(alpha = 0.3f))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = if (busy) "…" else label,
            style = HandsType.Label.copy(color = fg),
            maxLines = 2,
            softWrap = true,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Drop-in ActionButton replacement for Hands with iOS frosted glass styling.
 */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ActionKind = ActionKind.GHOST,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = "…",
) {
    val glassKind = when (kind) {
        ActionKind.PRIMARY -> GlassButtonKind.PRIMARY
        ActionKind.DANGER -> GlassButtonKind.DANGER
        ActionKind.GHOST -> GlassButtonKind.GHOST
    }
    GlassButton(
        label = if (busy) busyLabel else label,
        onClick = onClick,
        modifier = modifier,
        kind = glassKind,
        enabled = enabled,
        busy = busy,
    )
}

/**
 * Drop-in GroupSurface replacement for Hands with iOS frosted glass styling.
 */
@Composable
fun GroupSurface(
    modifier: Modifier = Modifier,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier,
        padded = padded,
        content = content,
    )
}

/**
 * iOS-styled Segmented Control pill tab bar.
 */
@Composable
fun GlassSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HandsTheme.colors
    val containerShape = RoundedCornerShape(16.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(containerShape)
            .background(colors.surfaceGlass)
            .border(BorderStroke(1.dp, colors.strokeGlass), containerShape)
            .padding(4.dp),
    ) {
        val columns = if (maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f) 2 else items.size
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.withIndex().chunked(columns.coerceAtLeast(1)).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { item ->
                        val isSelected = selectedIndex == item.index
                        val itemShape = RoundedCornerShape(12.dp)
                        val itemBg = if (isSelected) colors.surfaceElevated else Color.Transparent
                        val itemBorder = if (isSelected) BorderStroke(1.dp, colors.strokeStrong) else null
                        val textColor = if (isSelected) colors.textPrimary else colors.textSecondary
                        val textWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(1f).fillMaxHeight().defaultMinSize(minHeight = 48.dp)
                                .clip(itemShape).background(itemBg)
                                .then(if (itemBorder != null) Modifier.border(itemBorder, itemShape) else Modifier)
                                .clickable(role = Role.Tab, onClick = { onSelect(item.index) })
                                .testTag("hands.tab.${item.value.lowercase()}")
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(item.value, style = HandsType.Subtitle.copy(color = textColor,
                                fontWeight = textWeight, fontSize = 13.sp), maxLines = 2, softWrap = true)
                        }
                    }
                }
            }
        }
    }
}

/**
 * iOS Status Pill with a glowing circular status indicator dot.
 */
@Composable
fun GlassStatusPill(
    label: String,
    statusText: String,
    active: Boolean = false,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = HandsTheme.colors
    val dotColor = when {
        danger -> colors.danger
        active -> colors.success
        else -> colors.textTertiary
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(colors.surfaceElevated)
            .border(BorderStroke(1.dp, colors.strokeGlass), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = if (label.isNotBlank()) "$label · $statusText" else statusText,
            style = HandsType.Caption.copy(
                color = colors.textPrimary,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Drop-in ProtocolStatus replacement for Hands with iOS frosted glass styling.
 */
@Composable
fun ProtocolStatus(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val active = value.contains("Online", ignoreCase = true) ||
                 value.contains("Allowed", ignoreCase = true) ||
                 value.contains("On", ignoreCase = true)
    val danger = value.contains("Off", ignoreCase = true) ||
                 value.contains("Stop", ignoreCase = true)

    if (LocalDensity.current.fontScale >= 1.4f) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = HandsType.Body.copy(color = HandsTheme.colors.textSecondary))
            GlassStatusPill(label = "", statusText = value, active = active, danger = danger)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f).padding(end = 8.dp),
                style = HandsType.Body.copy(color = HandsTheme.colors.textSecondary))
            GlassStatusPill(label = "", statusText = value, active = active, danger = danger)
        }
    }
}

/**
 * Clean navigation toolbar for Hands.
 */
@Composable
fun PageToolbar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = HandsTheme.colors
    val isOnline = subtitle?.contains("ONLINE", ignoreCase = true) == true

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = HandsType.LargeTitle.copy(color = colors.textPrimary),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = HandsType.Caption.copy(color = colors.textSecondary),
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            GlassStatusPill(
                label = "",
                statusText = if (isOnline) "ONLINE" else "OFF",
                active = isOnline,
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    val colors = HandsTheme.colors
    Text(
        text = title,
        style = HandsType.Title.copy(
            color = colors.textPrimary,
        ),
        modifier = modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier) {
    val colors = HandsTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = HandsType.Body.copy(color = colors.textSecondary))
        Text(value, style = HandsType.Mono.copy(color = colors.textPrimary))
    }
}

@Composable
fun SheetHeading(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = HandsType.Title.copy(color = HandsTheme.colors.textPrimary),
            modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
        )
        GlassButton(
            label = "Done",
            onClick = onClose,
            kind = GlassButtonKind.GHOST,
            shape = RoundedCornerShape(100.dp),
            modifier = Modifier.wrapContentWidth().defaultMinSize(minHeight = 40.dp),
        )
    }
}

/**
 * iOS-styled grouped section container with an optional section header.
 */
@Composable
fun GlassGroupedSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        GlassCard(padded = true, content = content)
    }
}

/**
 * iOS-styled preference row with interactive tap, title, optional summary, and trailing selected status pill.
 */
@Composable
fun GlassPreferenceRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = HandsTheme.colors
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = HandsType.Body.copy(
                    color = colors.textPrimary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = HandsType.Caption.copy(color = colors.textSecondary),
                )
            }
        }
        if (selected) {
            GlassStatusPill(label = "", statusText = value ?: "Selected", active = true)
        } else if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = HandsType.Subtitle.copy(color = colors.accent, fontWeight = FontWeight.Medium),
            )
        }
    }
}

/**
 * iOS-styled toggle row with title, optional summary, and an iOS frosted switch.
 */
@Composable
fun GlassPreferenceToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = HandsTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = HandsType.Body.copy(
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = HandsType.Caption.copy(color = colors.textSecondary),
                )
            }
        }
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * iOS-styled frosted switch component.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HandsTheme.colors
    val trackWidth = 51.dp
    val trackHeight = 31.dp
    val thumbSize = 27.dp
    val thumbPadding = 2.dp

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - thumbPadding else thumbPadding,
        label = "thumbOffset",
    )

    val trackBg = if (checked) colors.accent else (if (colors.isDark) Color(0x33FFFFFF) else Color(0x1F000000))
    val trackBorder = if (checked) null else BorderStroke(1.dp, colors.strokeGlass)

    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .clip(RoundedCornerShape(100.dp))
            .background(trackBg)
            .then(if (trackBorder != null) Modifier.border(trackBorder, RoundedCornerShape(100.dp)) else Modifier)
            .clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) }),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * iOS-styled status strip using an inset glass card.
 */
@Composable
fun GlassStatusStrip(
    text: String,
    tone: Color = HandsTheme.colors.accent,
    modifier: Modifier = Modifier,
) {
    GlassInsetCard(modifier = modifier) {
        Text(text, style = HandsType.Body.copy(color = tone))
    }
}


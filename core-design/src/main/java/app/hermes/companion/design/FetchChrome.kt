package app.hermes.companion.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Blinking 7×13 signal block — same cursor the transcript uses while streaming. */
@Composable
fun SignalCursor(modifier: Modifier = Modifier, active: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(CompanionMotion.BlinkMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor.alpha",
    )
    Box(
        modifier
            .width(7.dp)
            .height(13.dp)
            .background(CompanionColor.Signal.copy(alpha = if (active) blink else 1f)),
    )
}

/** Blinking signal dot — "something is live here" marker for rows and pills. */
@Composable
fun LiveDot(modifier: Modifier = Modifier, color: Color = CompanionColor.Signal, size: Dp = 6.dp) {
    val transition = rememberInfiniteTransition(label = "live")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(CompanionMotion.BlinkMs * 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live.alpha",
    )
    Box(
        modifier
            .size(size)
            .background(color.copy(alpha = blink), CircleShape),
    )
}

/** Three-step ellipsis that walks `·  ` → `·· ` → `···` — waiting-on-agent cue. */
@Composable
fun WalkingEllipsis(color: Color = CompanionColor.Signal, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ellipsis")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(CompanionMotion.ScanMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ellipsis.step",
    )
    val n = (step.toInt() % 3) + 1
    Text(
        text = "···".take(n).padEnd(3, ' '),
        style = CompanionType.MonoSmall.copy(color = color),
        modifier = modifier,
    )
}

/** Hairline with a signal sliver that travels the void — in-flight fetch cue. */
@Composable
fun Scanline(modifier: Modifier = Modifier, active: Boolean = true) {
    if (!active) {
        Hairline(modifier)
        return
    }
    val transition = rememberInfiniteTransition(label = "scan")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(CompanionMotion.ScanMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scan.phase",
    )
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(CompanionSpace.Hairline)
            .background(CompanionColor.Line),
    ) {
        val sliver = 48.dp
        Box(
            Modifier
                .offset(x = (maxWidth + sliver) * phase - sliver)
                .width(sliver)
                .height(CompanionSpace.Hairline)
                .background(CompanionColor.Signal),
        )
    }
}

/** Compact in-list status: cursor + mono label + scanline. */
@Composable
fun FetchRow(
    label: String,
    modifier: Modifier = Modifier,
    scanning: Boolean = true,
    padded: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("fetch.row")
            .then(modifier),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (padded) CompanionSpace.Lg else 0.dp,
                vertical = CompanionSpace.Md,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalCursor(active = scanning)
            Spacer(Modifier.width(CompanionSpace.Sm))
            Text(
                text = label,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
            )
        }
        Scanline(active = scanning)
    }
}

/**
 * Centered fetch pane for empty / failed / first-load with no cache.
 * [scanning] lights the cursor and scanline (in-flight); off is idle/failed chrome.
 */
@Composable
fun FetchPane(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    scanning: Boolean = false,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(CompanionSpace.Xl)
            .testTag("fetch.pane")
            .then(modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SignalCursor(active = scanning)
        Spacer(Modifier.height(CompanionSpace.Md))
        Text(
            text = label,
            style = CompanionType.Mono.copy(
                color = when {
                    scanning -> CompanionColor.Signal
                    onRetry != null -> CompanionColor.Warn
                    else -> CompanionColor.TextMute
                },
            ),
        )
        Spacer(Modifier.height(CompanionSpace.Md))
        Scanline(Modifier.width(96.dp), active = scanning)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(CompanionSpace.Md))
            Text(text = hint, style = CompanionType.MonoSmall)
        }
        if (onRetry != null && !retryLabel.isNullOrBlank()) {
            Spacer(Modifier.height(CompanionSpace.Lg))
            Text(
                text = retryLabel,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                modifier = Modifier
                    .testTag("fetch.retry")
                    .clickable(onClick = onRetry)
                    .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm),
            )
        }
    }
}

/** Pulsing hairline bars — list-shaped ghost while the first page is in flight. */
@Composable
fun FetchSkeleton(
    modifier: Modifier = Modifier,
    lines: Int = 5,
    padded: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "skel")
    val pulse by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skel.pulse",
    )
    val fracs = listOf(0.82f, 0.61f, 0.9f, 0.44f, 0.73f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (padded) CompanionSpace.Lg else 0.dp,
                vertical = CompanionSpace.Md,
            )
            .testTag("fetch.skeleton")
            .then(modifier),
        verticalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
    ) {
        fracs.take(lines.coerceIn(1, fracs.size)).forEach { frac ->
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(11.dp)
                    .background(CompanionColor.LineStrong.copy(alpha = pulse)),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF07080A)
@Composable
private fun FetchChromePreview() {
    CompanionTheme {
        Column(Modifier.background(CompanionColor.Void)) {
            FetchRow(label = "LOADING THREADS")
            FetchSkeleton(lines = 3)
        }
    }
}

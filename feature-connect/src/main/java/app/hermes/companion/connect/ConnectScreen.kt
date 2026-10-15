package app.hermes.companion.connect

import app.hermes.companion.design.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.RobotMark
import app.hermes.companion.design.ActionButton
import app.hermes.companion.design.ActionKind
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HairlineField
import androidx.compose.ui.platform.LocalClipboardManager
import app.hermes.companion.domain.GatewayBook
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.domain.WakePolicy
import app.hermes.companion.model.GatewayChoice

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(
    origin: String,
    loading: Boolean,
    error: String?,
    onOriginChange: (String) -> Unit,
    onConnect: () -> Unit,
    onPairLink: ((String, String, String) -> Unit)? = null,
    authRequired: Boolean = false,
    username: String = "",
    password: String = "",
    onUsernameChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    gateways: List<GatewayChoice> = emptyList(),
    onSelectGateway: (GatewayChoice) -> Unit = {},
    onForgetGateway: (GatewayChoice) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val handleOriginInput: (String) -> Unit = { input ->
        val pairLink = WakePolicy.parsePairDeepLink(input)
        if (pairLink != null) {
            onOriginChange(pairLink.origin)
            onPairLink?.invoke(pairLink.origin, pairLink.code, pairLink.profile)
        } else {
            onOriginChange(input)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .statusBarsPadding()
            .displayCutoutPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("connect.loading"),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RobotMark(size = 56.dp, awake = true)
                Spacer(Modifier.height(CompanionSpace.Md))
                Text(text = "Connect to Hermes", style = CompanionType.Display)
                Spacer(Modifier.height(CompanionSpace.Md))
                Hairline(Modifier.width(96.dp))
                FetchPane(
                    label = "CONNECTING",
                    hint = origin.substringAfter("://").ifBlank { origin }.ifBlank { "// handshake" },
                    scanning = true,
                )
            }
            return@BoxWithConstraints
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(CompanionSpace.Xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        RobotMark(size = 56.dp, awake = false)
        Spacer(Modifier.height(CompanionSpace.Md))
        Text(text = "Connect to Hermes", style = CompanionType.Display)
        Spacer(Modifier.height(CompanionSpace.Xs))
        Text(text = "Hermes Companion", style = CompanionType.Caption)
        Spacer(Modifier.height(CompanionSpace.Md))
        Hairline(Modifier.width(96.dp))
        Spacer(Modifier.height(CompanionSpace.Xl))
        if (gateways.isNotEmpty()) {
            GatewayRail(
                origin = origin,
                gateways = gateways,
                onSelect = onSelectGateway,
                onForget = onForgetGateway,
            )
            Spacer(Modifier.height(CompanionSpace.Lg))
        }
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Dashboard address",
                style = CompanionType.Caption,
            )
            ActionText(
                text = "Paste pairing link",
                style = CompanionType.Caption.copy(color = CompanionColor.Signal),
                modifier = Modifier
                    .testTag("connect.paste_link")
                    .heightIn(min = 48.dp).clickable {
                        val clip = clipboard.getText()?.text?.trim().orEmpty()
                        val pairLink = WakePolicy.parsePairDeepLink(clip)
                        if (pairLink != null) {
                            onOriginChange(pairLink.origin)
                            onPairLink?.invoke(pairLink.origin, pairLink.code, pairLink.profile)
                        } else if (clip.startsWith("http://") || clip.startsWith("https://")) {
                            onOriginChange(clip)
                        }
                    },
            )
        }
        Spacer(Modifier.height(CompanionSpace.Sm))
        HairlineField(
            value = origin,
            onValueChange = handleOriginInput,
            onDone = onConnect,
            modifier = Modifier.testTag("connect.origin"),
        )
        OriginPolicy.hint(origin)?.let { hint ->
            Spacer(Modifier.height(CompanionSpace.Sm))
            Text(
                text = hint,
                style = CompanionType.Caption.copy(color = CompanionColor.Warn),
                modifier = Modifier
                    .align(Alignment.Start)
                    .testTag("connect.loopback"),
            )
        }
        if (authRequired) {
            Spacer(Modifier.height(CompanionSpace.Lg))
            Text(
                text = "Username",
                style = CompanionType.Caption,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(Modifier.height(CompanionSpace.Sm))
            HairlineField(
                value = username,
                onValueChange = onUsernameChange,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
                modifier = Modifier.testTag("connect.username"),
            )
            Spacer(Modifier.height(CompanionSpace.Lg))
            Text(
                text = "Password",
                style = CompanionType.Caption,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(Modifier.height(CompanionSpace.Sm))
            HairlineField(
                value = password,
                onValueChange = onPasswordChange,
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                onDone = onConnect,
                modifier = Modifier.testTag("connect.password"),
            )
        }
        Spacer(Modifier.height(CompanionSpace.Lg))
        ActionButton(
            label = if (authRequired) "Sign in" else "Connect",
            kind = ActionKind.PRIMARY,
            enabled = origin.isNotBlank(),
            onClick = onConnect,
            modifier = Modifier.testTag("connect.go"),
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(CompanionSpace.Lg))
            Text(
                text = error,
                style = CompanionType.Caption.copy(color = CompanionColor.Danger),
                modifier = Modifier.testTag("connect.error"),
            )
        }
        }
    }
}

@Composable
private fun GatewayRail(
    origin: String,
    gateways: List<GatewayChoice>,
    onSelect: (GatewayChoice) -> Unit,
    onForget: (GatewayChoice) -> Unit,
) {
    var pending by remember { mutableStateOf<GatewayChoice?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connect.gateways"),
    ) {
        Text(
            text = "Saved connections",
            style = CompanionType.Title,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        pending?.let { target ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = CompanionSpace.Sm)
                    .testTag("connect.forget"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
            ) {
                Text(
                    text = "FORGET  ${target.name}?",
                    style = CompanionType.Caption.copy(color = CompanionColor.Warn),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ActionText(
                    text = "FORGET",
                    style = CompanionType.Caption.copy(color = CompanionColor.Danger),
                    modifier = Modifier
                        .testTag("connect.forget.confirm")
                        .heightIn(min = 48.dp).clickable {
                            onForget(target)
                            pending = null
                        },
                )
                ActionText(
                    text = "CANCEL",
                    style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
                    modifier = Modifier
                        .testTag("connect.forget.cancel")
                        .heightIn(min = 48.dp).clickable { pending = null },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
        ) {
            gateways.forEach { gw ->
                GatewayChip(
                    choice = gw,
                    selected = origin.isNotBlank() && GatewayBook.key(origin) == GatewayBook.key(gw.origin),
                    onSelect = { onSelect(gw) },
                    onForget = { if (gw.forgettable) pending = gw },
                )
            }
        }
    }
}

@Composable
private fun GatewayChip(
    choice: GatewayChoice,
    selected: Boolean,
    onSelect: () -> Unit,
    onForget: () -> Unit,
) {
    val border = if (selected) CompanionColor.Signal else CompanionColor.LineStrong
    val healthColor = when (choice.health) {
        "up" -> CompanionColor.Signal
        "down" -> CompanionColor.Warn
        else -> CompanionColor.TextMute
    }
    Column(
        modifier = Modifier

            .pointerInput(choice.id) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onLongPress = { onForget() },
                )
            }
            .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm)
            .testTag("connect.gw.${choice.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(end = CompanionSpace.Sm)
                    .size(6.dp)
                    .background(healthColor),
            )
            Text(
                text = choice.name,
                style = CompanionType.Caption.copy(color = CompanionColor.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (choice.forgettable) {
                Spacer(Modifier.width(CompanionSpace.Sm))
                ActionText(
                    text = "DEL",
                    style = CompanionType.Caption.copy(color = CompanionColor.Danger),
                    modifier = Modifier
                        .testTag("connect.gw.${choice.id}.forget")
                        .heightIn(min = 48.dp).clickable(onClick = onForget),
                )
            }
        }
        Text(
            text = choice.host,
            style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val badges = buildList {
            if (choice.paired) add("PAIRED")
            if (choice.lastOk) add("LAST OK")
            if (choice.health == "down") add("down")
        }
        if (badges.isNotEmpty()) {
            Text(
                text = badges.joinToString(" · "),
                style = CompanionType.Caption.copy(
                    color = if (choice.health == "down") CompanionColor.Warn else CompanionColor.Signal,
                ),
                maxLines = 1,
            )
        }
    }
}

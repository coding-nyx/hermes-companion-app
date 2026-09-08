package app.hermes.companion.connect

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
import app.hermes.companion.domain.GatewayBook
import app.hermes.companion.domain.OriginPolicy
import app.hermes.companion.model.GatewayChoice

@Composable
fun ConnectScreen(
    origin: String,
    loading: Boolean,
    error: String?,
    onOriginChange: (String) -> Unit,
    onConnect: () -> Unit,
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
                Text(text = "HERMES", style = CompanionType.Display)
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
        Text(text = "HERMES", style = CompanionType.Display)
        Spacer(Modifier.height(CompanionSpace.Xs))
        Text(text = "companion", style = CompanionType.MonoSmall)
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
        Text(
            text = "origin",
            style = CompanionType.MonoSmall,
            modifier = Modifier.align(Alignment.Start),
        )
        Spacer(Modifier.height(CompanionSpace.Sm))
        HairlineField(
            value = origin,
            onValueChange = onOriginChange,
            onDone = onConnect,
            modifier = Modifier.testTag("connect.origin"),
        )
        OriginPolicy.hint(origin)?.let { hint ->
            Spacer(Modifier.height(CompanionSpace.Sm))
            Text(
                text = hint,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                modifier = Modifier
                    .align(Alignment.Start)
                    .testTag("connect.loopback"),
            )
        }
        if (authRequired) {
            Spacer(Modifier.height(CompanionSpace.Lg))
            Text(
                text = "username",
                style = CompanionType.MonoSmall,
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
                text = "password",
                style = CompanionType.MonoSmall,
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
            label = "CONNECT",
            kind = ActionKind.PRIMARY,
            enabled = origin.isNotBlank(),
            onClick = onConnect,
            modifier = Modifier.testTag("connect.go"),
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(CompanionSpace.Lg))
            Text(
                text = error,
                style = CompanionType.Mono.copy(color = CompanionColor.Danger),
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
            text = "gateways",
            style = CompanionType.MonoSmall,
            modifier = Modifier.padding(bottom = CompanionSpace.Sm),
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
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "FORGET",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger),
                    modifier = Modifier
                        .testTag("connect.forget.confirm")
                        .clickable {
                            onForget(target)
                            pending = null
                        },
                )
                Text(
                    text = "CANCEL",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                    modifier = Modifier
                        .testTag("connect.forget.cancel")
                        .clickable { pending = null },
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
            .border(CompanionSpace.Hairline, border)
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
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (choice.forgettable) {
                Spacer(Modifier.width(CompanionSpace.Sm))
                Text(
                    text = "DEL",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger),
                    modifier = Modifier
                        .testTag("connect.gw.${choice.id}.forget")
                        .clickable(onClick = onForget),
                )
            }
        }
        Text(
            text = choice.host,
            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
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
                style = CompanionType.MonoSmall.copy(
                    color = if (choice.health == "down") CompanionColor.Warn else CompanionColor.SignalDim,
                ),
                maxLines = 1,
            )
        }
    }
}

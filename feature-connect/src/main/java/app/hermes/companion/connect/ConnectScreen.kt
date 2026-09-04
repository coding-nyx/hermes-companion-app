package app.hermes.companion.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HairlineField
import app.hermes.companion.domain.OriginPolicy

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
        Text(text = "HERMES", style = CompanionType.Display)
        Spacer(Modifier.height(CompanionSpace.Md))
        Hairline(Modifier.width(96.dp))
        Spacer(Modifier.height(CompanionSpace.Xl))
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
        Text(
            text = "CONNECT",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .testTag("connect.go")
                .border(CompanionSpace.Hairline, CompanionColor.Signal)
                .clickable(onClick = onConnect)
                .padding(horizontal = 16.dp, vertical = 10.dp),
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

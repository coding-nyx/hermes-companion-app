package app.hermes.companion.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HairlineField

@Composable
fun ConnectScreen(
    origin: String,
    loading: Boolean,
    error: String?,
    onOriginChange: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
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
        Spacer(Modifier.height(CompanionSpace.Lg))
        Text(
            text = if (loading) "CONNECTING" else "CONNECT",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .testTag("connect.go")
                .border(CompanionSpace.Hairline, CompanionColor.Signal)
                .clickable(enabled = !loading, onClick = onConnect)
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

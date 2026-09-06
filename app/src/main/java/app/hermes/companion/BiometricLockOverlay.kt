package app.hermes.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType

@Composable
fun BiometricLockOverlay(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .clickable(onClick = {})
            .testTag("lock.overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CompanionSpace.Lg),
        ) {
            Text(text = "LOCKED", style = CompanionType.Display, modifier = Modifier.testTag("lock.status"))
            Text(
                text = "biometric or device PIN",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
            )
            Text(
                text = "UNLOCK",
                style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                modifier = Modifier
                    .testTag("lock.unlock")
                    .border(CompanionSpace.Hairline, CompanionColor.Signal)
                    .clickable(onClick = onUnlock)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

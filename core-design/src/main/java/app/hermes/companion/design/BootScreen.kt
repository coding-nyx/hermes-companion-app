package app.hermes.companion.design

import androidx.compose.foundation.background
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun BootScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .padding(CompanionSpace.Xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "HERMES",
            style = CompanionType.Display.copy(
                color = CompanionColor.Text,
                letterSpacing = CompanionType.Display.letterSpacing,
            ),
        )
        Spacer(Modifier.height(CompanionSpace.Md))
        Hairline(Modifier.width(96.dp))
        Spacer(Modifier.height(CompanionSpace.Md))
        Text(text = "companion // void", style = CompanionType.Mono)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF07080A)
@Composable
private fun BootPreview() {
    CompanionTheme { BootScreen() }
}

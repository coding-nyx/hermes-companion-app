package app.hermes.companion.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.SignalCursor
import app.hermes.companion.model.TerminalExecResult

data class TerminalLogEntry(
    val command: String,
    val result: TerminalExecResult?,
    val isRunning: Boolean = false,
    val timestamp: String = "",
)

@Composable
fun ConsoleScreen(
    logs: List<TerminalLogEntry>,
    isExecuting: Boolean,
    onExecute: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val presets = listOf(
        "hermes status",
        "uptime",
        "free -m",
        "df -h",
        "git status",
        "top -b -n 1 | head -n 15",
        "ps aux | grep hermes",
    )

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .testTag("console.screen"),
    ) {
        // Console Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (isExecuting) CompanionColor.Warn else CompanionColor.Signal),
                )
                Spacer(Modifier.width(CompanionSpace.Sm))
                Text(
                    text = if (isExecuting) "HOST EXEC // BUSY" else "HOST TERMINAL // READY",
                    style = CompanionType.MonoSmall.copy(
                        color = if (isExecuting) CompanionColor.Warn else CompanionColor.Signal,
                    ),
                )
            }
            Text(
                text = "CLEAR",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                modifier = Modifier
                    .testTag("console.clear")
                    .clickable(onClick = onClear)
                    .padding(CompanionSpace.Xs),
            )
        }
        Hairline()

        // Preset command chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
        ) {
            for (preset in presets) {
                Box(
                    modifier = Modifier
                        .background(CompanionColor.VoidElevated)
                        .border(1.dp, CompanionColor.Line)
                        .clickable(enabled = !isExecuting) {
                            input = preset
                            onExecute(preset)
                        }
                        .padding(horizontal = CompanionSpace.Sm, vertical = CompanionSpace.Xs),
                ) {
                    Text(
                        text = preset,
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Text),
                    )
                }
            }
        }
        Hairline()

        // Terminal Output Stream
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(CompanionColor.VoidElevated)
                .padding(CompanionSpace.Md),
        ) {
            if (logs.isEmpty()) {
                FetchPane(
                    label = "READY FOR COMMANDS",
                    hint = "// tap preset or type",
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("console.empty"),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
                ) {
                    items(logs) { entry ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$ ",
                                    style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                                )
                                Text(
                                    text = entry.command,
                                    style = CompanionType.Mono.copy(color = CompanionColor.Text),
                                )
                                Spacer(Modifier.weight(1f))
                                if (entry.timestamp.isNotBlank()) {
                                    Text(
                                        text = entry.timestamp,
                                        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                                    )
                                }
                            }
                            Spacer(Modifier.height(CompanionSpace.Xs))
                            if (entry.isRunning) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SignalCursor()
                                    Spacer(Modifier.width(CompanionSpace.Sm))
                                    Text(
                                        text = "executing on host",
                                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                                    )
                                }
                            } else if (entry.result != null) {
                                val res = entry.result
                                if (res.stdout.isNotBlank()) {
                                    Text(
                                        text = res.stdout.trimEnd(),
                                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Text),
                                    )
                                }
                                if (res.stderr.isNotBlank()) {
                                    Text(
                                        text = res.stderr.trimEnd(),
                                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger),
                                    )
                                }
                                if (res.exitCode != 0) {
                                    Text(
                                        text = "exit code: ${res.exitCode}",
                                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Danger),
                                    )
                                }
                            }
                            Spacer(Modifier.height(CompanionSpace.Sm))
                            Hairline()
                        }
                    }
                }
            }
        }
        Hairline()

        // Terminal Command Composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CompanionColor.Void)
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ">",
                style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                modifier = Modifier.padding(end = CompanionSpace.Sm),
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = CompanionType.Mono.copy(color = CompanionColor.Text),
                cursorBrush = SolidColor(CompanionColor.Signal),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank() && !isExecuting) {
                        val cmd = input.trim()
                        input = ""
                        onExecute(cmd)
                    }
                }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = CompanionSpace.Sm)
                    .testTag("console.input"),
                decorationBox = { innerTextField ->
                    if (input.isEmpty()) {
                        Text(
                            text = "enter shell command...",
                            style = CompanionType.Mono.copy(color = CompanionColor.TextMute),
                        )
                    }
                    innerTextField()
                },
            )
            Spacer(Modifier.width(CompanionSpace.Sm))
            Box(
                modifier = Modifier
                    .background(if (input.isNotBlank() && !isExecuting) CompanionColor.SignalDim else CompanionColor.VoidElevated)
                    .border(1.dp, if (input.isNotBlank() && !isExecuting) CompanionColor.Signal else CompanionColor.Line)
                    .clickable(enabled = input.isNotBlank() && !isExecuting) {
                        val cmd = input.trim()
                        input = ""
                        onExecute(cmd)
                    }
                    .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm)
                    .testTag("console.run"),
            ) {
                Text(
                    text = if (isExecuting) "..." else "RUN",
                    style = CompanionType.MonoSmall.copy(
                        color = if (input.isNotBlank() && !isExecuting) CompanionColor.Signal else CompanionColor.TextMute,
                    ),
                )
            }
        }
    }
}

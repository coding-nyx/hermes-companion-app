package app.hermes.companion.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.design.HairlineField
import app.hermes.companion.model.ApprovalPrompt
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    draft: String,
    streaming: Boolean,
    error: String?,
    approval: ApprovalPrompt?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onApproval: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .imePadding()
            .testTag("chat.surface"),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            verticalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
        ) {
            items(messages, key = { it.id }) { message ->
                when (message.role) {
                    MessageRole.USER -> Text(
                        text = message.text,
                        style = CompanionType.Body.copy(color = CompanionColor.TextDim),
                        modifier = Modifier.testTag("chat.user"),
                    )
                    MessageRole.TOOL -> ToolRow(message)
                    MessageRole.ASSISTANT -> Row {
                        Text(
                            text = message.text,
                            style = CompanionType.Body.copy(color = CompanionColor.Text),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (message.streaming) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                Modifier
                                    .padding(top = 4.dp)
                                    .width(7.dp)
                                    .height(13.dp)
                                    .background(CompanionColor.Signal)
                                    .testTag("chat.cursor"),
                            )
                        }
                    }
                }
            }
        }
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = CompanionType.Mono.copy(color = CompanionColor.Danger),
                modifier = Modifier.padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            )
        }
        if (approval != null) {
            ApprovalStrip(approval, onApproval)
        }
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HairlineField(
                value = draft,
                onValueChange = onDraftChange,
                imeAction = ImeAction.Send,
                keyboardType = KeyboardType.Text,
                onDone = onSend,
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat.draft"),
            )
            Spacer(Modifier.width(CompanionSpace.Md))
            val action = if (streaming) "INTERRUPT" to onInterrupt else "SEND" to onSend
            Text(
                text = action.first,
                style = CompanionType.MonoSmall.copy(
                    color = if (streaming) CompanionColor.Warn else CompanionColor.Signal,
                ),
                modifier = Modifier
                    .testTag("chat.send")
                    .border(
                        CompanionSpace.Hairline,
                        if (streaming) CompanionColor.Warn else CompanionColor.Signal,
                    )
                    .clickable(onClick = action.second)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ApprovalStrip(prompt: ApprovalPrompt, onApproval: (String) -> Unit) {
    val kind = prompt.kind.uppercase()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CompanionColor.VoidElevated)
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md)
            .testTag("chat.approval"),
    ) {
        Text(
            text = "$kind · ${prompt.command}",
            style = CompanionType.Mono.copy(color = CompanionColor.Warn),
        )
        Row(
            modifier = Modifier.padding(top = CompanionSpace.Sm),
            horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
        ) {
            prompt.choices.forEach { choice ->
                val deny = choice.equals("deny", ignoreCase = true) ||
                    choice.equals("reject", ignoreCase = true)
                val label = when {
                    deny -> "DENY"
                    choice.equals("once", ignoreCase = true) ||
                        choice.equals("allow", ignoreCase = true) ||
                        choice.equals("submit", ignoreCase = true) -> "ALLOW"
                    else -> choice.uppercase()
                }
                val tag = if (deny) "chat.approval.deny" else "chat.approval.${choice.lowercase()}"
                val color = if (deny) CompanionColor.Danger else CompanionColor.Signal
                Text(
                    text = label,
                    style = CompanionType.MonoSmall.copy(color = color),
                    modifier = Modifier
                        .testTag(if (deny) "chat.approval.deny" else if (label == "ALLOW") "chat.approval.allow" else tag)
                        .border(CompanionSpace.Hairline, color)
                        .clickable { onApproval(choice) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolRow(message: ChatMessage) {
    val name = message.toolName ?: "tool"
    val detail = message.toolDetail ?: message.text
    Text(
        text = "$name · $detail",
        style = CompanionType.Mono,
        modifier = Modifier
            .fillMaxWidth()
            .border(CompanionSpace.Hairline, CompanionColor.Line)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("chat.tool"),
    )
}

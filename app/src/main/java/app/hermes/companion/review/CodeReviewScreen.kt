package app.hermes.companion.review

import app.hermes.companion.design.*

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.hermes.companion.design.rememberDismissKeyboard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.Hairline
import app.hermes.companion.model.GitDiffSummary
import app.hermes.companion.model.GitStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodeReviewScreen(
    status: GitStatus?,
    diff: GitDiffSummary?,
    selectedFile: String?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSelectFile: (String) -> Unit,
    onCloseDiff: () -> Unit,
    onStageFile: (String, Boolean) -> Unit,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var commitMessage by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .testTag("review.screen"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Branch", style = CompanionType.Caption)
                Text(status?.branch ?: "No repository selected", style = CompanionType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            app.hermes.companion.design.ActionButton(if (isLoading) "Refreshing…" else "Refresh", onRefresh,
                enabled = !isLoading, modifier = Modifier.testTag("review.refresh"))
        }
        Hairline()

        // Counts summary row
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Staged · ${status?.stagedFiles?.size ?: 0}",
                style = CompanionType.Caption.copy(
                    color = if ((status?.stagedFiles?.size ?: 0) > 0) CompanionColor.Signal else CompanionColor.TextDim,
                ),
            )
            Text(
                text = "Modified · ${status?.modifiedFiles?.size ?: 0}",
                style = CompanionType.Caption.copy(
                    color = if ((status?.modifiedFiles?.size ?: 0) > 0) CompanionColor.Warn else CompanionColor.TextDim,
                ),
            )
            Text(
                text = "Untracked · ${status?.untrackedFiles?.size ?: 0}",
                style = CompanionType.Caption.copy(color = CompanionColor.TextMute),
            )
        }
        Hairline()

        // Diff or File List View
        if (selectedFile != null && diff != null) {
            // Diff Inspector
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()

                        .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedFile,
                        style = CompanionType.Mono.copy(color = CompanionColor.Text),
                        modifier = Modifier.weight(1f),
                    )
                    ActionText(
                        text = "Close",
                        style = CompanionType.Caption.copy(color = CompanionColor.Warn),
                        modifier = Modifier
                            .testTag("review.diff.close")
                            .heightIn(min = 48.dp).clickable(onClick = onCloseDiff)
                            .padding(CompanionSpace.Xs),
                    )
                }
                Hairline()

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()

                        .padding(CompanionSpace.Md),
                ) {
                    if (isLoading && diff.rawDiff.isBlank()) {
                        FetchPane(
                            label = "LOADING DIFF",
                            hint = "// handshake",
                            scanning = true,
                            modifier = Modifier.testTag("review.diff.loading"),
                        )
                    } else if (diff.rawDiff.isBlank()) {
                        FetchPane(
                            label = "NO DIFF CHANGES",
                            hint = "// idle",
                            modifier = Modifier.testTag("review.diff.empty"),
                        )
                    } else {
                        val lines = remember(diff.rawDiff) { diff.rawDiff.lines() }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(lines) { line ->
                                val color = when {
                                    line.startsWith("+") && !line.startsWith("+++") -> CompanionColor.Signal
                                    line.startsWith("-") && !line.startsWith("---") -> CompanionColor.Danger
                                    line.startsWith("@@") -> CompanionColor.Warn
                                    else -> CompanionColor.TextDim
                                }
                                Text(
                                    text = line,
                                    style = CompanionType.Mono.copy(color = color),
                                )
                            }
                        }
                    }
                }
            }
        } else if (isLoading && status == null) {
            FetchPane(
                label = "SYNCING WORKING TREE",
                hint = "// handshake",
                scanning = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("review.loading"),
            )
        } else {
            val treeEmpty = status?.stagedFiles.isNullOrEmpty() &&
                status?.modifiedFiles.isNullOrEmpty() &&
                status?.untrackedFiles.isNullOrEmpty()
            if (isLoading) {
                FetchRow(label = "SYNCING", modifier = Modifier.testTag("review.loading"))
            }
            if (treeEmpty && !isLoading) {
                FetchPane(
                    label = "Working tree clean",
                    hint = "There are no changes to review.",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("review.empty"),
                )
            } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {

                if (!status?.stagedFiles.isNullOrEmpty()) {
                    item {
                        Spacer(Modifier.height(CompanionSpace.Md))
                        Text(
                            text = "Staged for commit",
                            style = CompanionType.Title,
                        )
                        Spacer(Modifier.height(CompanionSpace.Xs))
                    }
                    items(status?.stagedFiles.orEmpty()) { file ->
                        FileRow(
                            file = file,
                            statusColor = CompanionColor.Signal,
                            actionLabel = "UNSTAGE",
                            onAction = { onStageFile(file, false) },
                            onClick = { onSelectFile(file) },
                        )
                    }
                }

                if (!status?.modifiedFiles.isNullOrEmpty()) {
                    item {
                        Spacer(Modifier.height(CompanionSpace.Md))
                        Text(
                            text = "Modified",
                            style = CompanionType.Title,
                        )
                        Spacer(Modifier.height(CompanionSpace.Xs))
                    }
                    items(status?.modifiedFiles.orEmpty()) { file ->
                        FileRow(
                            file = file,
                            statusColor = CompanionColor.Warn,
                            actionLabel = "STAGE",
                            onAction = { onStageFile(file, true) },
                            onClick = { onSelectFile(file) },
                        )
                    }
                }

                if (!status?.untrackedFiles.isNullOrEmpty()) {
                    item {
                        Spacer(Modifier.height(CompanionSpace.Md))
                        Text(
                            text = "Untracked",
                            style = CompanionType.Title,
                        )
                        Spacer(Modifier.height(CompanionSpace.Xs))
                    }
                    items(status?.untrackedFiles.orEmpty()) { file ->
                        FileRow(
                            file = file,
                            statusColor = CompanionColor.TextMute,
                            actionLabel = "STAGE",
                            onAction = { onStageFile(file, true) },
                            onClick = { onSelectFile(file) },
                        )
                    }
                }
            }
            }
        }
        Hairline()

        // Keep commit controls with staged work instead of displaying an inert footer on clean trees.
        if (!status?.stagedFiles.isNullOrEmpty() || commitMessage.isNotBlank()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CompanionColor.Void)
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dismissKeyboard = rememberDismissKeyboard()
            fun commit() {
                if (commitMessage.isBlank() || isLoading || status?.stagedFiles.isNullOrEmpty()) return
                val msg = commitMessage.trim()
                onCommit(msg)
                dismissKeyboard()
            }
            BasicTextField(
                value = commitMessage,
                onValueChange = { commitMessage = it },
                textStyle = CompanionType.Body.copy(color = CompanionColor.Text),
                cursorBrush = SolidColor(CompanionColor.Signal),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .weight(1f)
                    .background(CompanionColor.VoidSurface, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .border(1.dp, CompanionColor.CardBorder, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .testTag("review.commit.input"),
                decorationBox = { innerTextField ->
                    if (commitMessage.isEmpty()) {
                        Text(
                            text = "Commit message…",
                            style = CompanionType.Body.copy(color = CompanionColor.TextMute),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                },
            )
            Spacer(Modifier.width(CompanionSpace.Sm))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = 48.dp).clickable(enabled = commitMessage.isNotBlank() && !isLoading && !status?.stagedFiles.isNullOrEmpty()) { commit() }
                    .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm)
                    .testTag("review.commit.button"),
            ) {
                Text(
                    text = "Commit",
                    style = CompanionType.Caption.copy(
                        color = if (commitMessage.isNotBlank()) CompanionColor.Signal else CompanionColor.TextMute,
                    ),
                )
            }
        }
        }
    }
}

@Composable
private fun FileRow(
    file: String,
    statusColor: androidx.compose.ui.graphics.Color,
    actionLabel: String,
    onAction: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp).clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.substringAfterLast('/'), style = CompanionType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            file.substringBeforeLast('/', "").takeIf { it.isNotBlank() }?.let {
                Text(it, style = CompanionType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ActionText(
            text = actionLabel.lowercase().replaceFirstChar(Char::titlecase),
            style = CompanionType.Caption.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .heightIn(min = 48.dp).clickable(onClick = onAction)
                .padding(CompanionSpace.Xs),
        )
    }
    Hairline()
}

package app.hermes.companion.review

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
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.Hairline
import app.hermes.companion.model.GitDiffSummary
import app.hermes.companion.model.GitStatus

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
        // Branch & status header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BRANCH // ",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                )
                Text(
                    text = status?.branch ?: "main",
                    style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isLoading) "SYNCING" else "REFRESH",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                    modifier = Modifier
                        .testTag("review.refresh")
                        .clickable(enabled = !isLoading, onClick = onRefresh)
                        .padding(CompanionSpace.Xs),
                )
            }
        }
        Hairline()

        // Counts summary row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "STAGED: ${status?.stagedFiles?.size ?: 0}",
                style = CompanionType.MonoSmall.copy(
                    color = if ((status?.stagedFiles?.size ?: 0) > 0) CompanionColor.Signal else CompanionColor.TextDim,
                ),
            )
            Text(
                text = "MODIFIED: ${status?.modifiedFiles?.size ?: 0}",
                style = CompanionType.MonoSmall.copy(
                    color = if ((status?.modifiedFiles?.size ?: 0) > 0) CompanionColor.Warn else CompanionColor.TextDim,
                ),
            )
            Text(
                text = "UNTRACKED: ${status?.untrackedFiles?.size ?: 0}",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
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
                        .background(CompanionColor.VoidElevated)
                        .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedFile,
                        style = CompanionType.Mono.copy(color = CompanionColor.Text),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "CLOSE [X]",
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
                        modifier = Modifier
                            .testTag("review.diff.close")
                            .clickable(onClick = onCloseDiff)
                            .padding(CompanionSpace.Xs),
                    )
                }
                Hairline()

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(CompanionColor.VoidElevated)
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
                                    style = CompanionType.MonoSmall.copy(color = color),
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
                    label = "WORKING TREE CLEAN",
                    hint = "// no changes",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("review.empty"),
                )
            } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg),
            ) {

                if (!status?.stagedFiles.isNullOrEmpty()) {
                    item {
                        Spacer(Modifier.height(CompanionSpace.Md))
                        Text(
                            text = "STAGED FOR COMMIT",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
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
                            text = "MODIFIED",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.Warn),
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
                            text = "UNTRACKED",
                            style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
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

        // Commit Composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CompanionColor.Void)
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dismissKeyboard = rememberDismissKeyboard()
            fun commit() {
                if (commitMessage.isBlank() || isLoading) return
                val msg = commitMessage.trim()
                commitMessage = ""
                onCommit(msg)
                dismissKeyboard()
            }
            BasicTextField(
                value = commitMessage,
                onValueChange = { commitMessage = it },
                textStyle = CompanionType.Mono.copy(color = CompanionColor.Text),
                cursorBrush = SolidColor(CompanionColor.Signal),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = CompanionSpace.Sm)
                    .testTag("review.commit.input"),
                decorationBox = { innerTextField ->
                    if (commitMessage.isEmpty()) {
                        Text(
                            text = "commit message...",
                            style = CompanionType.Mono.copy(color = CompanionColor.TextMute),
                        )
                    }
                    innerTextField()
                },
            )
            Spacer(Modifier.width(CompanionSpace.Sm))
            Box(
                modifier = Modifier
                    .background(if (commitMessage.isNotBlank()) CompanionColor.SignalDim else CompanionColor.VoidElevated)
                    .border(1.dp, if (commitMessage.isNotBlank()) CompanionColor.Signal else CompanionColor.Line)
                    .clickable(enabled = commitMessage.isNotBlank() && !isLoading) { commit() }
                    .padding(horizontal = CompanionSpace.Md, vertical = CompanionSpace.Sm)
                    .testTag("review.commit.button"),
            ) {
                Text(
                    text = "COMMIT",
                    style = CompanionType.MonoSmall.copy(
                        color = if (commitMessage.isNotBlank()) CompanionColor.Signal else CompanionColor.TextMute,
                    ),
                )
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
            .clickable(onClick = onClick)
            .padding(vertical = CompanionSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(14.dp)
                    .background(statusColor),
            )
            Spacer(Modifier.width(CompanionSpace.Sm))
            Text(
                text = file,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Text),
                maxLines = 1,
            )
        }
        Text(
            text = "[$actionLabel]",
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            modifier = Modifier
                .clickable(onClick = onAction)
                .padding(CompanionSpace.Xs),
        )
    }
    Hairline()
}

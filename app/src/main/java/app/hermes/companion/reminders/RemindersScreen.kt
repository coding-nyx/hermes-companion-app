package app.hermes.companion.reminders

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.FetchPane
import app.hermes.companion.design.FetchRow
import app.hermes.companion.design.Hairline
import app.hermes.companion.model.CronJob

@Composable
fun RemindersScreen(
    jobs: List<CronJob>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onTriggerJob: (String) -> Unit,
    onToggleJob: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .testTag("reminders.screen"),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "CRON JOBS // ",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
                )
                Text(
                    text = "${jobs.size} ACTIVE",
                    style = CompanionType.Mono.copy(color = CompanionColor.Signal),
                )
            }
            Text(
                text = if (isLoading) "SYNCING" else "REFRESH",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                modifier = Modifier
                    .testTag("reminders.refresh")
                    .clickable(enabled = !isLoading, onClick = onRefresh)
                    .padding(CompanionSpace.Xs),
            )
        }
        Hairline()

        if (jobs.isEmpty()) {
            FetchPane(
                label = if (isLoading) "LOADING SCHEDULED TASKS" else "NO CRON JOBS",
                hint = if (isLoading) "// handshake" else "// idle",
                scanning = isLoading,
                modifier = Modifier
                    .weight(1f)
                    .testTag(if (isLoading) "reminders.loading" else "reminders.empty"),
            )
        } else {
            if (isLoading) {
                FetchRow(label = "SYNCING CRON", modifier = Modifier.testTag("reminders.loading"))
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = CompanionSpace.Lg),
            ) {
                items(jobs, key = { it.id }) { job ->
                    CronJobCard(
                        job = job,
                        onTrigger = { onTriggerJob(job.id) },
                        onToggle = { onToggleJob(job.id, job.enabled) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CronJobCard(
    job: CronJob,
    onTrigger: () -> Unit,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = CompanionSpace.Md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(16.dp)
                        .background(if (job.enabled) CompanionColor.Signal else CompanionColor.TextMute),
                )
                Spacer(Modifier.width(CompanionSpace.Sm))
                Text(
                    text = job.name.ifBlank { job.id },
                    style = CompanionType.Body.copy(
                        color = if (job.enabled) CompanionColor.Text else CompanionColor.TextDim,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .background(CompanionColor.VoidElevated)
                    .border(1.dp, if (job.enabled) CompanionColor.SignalDim else CompanionColor.Line)
                    .padding(horizontal = CompanionSpace.Sm, vertical = 2.dp),
            ) {
                Text(
                    text = if (job.enabled) "ACTIVE" else "PAUSED",
                    style = CompanionType.MonoSmall.copy(
                        color = if (job.enabled) CompanionColor.Signal else CompanionColor.TextMute,
                    ),
                )
            }
        }

        Spacer(Modifier.height(CompanionSpace.Xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "expr: ${job.scheduleDisplay}",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            )
            if (job.profile.isNotBlank()) {
                Text(
                    text = "profile: ${job.profile}",
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                )
            }
        }

        if (job.nextRunAt != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "next: ${job.nextRunAt}",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextMute),
            )
        }

        if (job.lastStatus != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "last status: ${job.lastStatus}" + if (job.lastError != null) " (${job.lastError})" else "",
                style = CompanionType.MonoSmall.copy(
                    color = if (job.lastStatus == "ok") CompanionColor.TextDim else CompanionColor.Danger,
                ),
            )
        }

        Spacer(Modifier.height(CompanionSpace.Sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = if (job.enabled) "[PAUSE]" else "[RESUME]",
                style = CompanionType.MonoSmall.copy(
                    color = if (job.enabled) CompanionColor.Warn else CompanionColor.Signal,
                ),
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(CompanionSpace.Xs),
            )
            Spacer(Modifier.width(CompanionSpace.Md))
            Text(
                text = "[TRIGGER NOW]",
                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                modifier = Modifier
                    .clickable(onClick = onTrigger)
                    .padding(CompanionSpace.Xs),
            )
        }
        Spacer(Modifier.height(CompanionSpace.Sm))
        Hairline()
    }
}

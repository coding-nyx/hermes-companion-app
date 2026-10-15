package app.hermes.companion.reminders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import app.hermes.companion.model.CronJob

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    jobs: List<CronJob>, isLoading: Boolean, onRefresh: () -> Unit,
    onTriggerJob: (String) -> Unit, onToggleJob: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var runRequest by remember { mutableStateOf<CronJob?>(null) }
    val selected = jobs.firstOrNull { it.id == selectedId }
    Column(modifier.fillMaxSize().testTag("reminders.screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${jobs.count { it.enabled }} active · ${jobs.size} total", style = CompanionType.Caption, modifier = Modifier.weight(1f))
            ActionButton(if (isLoading) "Refreshing…" else "Refresh", onRefresh, enabled = !isLoading, modifier = Modifier.testTag("reminders.refresh"))
        }
        if (jobs.isEmpty()) FetchPane(if (isLoading) "Loading schedules…" else "No schedules yet",
            hint = if (isLoading) "Reading scheduled work from this host." else "Schedules created in Hermes will appear here.",
            scanning = isLoading, modifier = Modifier.weight(1f).testTag(if (isLoading) "reminders.loading" else "reminders.empty"))
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            if (isLoading) item { FetchRow("Refreshing schedules…", modifier = Modifier.testTag("reminders.loading")) }
            listOf(true to "Active", false to "Paused").forEach { (enabled, label) ->
                val group = jobs.filter { it.enabled == enabled }
                if (group.isNotEmpty()) item(key = label) {
                    GroupedSection(label) {
                        group.forEach { job -> PreferenceRow(job.name.ifBlank { job.id }, job.scheduleDisplay,
                            value = if (job.enabled) "Active" else "Paused", onClick = { selectedId = job.id },
                            modifier = Modifier.testTag("reminders.job.${job.id}")) }
                    }
                }
            }
        }
    }
    if (selected != null) ModalBottomSheet(onDismissRequest = { selectedId = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CompanionColor.Void, contentColor = CompanionColor.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)
            .testTag("reminders.detail"), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            SheetHeading(selected.name.ifBlank { "Schedule" }, { selectedId = null })
            GroupedSection("Schedule") {
                PreferenceRow("Repeat", selected.scheduleDisplay.ifBlank { "Not reported" })
                PreferenceRow("Profile", selected.profile.ifBlank { "Host default" })
                PreferenceRow("State", if (selected.enabled) "Active" else "Paused")
                PreferenceRow("Next run", selected.nextRunAt ?: if (selected.enabled) "Not reported" else "Paused")
            }
            if (selected.prompt.isNotBlank()) GroupedSection("Instruction", padded = true) { Text(selected.prompt, style = CompanionType.Body) }
            GroupedSection("Latest outcome") {
                PreferenceRow("Last run", selected.lastRunAt ?: "Not reported")
                PreferenceRow("Result", selected.lastStatus ?: "No outcome reported yet")
                selected.lastError?.takeIf { it.isNotBlank() }?.let { PreferenceRow("Error", it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(if (selected.enabled) "Pause schedule" else "Resume schedule", { onToggleJob(selected.id, selected.enabled) },
                    enabled = !isLoading, modifier = Modifier.testTag("reminders.toggle"))
                ActionButton("Run now", { runRequest = selected }, enabled = !isLoading, kind = ActionKind.PRIMARY,
                    modifier = Modifier.testTag("reminders.run"))
            }
        }
    }
    runRequest?.let { job -> AlertDialog(onDismissRequest = { runRequest = null }, title = { Text("Run this schedule now?") },
        text = { Text("Start one run of ${job.name.ifBlank { job.id }}. The recurring schedule stays unchanged.") },
        confirmButton = { TextButton(onClick = { runRequest = null; onTriggerJob(job.id) }) { Text("Run now") } },
        dismissButton = { TextButton(onClick = { runRequest = null }) { Text("Cancel") } }) }
}

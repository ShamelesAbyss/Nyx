package com.nyx.ai.ui

import com.nyx.ai.ui.prism.PrismCheckbox as Checkbox
import com.nyx.ai.ui.prism.PrismText as Text
import com.nyx.ai.ui.prism.PrismTextButton as TextButton
import com.nyx.ai.ui.prism.PrismButton as Button
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nyx.ai.NyxState
import com.nyx.ai.NyxViewModel
import com.nyx.ai.autonomy.QuickActionType

@Composable
fun QuickActionsCard(state: NyxState, vm: NyxViewModel, openWorkbench: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(QuickActionType.INSPECT_PROJECT.name) }
    var approved by rememberSaveable { mutableStateOf(false) }
    var artifact by rememberSaveable { mutableStateOf("") }
    val type = QuickActionType.valueOf(selected)
    // Selection changes invalidate the visible approval, including asynchronous manifest refresh.
    LaunchedEffect(selected, state.terminalWorkspace, state.terminalEnvironment, state.guestAlias,
        state.selectedObjectiveId, artifact, state.workbenchManifest) { approved = false }
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Quick Actions", style = MaterialTheme.typography.titleMedium)
            Text("Explicit fixed operations through the objective engine. No AI planning or model request.", style = MaterialTheme.typography.bodySmall)
            QuickActionType.entries.forEach { action ->
                FilterChip(selected = type == action, onClick = { selected = action.name; approved = false }, label = { Text(action.label) })
            }
            if (type == QuickActionType.VERIFY_ARTIFACT) {
                Text("Choose an existing Workbench objective, then its retained output. Verification creates an evidence report, not a new copy of the file.", style = MaterialTheme.typography.bodySmall)
                state.objectives.forEach { objective ->
                    TextButton(onClick = { artifact = ""; approved = false; vm.selectObjective(objective.id) }, enabled = !state.objectiveRunning) {
                        Text((if (state.selectedObjectiveId == objective.id) "Selected: " else "") + objective.title)
                    }
                }
                state.workbenchManifest?.artifacts?.forEach { record ->
                    FilterChip(selected = artifact == record.id, onClick = { artifact = record.id; approved = false }, label = { Text(record.fileName) })
                }
                Text("Checks registered ownership, format, length and SHA-256 of existing bytes. This does not verify factual accuracy.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val source = state.objectiveEvents.lastOrNull { it.kind == "quick_action" }?.detail?.let {
                        runCatching { org.json.JSONObject(it).optString("sourceObjectiveId") }.getOrNull()
                    }
                    if (!source.isNullOrBlank()) vm.selectObjective(source)
                    openWorkbench()
                }) { Text("Open existing Workbench output") }
            } else {
                Text("Selected authorized project: ${state.terminalEnvironment.name}/${state.guestAlias}\n${state.terminalWorkspace.ifBlank { "Select a workspace in Terminal first" }}", style = MaterialTheme.typography.bodySmall)
                Text(if (type == QuickActionType.RUN_TESTS) "Permission: execute the detected fixed test procedure in this trusted project. Project scripts may modify files or access the network with Termux authority. No command overrides or installs."
                    else "Permission: bounded read-only inspection of this project. No project script execution, edits or installation.", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                Checkbox(checked = approved, onCheckedChange = { approved = it }, enabled = !state.objectiveRunning)
                Text("I approve this selected action and scope", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { vm.createQuickAction(type, approved, artifact); approved = false },
                enabled = approved && !state.objectiveRunning && (type != QuickActionType.VERIFY_ARTIFACT || artifact.isNotBlank())) { Text("Start Quick Action") }
            Text("Progress, actual results and failures appear in the mission task graph and Live Activity below. Interrupted actions require a new explicit approval.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

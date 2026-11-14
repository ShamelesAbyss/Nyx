package com.nyx.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyx.ai.*
import com.nyx.ai.autonomy.WorkerActivityBus
import com.nyx.ai.terminal.TerminalOperationState
import kotlinx.coroutines.delay

@Composable
fun TerminalWorkersCard(state: NyxState, vm: NyxViewModel) {
    var selected by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.objectiveRunning, state.terminalRunning) {
        vm.refreshTerminalOperations()
        while (state.objectiveRunning || state.terminalRunning) {
            delay(5_000)
            vm.refreshTerminalOperations()
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TERMINAL OPERATIONS", color = VioletSoft, fontSize = 11.sp)
            val active = state.terminalOperations.count { it.state == TerminalOperationState.RUNNING }
            val queued = state.terminalOperations.count { it.state == TerminalOperationState.QUEUED }
            val uncertain = state.terminalOperations.count { it.state == TerminalOperationState.UNCERTAIN }
            Text("$active active · $queued queued · $uncertain need review", color = TextMain)
            Text("Independent commands, not persistent interactive sessions. Requested capacity is capped by memory, heat, battery, energy mode and workspace conflicts.", color = TextMuted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.setTerminalContexts(state.requestedTerminalContexts - 1) }, enabled = state.requestedTerminalContexts > 1) { Text("−") }
                Text("${state.requestedTerminalContexts} requested", modifier = Modifier.padding(top = 12.dp), color = TextMain)
                TextButton(onClick = { vm.setTerminalContexts(state.requestedTerminalContexts + 1) }, enabled = state.requestedTerminalContexts < 8) { Text("+") }
                TextButton(onClick = vm::refreshTerminalOperations) { Text("Refresh") }
            }
            state.terminalOperations.take(8).forEach { operation ->
                val id = operation.context.operationId
                TextButton(onClick = { selected = if (selected == id) null else id }) {
                    Text("${id.take(8)} · ${operation.state.name.lowercase()} · exit ${operation.exitCode ?: "unknown"}")
                }
                if (selected == id) {
                    Text("Task ${operation.context.taskId ?: "unassigned"} · ${operation.context.namespace}\n${operation.context.workspace ?: "unverified path"}", color = TextMuted, fontSize = 11.sp)
                    Text(WorkerActivityBus.redact(operation.stdout.takeLast(3000)), color = TextMain, fontSize = 11.sp)
                    if (operation.stderr.isNotBlank()) Text(WorkerActivityBus.redact(operation.stderr.takeLast(1000)), color = Amber, fontSize = 11.sp)
                    if (operation.state == TerminalOperationState.UNCERTAIN) TextButton(onClick = { review = id }) { Text("Review reservation") }
                }
            }
        }
    }
    review?.let { id ->
        AlertDialog(onDismissRequest = { review = null }, title = { Text("Confirm operation has stopped") },
            text = { Text("Verify independently that operation ${id.take(8)} is no longer running. Releasing its reservation does not kill it, replay it, or prove success. Do not confirm if its process state is uncertain.") },
            confirmButton = { TextButton(onClick = { vm.confirmTerminalOperationStopped(id); review = null }) { Text("I verified it stopped") } },
            dismissButton = { TextButton(onClick = { review = null }) { Text("Keep reserved") } })
    }
}

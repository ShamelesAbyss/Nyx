package com.nyx.ai.ui.prism

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nyx.ai.NyxState
import com.nyx.ai.NyxViewModel
import com.nyx.ai.autonomy.*
import com.nyx.ai.data.ObjectiveEntity
import com.nyx.ai.workbench.ArtifactRecord
import com.nyx.ai.workbench.ArtifactStatus
import com.nyx.ai.ui.consoleTextParts

@Composable fun MissionResultsCard(result: MissionResult, onTechnical: () -> Unit, onWorkbench: (() -> Unit)? = null,
    onTerminal: (() -> Unit)? = null, onReview: (() -> Unit)? = null, onPause: (() -> Unit)? = null, onCancel: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    var fullOutput by rememberSaveable(result.objectiveId) { mutableStateOf<Int?>(null) }
    PrismCard(modifier = Modifier.fillMaxWidth().testTag("mission_results")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrismText("MISSION RESULTS", color = LocalPrismAccent.current, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            PrismText(result.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            PrismText("Status: ${result.status.replace('_', ' ')}", color = LocalPrismAccent.current, modifier = Modifier.testTag("mission_result_status"))
            PrismText(result.summary, modifier = Modifier.testTag("mission_result_summary"))
            PrismText(result.verification, color = PrismMuted, modifier = Modifier.testTag("mission_result_verification"))
            result.facts.forEach { fact ->
                Column(Modifier.fillMaxWidth()) {
                    PrismText(fact.label, color = LocalPrismAccent.current, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    SelectionContainer { PrismText(fact.value, modifier = Modifier.testTag("mission_fact_${fact.label}")) }
                }
            }
            result.outputs.forEachIndexed { index, output ->
                PrismText(output.title, fontWeight = FontWeight.SemiBold)
                PrismText(if (output.verified) "Task passed recorded local checks; review the content yourself." else "Observed response · verification incomplete", color = PrismMuted, fontSize = 12.sp)
                SelectionContainer { PrismText(output.text.take(900), maxLines = 10) }
                PrismTextButton(onClick = { fullOutput = index }, modifier = Modifier.testTag("mission_output_$index")) { Text("Read full result") }
            }
            PrismActionRow {
                if (onPause != null) PrismOutlinedButton(onClick = onPause) { Text("Pause mission") }
                if (onCancel != null) PrismOutlinedButton(onClick = onCancel) { Text("Cancel mission") }
                PrismButton(onClick = { clipboard.setText(AnnotatedString(result.copyText.take(60_000))) }, modifier = Modifier.testTag("mission_copy")) { Text(if (result.copyText.length > 60_000) "Copy result excerpt (60,000 chars)" else "Copy result") }
                PrismOutlinedButton(onClick = onTechnical, modifier = Modifier.testTag("mission_technical")) { Text("View technical details") }
                if (onWorkbench != null) PrismOutlinedButton(onClick = onWorkbench) { Text("Open Workbench") }
                if (onTerminal != null && result.facts.any { it.label == "Project location" }) PrismTextButton(onClick = onTerminal) { Text("Review in Terminal") }
                if (result.needsReview && !result.quickAction && onReview != null) PrismOutlinedButton(onClick = onReview) { Text("Review recovery") }
            }
            if (result.quickAction && result.needsReview) PrismText("To retry, make a new Quick Action selection and approve its scope. This result never replays work.", fontSize = 12.sp, color = PrismMuted)
            PrismText("Local checks establish recorded execution/byte evidence, not factual accuracy. File actions recheck ownership and integrity.", color = PrismMuted, fontSize = 12.sp)
        }
    }
    fullOutput?.let { index -> result.outputs.getOrNull(index)?.text?.let { text -> PrismTextDialog("Full mission result", text, onClose = { fullOutput = null }) } }
}

@Composable fun PrismTextDialog(title: String, text: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val parts = remember(text) { consoleTextParts(text) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PrismCard(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            PrismActionRow(Modifier.padding(horizontal = 12.dp)) {
                PrismText(title, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
                PrismTextButton(onClick = { clipboard.setText(AnnotatedString(text.take(60_000))) }) { Text(if (text.length > 60_000) "Copy excerpt (60,000 chars)" else "Copy text") }
                PrismTextButton(onClick = onClose) { Text("Close") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("prism_full_text"), contentPadding = PaddingValues(16.dp)) {
                itemsIndexed(parts) { _, part -> SelectionContainer { PrismText(part) } }
            }
        }
    }
}

@Composable fun WorkbenchPreviewDialog(state: NyxState, onClose: () -> Unit) {
    if (state.workbenchPreviewText.isNotBlank()) PrismTextDialog(state.workbenchPreviewTitle, state.workbenchPreviewText, onClose)
}

@Composable fun MissionResultsSection(state: NyxState, vm: NyxViewModel, objective: ObjectiveEntity?,
    onWorkbench: () -> Unit, onTerminal: (() -> Unit)? = null, onReview: (() -> Unit)? = null) {
    var technical by rememberSaveable(objective?.id) { mutableStateOf(false) }
    val result = remember(objective, state.objectiveTasks, state.objectiveEvents, state.objectiveArtifacts, state.workbenchManifest) {
        objective?.let { MissionResults.present(it, state.objectiveTasks, state.objectiveEvents, state.objectiveArtifacts, state.workbenchManifest) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> vm.saveWorkbenchExport(uri) }
    if (result != null) {
        val active = state.objectiveRunning && ExecutionMonitor.state.value.objectiveId == objective?.id
        MissionResultsCard(result, onTechnical = { technical = true }, onWorkbench, onTerminal, onReview,
            onPause = if (active) vm::pauseObjective else null,
            onCancel = if (active && objective != null) ({ vm.cancelObjective(objective.id) }) else null)
        val files = state.workbenchManifest?.takeIf { it.objectiveId == result.objectiveId && state.selectedObjectiveId == result.objectiveId && state.workbenchApproval.enabled }?.artifacts.orEmpty()
        if (files.isNotEmpty()) PrismSectionHeading("Your output files", "Registered outputs; every preview/save/share checks the existing private store again.")
        files.forEach { file ->
            MissionFileCard(file, onPreview = { vm.previewWorkbenchObject("artifact", file.id, file.fileName) },
                onSave = { vm.prepareWorkbenchExport(file)?.let { save.launch(it) } }, onShare = { vm.shareWorkbenchArtifact(file) })
        }
        if (state.workbenchMessage.isNotBlank()) PrismText(state.workbenchMessage, color = PrismMuted)
        if (technical && objective != null) MissionTechnicalDialog(objective, state.objectiveTasks, state.objectiveEvents,
            state.objectiveRunning && ExecutionMonitor.state.value.objectiveId == objective.id, onClose = { technical = false })

    } else PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { PrismText("Your work, in one place", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            PrismText("Start an objective or select a previous mission. Its observed results and registered files will appear here.", color = PrismMuted) }
    }
    WorkbenchPreviewDialog(state, vm::closeWorkbenchPreview)
}

@Composable fun MissionFileCard(record: ArtifactRecord, onPreview: () -> Unit, onSave: () -> Unit, onShare: () -> Unit) {
    PrismCard(Modifier.fillMaxWidth().testTag("mission_file_${record.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismText(record.fileName, fontWeight = FontWeight.SemiBold)
            PrismText("Registered ${record.format.name.lowercase()} · ${record.bytes} bytes · ${record.status.name}", color = PrismMuted)
            PrismActionRow {
                PrismOutlinedButton(onClick = onPreview) { Text("Preview") }
                PrismOutlinedButton(onClick = onSave, enabled = record.status == ArtifactStatus.VERIFIED) { Text("Save as…") }
                PrismOutlinedButton(onClick = onShare, enabled = record.status == ArtifactStatus.VERIFIED) { Text("Share") }
            }
            if (record.status != ArtifactStatus.VERIFIED) PrismText("Save/share need a registered verified artifact; reload the Workbench and review the failure.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable fun MissionTechnicalDialog(objective: ObjectiveEntity, tasks: List<com.nyx.ai.data.ObjectiveTaskEntity>,
    records: List<com.nyx.ai.data.ObjectiveEventEntity>, running: Boolean = false, onClose: () -> Unit) {
    val events = remember(objective.id, tasks, records) {
        val ids = tasks.filter { it.objectiveId == objective.id }.map { it.id }.toSet()
        records.filter { it.objectiveId == objective.id && (it.taskId == null || it.taskId in ids) }.map { event ->
            WorkerActivity(event.id, event.createdAt, event.objectiveId, event.taskId, WorkerActivityKind.INFO,
                event.kind, event.detail.orEmpty(), event.toolName, event.toolInput, event.toolOutput)
        }
    }
    com.nyx.ai.ui.WorkerConsoleDialog(events, running, objective.title, phase = "Persisted objective records",
        objectiveStatus = objective.status, currentObjectiveId = objective.id, currentRunId = null, onDismiss = onClose)
}

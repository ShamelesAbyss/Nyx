package com.nyx.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nyx.ai.NyxState
import com.nyx.ai.NyxViewModel
import com.nyx.ai.workbench.*

/** No WebView or external viewer: all previews are inert, selectable text. */
@Composable
fun WorkbenchScreen(state: NyxState, vm: NyxViewModel) {
    var goal by rememberSaveable { mutableStateOf("") }
    var filename by rememberSaveable { mutableStateOf("report.md") }
    var formatName by rememberSaveable { mutableStateOf(ArtifactFormat.MARKDOWN.name) }
    var approved by rememberSaveable { mutableStateOf(false) }
    var researchApproved by rememberSaveable { mutableStateOf(false) }
    var urls by rememberSaveable { mutableStateOf("") }
    var validationMessage by rememberSaveable { mutableStateOf("") }
    var retryId by rememberSaveable { mutableStateOf<String?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importWorkbenchDocument(uri)
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.saveWorkbenchExport(uri)
    }
    val selected = state.objectives.firstOrNull { it.id == state.selectedObjectiveId }
    val manifest = state.workbenchManifest
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Objective Workbench", style = MaterialTheme.typography.titleLarge)
            Text("Create real, validated files in this objective's app-private storage. Planning, pause/retry checkpoints and resource limits use the existing NYX engine. Validation proves file structure and bytes—not factual correctness.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New artifact objective", style = MaterialTheme.typography.titleMedium)
                Text("Provider: ${state.selectedModel.ifBlank { "select a model in Models" }} · Agent: ${state.agents.firstOrNull { it.id == state.selectedAgentId }?.name ?: "none selected"}", style = MaterialTheme.typography.bodySmall)
                ExpandableDraftField(value = goal, onValueChange = { goal = it.take(6_000) }, label = "What should this file contain?", modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, maxCharacters = 6_000)
                OutlinedTextField(filename, { filename = it.take(80) }, label = { Text("Required output filename") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Format (PDF/browser execution deferred)", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(ArtifactFormat.MARKDOWN, ArtifactFormat.TEXT, ArtifactFormat.CSV, ArtifactFormat.JSON, ArtifactFormat.HTML, ArtifactFormat.ZIP).chunked(3).forEach { formats ->
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            formats.forEach { format ->
                                FilterChip(selected = formatName == format.name, onClick = {
                                    formatName = format.name
                                    filename = "${filename.substringBeforeLast('.').ifBlank { "report" }}.${format.extension}"
                                }, label = { Text(format.extension.uppercase()) })
                            }
                        }
                    }
                }
                Text("Static HTML has no scripts, active links, or external resources. ZIP bundles include only selected verified artifacts, an artifact-only manifest and README; never imported documents or research source files.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { importPicker.launch(arrayOf("text/plain", "text/markdown", "text/csv", "application/json")) }, enabled = !state.workbenchImporting && !state.objectiveRunning) {
                    Text(if (state.workbenchImporting) "Importing…" else "Select a text document for this objective")
                }
                if (state.workbenchDraftImportName.isNotBlank()) {
                    Text("Selected: ${state.workbenchDraftImportName} · untrusted input", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::clearWorkbenchImport) { Text("Remove selected import") }
                }
                Row { Checkbox(approved, { approved = it }); Text("Authorize private artifact tools for this new objective only", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
                Text("This does not authorize Termux, arbitrary device files, external authentication, uploads or publishing. Save/share require your separate tap and re-check the artifact's digest.", style = MaterialTheme.typography.bodySmall)
                Row { Checkbox(researchApproved, { researchApproved = it }); Text("Also permit research of only the selected HTTPS URLs", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
                if (researchApproved) {
                    ExpandableDraftField(value = urls, onValueChange = { urls = it.take(4_096) }, label = "Exact authorized HTTPS URLs, one per line", modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, maxCharacters = 4_096)
                    Text("Existing network policy still applies (${state.autonomyMode.name.lowercase()} mode). This checkbox never grants Internet/LAN authority or changes autonomy mode. Redirects must already be selected; discovered links are not followed. No login, browser sessions or external auth.", style = MaterialTheme.typography.bodySmall)
                }
                if (validationMessage.isNotBlank()) Text(validationMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    val approval = WorkbenchAuthorization(enabled = approved, researchEnabled = researchApproved,
                        authorizedUrls = if (researchApproved) urls.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList() else emptyList(),
                        requiredFiles = mapOf(filename.trim() to ArtifactFormat.valueOf(formatName)))
                    runCatching { approval.validate() }.fold(onSuccess = {
                        validationMessage = ""
                        vm.createObjective(goal, workbench = it)
                        approved = false; researchApproved = false
                    }, onFailure = { validationMessage = it.message ?: "Check the selected filename and URLs." })
                }, enabled = approved && goal.isNotBlank() && !state.objectiveRunning && !state.workbenchImporting, modifier = Modifier.fillMaxWidth()) {
                    Text("Approve and start file objective")
                }
            } }
        }
        if (state.workbenchMessage.isNotBlank()) item { Text(state.workbenchMessage, style = MaterialTheme.typography.bodySmall) }
        item {
            Text("Previous objectives — select to reopen", style = MaterialTheme.typography.titleMedium)
            Text("Reopening reads persisted manifests and re-verifies registered files. It does not resume execution or expand approval.", style = MaterialTheme.typography.bodySmall)
        }
        items(state.objectives.take(40), key = { "objective-${it.id}" }) { objective ->
            OutlinedButton(onClick = { vm.selectObjective(objective.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text((if (objective.id == selected?.id) "Selected · " else "") + objective.title)
                    Text(objective.status.replace('_', ' '), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (selected != null) item {
            Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected.title, style = MaterialTheme.typography.titleMedium)
                Text("Status: ${selected.status} · ${state.objectiveTasks.count { it.status == "completed" }}/${state.objectiveTasks.size} verified tasks", style = MaterialTheme.typography.bodySmall)
                selected.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.workbenchApproval.enabled) {
                    Text("Approved required files: ${state.workbenchApproval.requiredFiles.keys.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    Text("Research ${if (state.workbenchApproval.researchEnabled) "limited to ${state.workbenchApproval.authorizedUrls.size} selected URL(s)" else "disabled"}. Approval is preserved across resume.", style = MaterialTheme.typography.bodySmall)
                    if (state.workbenchApproval.researchEnabled) state.workbenchApproval.authorizedUrls.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                } else Text("This objective has no Workbench artifact approval. Its assistant prose is not an exportable file.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = vm::refreshWorkbench) { Text("Verify/reload") }
                    if (state.objectiveRunning) OutlinedButton(onClick = vm::pauseObjective) { Text("Pause") }
                    else if (selected.status in setOf("paused", "needs_attention", "failed")) OutlinedButton(onClick = { retryId = selected.id }) { Text("Review resume") }
                }
            } }
        }
        if (manifest != null) {
            item { Text("Verified artifact files", style = MaterialTheme.typography.titleMedium) }
            if (manifest.artifacts.isEmpty()) item { Text("No verified output files yet. A model's completion claim does not create a file.", style = MaterialTheme.typography.bodySmall) }
            items(manifest.artifacts, key = { "artifact-${it.id}" }) { artifact ->
                Card { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(artifact.fileName, style = MaterialTheme.typography.titleSmall)
                    Text("${artifact.status.name} · ${artifact.bytes} bytes · ${artifact.format.mimeType}", style = MaterialTheme.typography.bodySmall)
                    Text("SHA-256 ${artifact.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    Text(artifact.validationNote, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.previewWorkbenchObject("artifact", artifact.id, artifact.fileName) }) { Text("Preview") }
                        TextButton(onClick = { vm.prepareWorkbenchExport(artifact)?.let { savePicker.launch(it) } }, enabled = artifact.status == ArtifactStatus.VERIFIED) { Text("Save as…") }
                        TextButton(onClick = { vm.shareWorkbenchArtifact(artifact) }, enabled = artifact.status == ArtifactStatus.VERIFIED) { Text("Share") }
                    }
                } }
            }
            item { Text("Selected imports (untrusted; not exportable)", style = MaterialTheme.typography.titleMedium) }
            items(manifest.imports, key = { "import-${it.id}" }) { imported ->
                OutlinedButton(onClick = { vm.previewWorkbenchObject("import", imported.id, imported.fileName) }) { Text("${imported.fileName} · ${imported.bytes} bytes") }
            }
            item { Text("Research sources (untrusted; not exportable)", style = MaterialTheme.typography.titleMedium) }
            items(manifest.sources, key = { "source-${it.id}-${it.url}" }) { source ->
                Card { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(source.title.ifBlank { source.url }, style = MaterialTheme.typography.titleSmall)
                    Text(source.url, style = MaterialTheme.typography.bodySmall)
                    if (source.finalUrl != source.url) Text("Final: ${source.finalUrl}", style = MaterialTheme.typography.bodySmall)
                    Text("${source.status} · ${source.bytes} bytes · fetched ${source.fetchedAt}", style = MaterialTheme.typography.bodySmall)
                    Text(source.note, style = MaterialTheme.typography.bodySmall)
                    if (source.sha256.isNotBlank()) Text("SHA-256 ${source.sha256}", style = MaterialTheme.typography.labelSmall)
                    if (source.status == "FETCHED") TextButton(onClick = { vm.previewWorkbenchObject("source", source.id, source.title.ifBlank { source.url }) }) { Text("Read source text") }
                } }
            }
        }
    }
    if (retryId != null) AlertDialog(onDismissRequest = { retryId = null }, title = { Text("Review before resuming") },
        text = { Text("Tools may already have written files. Resume explicitly reviews/retries uncertain tasks under the same Workbench file/URL approval; it does not authorize new URLs or overwrite existing conflicting artifacts. A non-tool-capable model may remain blocked.") },
        confirmButton = { TextButton(onClick = { retryId?.let { vm.resumeObjective(it, true) }; retryId = null }) { Text("Approve resume") } },
        dismissButton = { TextButton(onClick = { retryId = null }) { Text("Cancel") } })
    if (state.workbenchPreviewText.isNotBlank()) AlertDialog(onDismissRequest = vm::closeWorkbenchPreview,
        title = { Text(state.workbenchPreviewTitle) },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp)) { item { SelectionContainer { Text(state.workbenchPreviewText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } } } },
        confirmButton = { TextButton(onClick = vm::closeWorkbenchPreview) { Text("Close read-only preview") } })
}

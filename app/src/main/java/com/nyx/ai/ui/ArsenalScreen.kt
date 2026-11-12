package com.nyx.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyx.ai.NyxState
import com.nyx.ai.NyxViewModel
import com.nyx.ai.skills.ArsenalSkill
import com.nyx.ai.skills.SkillOrigin

private val ASurface = Color(0xFF12121A)
private val ALine = Color(0xFF2B2936)
private val AViolet = Color(0xFFA78BFA)
private val AMuted = Color(0xFFA5A2B0)
private val AText = Color(0xFFEDEAF5)

@Composable
fun ArsenalScreen(state: NyxState, vm: NyxViewModel) {
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var configTarget by remember { mutableStateOf<ArsenalSkill?>(null) }
    var configKey by remember { mutableStateOf("") }
    var configValue by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ARSENAL", color = AViolet, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                Text("Offline operational skills. Only relevant procedures enter a task prompt.", color = AMuted, fontSize = 11.sp)
            }
            OutlinedButton(onClick = { vm.refreshArsenal() }) { Text("Refresh") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { importText = ""; showImport = true }) { Text("Import") }
        }
        if (state.arsenalMessage.isNotBlank()) {
            Text(state.arsenalMessage, color = if (state.arsenalMessage.contains("fail", true) || state.arsenalMessage.contains("reject", true)) Color(0xFFFF8E9B) else AMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("Built-in skills are trusted and versioned with the app. Imported JSON is untrusted: unknown tools, writes, terminal authority, and duplicate IDs are rejected. Enabling a skill never bypasses the autonomy policy.", color = AMuted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.arsenalSkills, key = { it.id }) { skill ->
                SkillCard(
                    skill, expanded == skill.id,
                    onToggleExpand = { expanded = if (expanded == skill.id) null else skill.id },
                    onEnabled = { vm.setArsenalEnabled(skill.id, it) },
                    onConfigure = { configTarget = skill; configKey = ""; configValue = "" },
                    onRemove = { vm.removeArsenal(skill.id) },
                )
            }
            if (state.arsenalSkills.isEmpty()) item { Text("No skills loaded. Tap Refresh or import a manifest.", color = AMuted, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
        }
    }
    if (showImport) AlertDialog(
        onDismissRequest = { showImport = false },
        containerColor = ASurface,
        title = { Text("Import a skill manifest", color = AText) },
        text = {
            Column(Modifier.height(320.dp)) {
                Text("Paste a bounded JSON manifest. The import is validated as untrusted and cannot claim write, terminal, build, or test tools.", color = AMuted, fontSize = 11.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(importText, { importText = it }, Modifier.fillMaxSize(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), placeholder = { Text("{\"id\":\"...\",\"version\":\"1.0.0\"...}") })
            }
        },
        confirmButton = { TextButton(onClick = { vm.importArsenal(importText); showImport = false }) { Text("Validate & import") } },
        dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } },
    )
    configTarget?.let { skill ->
        AlertDialog(
            onDismissRequest = { configTarget = null },
            containerColor = ASurface,
            title = { Text("Configure ${skill.id}", color = AText) },
            text = {
                Column {
                    Text("Configuration is validated against the skill's declared constraints. It never grants new tools.", color = AMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(configKey, { configKey = it }, label = { Text("Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(configValue, { configValue = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (skill.config.isNotEmpty()) Text(skill.config.entries.joinToString("\n") { "${it.key} = ${it.value}" }, color = AMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.configureArsenal(skill.id, configKey, configValue); configTarget = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { configTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SkillCard(skill: ArsenalSkill, expanded: Boolean, onToggleExpand: () -> Unit, onEnabled: (Boolean) -> Unit, onConfigure: () -> Unit, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = ASurface), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (skill.enabled) Color(0xFF3A2D55) else ALine)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(skill.id.replace('-', ' ').replaceFirstChar { it.uppercase() }, color = AText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("${skill.id} · v${skill.version} · ${if (skill.origin == SkillOrigin.TRUSTED_BUILTIN) "trusted built-in" else "untrusted import"}", color = AMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = skill.enabled, onCheckedChange = onEnabled)
            }
            Text(skill.manifest.description, color = AMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TOOLS  ${skill.manifest.tools.joinToString().ifBlank { "none" }}", color = AViolet, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onToggleExpand) { Text(if (expanded) "Hide procedure" else "Procedure", fontSize = 11.sp) }
                TextButton(onClick = onConfigure) { Text("Configure", fontSize = 11.sp) }
            }
            if (expanded) {
                Text(skill.manifest.instructions.joinToString("\n"), color = AText, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.fillMaxWidth().height(190.dp).background(Color(0xFF0B0B10), RoundedCornerShape(8.dp)).padding(9.dp).verticalScroll(rememberScrollState()))
                if (skill.origin == SkillOrigin.UNTRUSTED_IMPORT) TextButton(onClick = onRemove) { Text("Remove import", color = Color(0xFFFF8E9B), fontSize = 11.sp) }
            }
        }
    }
}

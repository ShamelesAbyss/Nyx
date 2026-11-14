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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyx.ai.NyxState
import com.nyx.ai.NyxViewModel
import com.nyx.ai.gateway.GatewayHealth
import com.nyx.ai.gateway.GatewayKind
import com.nyx.ai.gateway.GatewayServerConfig
import com.nyx.ai.gateway.GatewayTool

private val GSurface = Color(0xFF12121A)
private val GLine = Color(0xFF2B2936)
private val GViolet = Color(0xFFA78BFA)
private val GMuted = Color(0xFFA5A2B0)
private val GText = Color(0xFFEDEAF5)
private val GGreen = Color(0xFF72D6A3)
private val GRed = Color(0xFFFF8E9B)

@Composable
fun GatewaysScreen(state: NyxState, vm: NyxViewModel) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = state.gatewayServers.firstOrNull { it.id == editingId }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var invokeServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var outputOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GATEWAYS", color = GViolet, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                Text("Real MCP HTTP and GitHub tools, with explicit server allowlists.", color = GMuted, fontSize = 11.sp)
            }
            OutlinedButton(onClick = { vm.refreshGateways() }) { Text("Refresh") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { editingId = null; showCreate = true }) { Text("Add") }
        }
        if (state.gatewayMessage.isNotBlank()) Text(state.gatewayMessage, color = if (state.gatewayMessage.contains("fail", true) || state.gatewayMessage.contains("denied", true)) GRed else GMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        Text("Tokens are stored only in Android Keystore-backed storage; only the opaque reference is saved here. GitHub writes are distinct tools and still pass the autonomy policy.", color = GMuted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.gatewayServers, key = { it.id }) { server ->
                GatewayCard(
                    server = server,
                    health = state.gatewayHealth[server.id],
                    toolCount = state.gatewayTools[server.id]?.size,
                    onToggle = { vm.setGatewayEnabled(server.id, it) },
                    onCheck = { vm.checkGateway(server.id) },
                    onTools = { invokeServerId = server.id },
                    onEdit = { editingId = server.id; showCreate = true },
                    onDelete = { vm.deleteGateway(server.id) },
                )
            }
            if (state.gatewayServers.isEmpty()) item { Text("No gateways configured. Add a streamable-HTTP MCP server or a GitHub token gateway.", color = GMuted, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
            if (state.gatewayOutput.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0B10)), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("LAST TOOL RESULT", color = GViolet, fontSize = 9.sp, letterSpacing = 0.8.sp)
                        Text(state.gatewayOutput, color = GText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = if (outputOpen) 80 else 6, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { outputOpen = !outputOpen }) { Text(if (outputOpen) "Collapse" else "Expand", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
    // Wait for a restored ID to resolve instead of silently opening a new-server editor.
    if (showCreate && (editingId == null || editing != null)) GatewayEditor(initial = editing, onDismiss = { showCreate = false; editingId = null }, onSave = { id, name, kind, endpoint, token, allow, enabled ->
        vm.saveGateway(id, name, kind, endpoint, token, allow, enabled)
        showCreate = false; editingId = null
    })
    state.gatewayServers.firstOrNull { it.id == invokeServerId }?.let { server ->
        ToolPicker(server, state.gatewayTools[server.id].orEmpty(), onDismiss = { invokeServerId = null }, onCall = { tool, args -> vm.callGateway(server.id, tool, args); invokeServerId = null })
    }
}

@Composable
private fun GatewayCard(server: GatewayServerConfig, health: GatewayHealth?, toolCount: Int?, onToggle: (Boolean) -> Unit, onCheck: () -> Unit, onTools: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val stateText = health?.state?.name ?: "NOT CHECKED"
    val accent = when (health?.state?.name) { "HEALTHY" -> GGreen; "UNREACHABLE", "MISCONFIGURED" -> GRed; else -> GMuted }
    Card(colors = CardDefaults.cardColors(containerColor = GSurface), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, GLine)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(server.name, color = GText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("${server.kind.name} · ${server.endpoint}", color = GMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = server.enabled, onCheckedChange = onToggle)
            }
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stateText, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Spacer(Modifier.weight(1f))
                Text("${toolCount ?: 0} tools · allowlist ${server.allowedTools.size}", color = GMuted, fontSize = 9.sp)
            }
            if (!health?.detail.isNullOrBlank()) Text(health.detail, color = GMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onCheck) { Text("Check", fontSize = 11.sp) }
                TextButton(onClick = onTools) { Text("Tools / call", fontSize = 11.sp) }
                TextButton(onClick = onEdit) { Text("Edit", fontSize = 11.sp) }
                TextButton(onClick = onDelete) { Text("Delete", color = GRed, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun GatewayEditor(initial: GatewayServerConfig?, onDismiss: () -> Unit, onSave: (String?, String, GatewayKind, String, String, String, Boolean) -> Unit) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var kind by rememberSaveable(initial?.id) { mutableStateOf(initial?.kind ?: GatewayKind.MCP_HTTP) }
    var endpoint by rememberSaveable(initial?.id) { mutableStateOf(initial?.endpoint ?: "") }
    // Credential drafts are deliberately memory-only, never part of saved instance state.
    var token by remember { mutableStateOf("") }
    var allow by rememberSaveable(initial?.id) { mutableStateOf(initial?.allowedTools?.joinToString(", ") ?: "") }
    var enabled by rememberSaveable(initial?.id) { mutableStateOf(initial?.enabled ?: true) }
    var kindMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = GSurface,
        title = { Text(if (initial == null) "Add gateway" else "Edit gateway", color = GText) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { kindMenu = true }) { Text(kind.name) }
                    DropdownMenu(kindMenu, onDismissRequest = { kindMenu = false }) {
                        GatewayKind.entries.forEach { option -> DropdownMenuItem(text = { Text(option.name) }, onClick = { kind = option; kindMenu = false }) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text(if (kind == GatewayKind.GITHUB) "API endpoint" else "MCP HTTP endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(token, { token = it }, label = { Text(if (initial == null) "Token (optional for MCP)" else "Replace token (leave blank to keep)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(allow, { allow = it }, label = { Text("Tool allowlist (comma separated; blank = safe GitHub read-only default)") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", color = GMuted, fontSize = 12.sp, modifier = Modifier.weight(1f)); Switch(enabled, { enabled = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(initial?.id, name, kind, endpoint, token, allow, enabled) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ToolPicker(server: GatewayServerConfig, tools: List<GatewayTool>, onDismiss: () -> Unit, onCall: (String, String) -> Unit) {
    var selected by rememberSaveable(server.id) { mutableStateOf(tools.firstOrNull()?.name ?: "") }
    var menu by remember { mutableStateOf(false) }
    var args by rememberSaveable(server.id) { mutableStateOf("{}") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = GSurface,
        title = { Text(server.name, color = GText) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (tools.isEmpty()) Text("Check the gateway first. No allowed tools were returned.", color = GMuted, fontSize = 12.sp)
                else {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(selected.ifBlank { "Choose a tool" }, maxLines = 1) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        tools.forEach { tool -> DropdownMenuItem(text = { Text("${tool.name} · ${tool.description.take(50)}") }, onClick = { selected = tool.name; menu = false }) }
                    }
                    Spacer(Modifier.height(7.dp))
                    ExpandableDraftField(args, { args = it }, label = "Arguments (JSON object)", textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), minLines = 4, modifier = Modifier.fillMaxWidth())
                    val schemaName = tools.firstOrNull { it.name == selected }?.inputSchemaJson.orEmpty()
                    if (schemaName.isNotBlank()) Text(schemaName.take(500), color = GMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (selected.isNotBlank()) onCall(selected, args) }, enabled = selected.isNotBlank()) { Text("Call tool") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

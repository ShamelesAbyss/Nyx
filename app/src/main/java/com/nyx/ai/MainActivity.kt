package com.nyx.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyx.ai.terminal.TerminalEnvironment
import com.nyx.ai.terminal.CapabilityState
import com.nyx.ai.terminal.WorkspaceCheck
import com.nyx.ai.terminal.ExecutableOrigin
import com.nyx.ai.terminal.ProjectKind
import com.nyx.ai.autonomy.PreflightResult
import com.nyx.ai.autonomy.policy.AutonomyMode
import com.nyx.ai.nativeinference.NativePhase
import com.nyx.ai.network.InferenceFabric
import com.nyx.ai.settings.MemoryTelemetry
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyx.ai.data.AgentEntity
import com.nyx.ai.data.ConversationEntity
import com.nyx.ai.data.MessageEntity
import com.nyx.ai.network.LocalModel
import com.nyx.ai.settings.formatInstalledAppVersion
import com.nyx.ai.settings.installedAppVersion
import java.util.Locale

private val Obsidian = Color(0xFF09090D)
private val Surface1 = Color(0xFF12121A)
private val Surface2 = Color(0xFF1A1A25)
private val Violet = Color(0xFFA78BFA)
private val VioletSoft = Color(0xFFC4B5FD)
private val TextMain = Color(0xFFF2F0F8)
private val TextMuted = Color(0xFFA5A2B0)
private val Green = Color(0xFF72D6A3)
private val Red = Color(0xFFFF8E9B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NyxTheme { NyxApp() } }
    }
}

@Composable
private fun NyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Violet, onPrimary = Obsidian, secondary = VioletSoft,
        background = Obsidian, surface = Surface1, onSurface = TextMain,
        onBackground = TextMain, error = Red, outline = Color(0xFF393744)
    ), content = content)
}

@Composable
private fun NyxApp(vm: NyxViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.registerGguf(uri, context.displayName(uri))
    }
    val textDocumentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTextDocument(uri)
    }
    val destinations = listOf("Chat", "Models", "Agents", "Autonomy", "Workflows", "Settings")
    val icons = listOf(Icons.Rounded.ChatBubbleOutline, Icons.Rounded.Memory, Icons.Rounded.SmartToy, Icons.Rounded.PlayArrow, Icons.Rounded.AccountTree, Icons.Rounded.Settings)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            containerColor = Obsidian,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!expanded) NavigationBar(containerColor = Surface1, contentColor = TextMain) {
                    destinations.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icons[index], contentDescription = label) },
                            label = { Text(label, fontSize = 10.sp) },
                            alwaysShowLabel = false
                        )
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).background(Obsidian)) {
                if (expanded) NavigationRail(containerColor = Surface1, contentColor = TextMain) {
                    destinations.forEachIndexed { index, label ->
                        NavigationRailItem(
                            selected = tab == index, onClick = { tab = index },
                            icon = { Icon(icons[index], contentDescription = label) },
                            label = { Text(label, fontSize = 10.sp) }, alwaysShowLabel = true
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxSize().background(Obsidian)) {
                    AppHeader(tab = destinations[tab], connected = state.connected, model = state.selectedModel, endpoint = state.endpoint)
                    if (state.error.isNotBlank()) ErrorBanner(state.error, vm::clearError)
                    when (tab) {
                        0 -> ChatScreen(state, vm)
                        1 -> ModelsScreen(state, vm) { picker.launch(arrayOf("*/*")) }
                        2 -> AgentsScreen(state, vm) { textDocumentPicker.launch(arrayOf("text/*", "application/json")) }
                        3 -> AutonomyScreen(state, vm)
                        4 -> WorkflowScreen(state, vm)
                        else -> SettingsScreen(state, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun AutonomyScreen(state: NyxState, vm: NyxViewModel) {
    var mode by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (mode == 0) {
                Button(onClick = { mode = 0 }, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian)) { Text("Objectives") }
            } else {
                OutlinedButton(onClick = { mode = 0 }) { Text("Objectives") }
            }
            if (mode == 1) {
                Button(onClick = { mode = 1 }, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian)) { Text("Terminal") }
            } else {
                OutlinedButton(onClick = { mode = 1 }) { Text("Terminal") }
            }
        }
        if (mode == 0) ObjectivesPanel(state, vm) else TerminalPanel(state, vm)
    }
}

@Composable
private fun TerminalPanel(state: NyxState, vm: NyxViewModel) {
    var command by rememberSaveable { mutableStateOf("") }
    var workspace by rememberSaveable { mutableStateOf(state.terminalWorkspace) }
    var projectName by rememberSaveable { mutableStateOf("nyx-app") }
    val profile = state.terminalProfile
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshTerminalEnvironment() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionIntro(
            "Terminal bridge",
            "Run real commands through Termux. Nothing is simulated: stdout, stderr, and exit codes come from the Termux RUN_COMMAND callback. Destructive and privileged commands are blocked; every manually entered command requires exact approval. A risk classifier is not a shell sandbox."
        )
        Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { permission.launch("com.termux.permission.RUN_COMMAND") }) { Text("Grant Termux command permission") }
                Text("TERMUX STATUS", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                val readiness = profile
                Text(
                    when {
                        readiness == null -> "Not checked yet."
                        readiness.hostAvailable -> "Host reachable${readiness.uname?.let { " · ${it.take(48)}" }.orEmpty()}"
                        readiness.detail != null -> readiness.detail.orEmpty()
                        else -> "Termux is unavailable."
                    },
                    color = if (readiness?.hostAvailable == true) Green else TextMuted,
                    fontSize = 12.sp
                )
                if (readiness != null) {
                    Text("HOME: ${readiness.home ?: "unknown"}", color = TextMuted, fontSize = 11.sp)
                    Text("PREFIX: ${readiness.prefix ?: "unknown"}", color = TextMuted, fontSize = 11.sp)
                    Text("proot-distro: ${readiness.prootDistroPath ?: "not found"}", color = TextMuted, fontSize = 11.sp)
                    Text("Installed distros: ${readiness.distros.ifEmpty { listOf("none detected") }.joinToString()}", color = TextMuted, fontSize = 11.sp)
                    Text("timeout binary: ${if (readiness.timeoutAvailable) "available (hard timeouts)" else "missing (waits are bounded, process is not killed)"}", color = TextMuted, fontSize = 11.sp)
                    Text("Tools: ${readiness.tools.keys.sorted().joinToString().ifBlank { "none detected" }}", color = TextMuted, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::refreshTerminalEnvironment, enabled = !state.terminalDiscovering) {
                        Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text(if (state.terminalDiscovering) "Checking…" else "Detect environment")
                    }
                    OutlinedButton(onClick = { vm.setTerminalEnvironment(TerminalEnvironment.TERMUX_HOST) }) { Text("Host") }
                    OutlinedButton(onClick = { vm.setTerminalEnvironment(TerminalEnvironment.DEBIAN_PROOT) }) { Text("Debian") }
                }
                Text("Selected environment: ${state.terminalEnvironment.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}", color = VioletSoft, fontSize = 11.sp)
                val verifiedGuests = profile?.verifiedDistros.orEmpty()
                if (verifiedGuests.isNotEmpty()) {
                    Text("VERIFIED GUESTS", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        verifiedGuests.forEach { alias ->
                            val selected = state.guestAlias.equals(alias, ignoreCase = true)
                            OutlinedButton(
                                onClick = {
                                    vm.setGuestAlias(alias)
                                    vm.setTerminalEnvironment(TerminalEnvironment.DEBIAN_PROOT)
                                },
                                colors = if (selected) ButtonDefaults.outlinedButtonColors(contentColor = Violet) else ButtonDefaults.outlinedButtonColors()
                            ) { Text(if (selected) "$alias · selected" else alias) }
                        }
                    }
                }
                if (profile?.nestedProot == true) {
                    Text("Nested PRoot: ${profile.detail.orEmpty()}", color = Color(0xFFF2C879), fontSize = 11.sp)
                }
                if (profile != null && profile.listError != null && profile.distros.isEmpty()) {
                    Text("Distro list error: ${profile.listError}", color = Color(0xFFF2C879), fontSize = 11.sp)
                }
                profile?.guest(state.guestAlias)?.let { guest ->
                    Text(
                        "Guest ${state.guestAlias}: HOME ${guest.home ?: "unknown"} · ${guest.osName ?: "unknown OS"} · uid ${guest.uid ?: "?"}",
                        color = TextMuted, fontSize = 11.sp
                    )
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("ENVIRONMENT CAPABILITIES", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                    TextButton(onClick = vm::refreshEnvironmentCapabilities) { Text("Refresh", color = VioletSoft, fontSize = 11.sp) }
                }
                val capabilities = state.environmentCapabilities
                if (capabilities == null) {
                    Text("Not probed yet. Detect the environment, then refresh capabilities.", color = TextMuted, fontSize = 11.sp)
                } else {
                    val verifiedCount = capabilities.tools.count { it.value.state == CapabilityState.VERIFIED }
                    Text(
                        "${capabilities.kind} · $verifiedCount verified · ${capabilities.tools.size} checked",
                        color = TextMain, fontSize = 11.sp
                    )
                    capabilities.tools.entries.sortedBy { it.key }.take(14).forEach { (name, tool) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = TextMain, fontSize = 11.sp, modifier = Modifier.width(78.dp))
                            Text(
                                tool.state.name.replace('_', ' ').lowercase(),
                                color = when (tool.state) {
                                    CapabilityState.VERIFIED -> Green
                                    CapabilityState.MISSING, CapabilityState.INCOMPATIBLE -> Color(0xFFF2C879)
                                    CapabilityState.PERMISSION_DENIED -> Color(0xFFF28B82)
                                    CapabilityState.NOT_CHECKED -> TextMuted
                                },
                                fontSize = 10.sp,
                                modifier = Modifier.width(92.dp)
                            )
                            Text(tool.version ?: tool.path ?: "", color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (capabilities.kind.startsWith("proot:") && tool.origin == ExecutableOrigin.GUEST_INHERITED_TERMUX) {
                                Text(" · inherited", color = Color(0xFFF2C879), fontSize = 9.sp)
                            }
                        }
                    }
                    capabilities.storageFreeBytes?.let { bytes ->
                        Text("Free storage: ${bytes / (1024L * 1024L)} MB", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("COMMAND", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it.take(8_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ls -la ~/project") },
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = { vm.requestTerminalCommand(command) },
                    enabled = command.isNotBlank() && !state.terminalRunning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian)
                ) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text(if (state.terminalRunning) "Running…" else "Run command")
                }
                if (state.terminalMessage.isNotBlank()) Text(state.terminalMessage, color = TextMuted, fontSize = 11.sp)
            }
        }
        if (state.terminalPendingCommand != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2233)), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("APPROVAL REQUIRED", color = Color(0xFFF2C879), fontSize = 10.sp, letterSpacing = 1.sp)
                    Text(state.terminalPendingCommand.orEmpty(), color = TextMain, fontSize = 12.sp)
                    Text("This command can modify files or the environment. Approve only if you intend this exact command.", color = TextMuted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.confirmTerminalCommand(); command = "" }, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian)) { Text("Approve and run") }
                        OutlinedButton(onClick = vm::dismissTerminalCommand) { Text("Cancel") }
                    }
                }
            }
        }
        if (state.terminalOutput.isNotBlank()) OutputCard("Terminal output", state.terminalOutput)
        Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AUTONOMOUS WORKSPACE", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                Text("Enabling authorizes bounded source edits and execution of existing build/test scripts for objectives in this workspace. Only approve projects you trust: scripts can access other Termux files or the network. No raw model shell, installs, commits or publishing.", color = TextMuted, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.terminalToolsEnabled, onCheckedChange = vm::setTerminalToolsEnabled)
                    Text("Allow terminal tools for objectives", color = TextMain, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = workspace,
                    onValueChange = { workspace = it.take(4_096) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (state.terminalEnvironment == TerminalEnvironment.DEBIAN_PROOT) "Guest path, e.g. /root/nyx-scratch"
                            else "Workspace path in Termux, e.g. ~/projects/app"
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedButton(onClick = { vm.setTerminalWorkspace(workspace) }) { Text("Save workspace") }
                when (val check = state.workspaceCheck) {
                    is WorkspaceCheck.Valid -> Text("Verified in the selected environment: ${check.normalizedPath}", color = Green, fontSize = 11.sp)
                    is WorkspaceCheck.Invalid -> Text("${check.code}: ${check.message} ${check.action}", color = Color(0xFFF2C879), fontSize = 11.sp)
                    null -> Text("Workspace is not verified yet. Save it to run a real existence and write check.", color = TextMuted, fontSize = 11.sp)
                }
                if (state.terminalEnvironment == TerminalEnvironment.DEBIAN_PROOT) {
                    Text("Guest paths belong to the PRoot filesystem. /storage/emulated/0 and /sdcard are Android paths and are rejected here.", color = TextMuted, fontSize = 10.sp)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AUTONOMOUS PROVISIONING", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(
                    "Mode: ${state.autonomyMode.name.lowercase().replaceFirstChar { it.uppercase() }}${if (state.autonomyStopped) " · STOPPED" else ""}. " +
                        "Workspaces are created and write-verified in the selected environment; installs use only planner-generated package commands.",
                    color = if (state.autonomyStopped) Color(0xFFF28B82) else TextMuted,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New project name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.createWorkspace(projectName) },
                        enabled = !state.workspaceProvisioning,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (state.workspaceProvisioning) "Creating…" else "Create workspace", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { vm.prepareEnvironment(ProjectKind.KOTLIN_JVM) },
                        enabled = state.environmentCapabilities != null && !state.terminalDiscovering,
                        modifier = Modifier.weight(1f)
                    ) { Text("Prepare Kotlin env", fontSize = 11.sp) }
                }
                state.dependencyPlan?.let { plan ->
                    Text(
                        "Requirements: ${plan.requirements.joinToString { it.tool }}",
                        color = TextMuted, fontSize = 10.sp
                    )
                    if (plan.missing.isNotEmpty()) {
                        Text("Missing: ${plan.missing.joinToString { it.tool }}", color = Color(0xFFF2C879), fontSize = 10.sp)
                    }
                    plan.candidates.take(4).forEach { candidate ->
                        Text("${candidate.manager} · ${candidate.packageName}", color = TextMuted, fontSize = 10.sp)
                    }
                    plan.blockers.forEach { blocker -> Text(blocker, color = Color(0xFFF28B82), fontSize = 10.sp) }
                }
                if (state.pendingInstallApproval.isNotEmpty()) {
                    Text("INSTALL APPROVAL REQUIRED", color = Color(0xFFF2C879), fontSize = 10.sp, letterSpacing = 1.sp)
                    Text(
                        state.pendingInstallApproval.joinToString { "${it.manager} ${it.packageName}" },
                        color = TextMain, fontSize = 11.sp
                    )
                    Button(
                        onClick = vm::approveDependencies,
                        colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian)
                    ) { Text("Approve installation") }
                }
                state.installationReport?.let { report ->
                    Text(report.message, color = if (report.succeeded) Green else Color(0xFFF2C879), fontSize = 11.sp)
                    report.steps.takeLast(4).forEach { step ->
                        Text(
                            "${step.packageName}: ${step.status}${step.exitCode?.let { " · exit $it" } ?: ""}",
                            color = TextMuted, fontSize = 10.sp
                        )
                    }
                }
                if (state.policyMessage.isNotBlank()) Text(state.policyMessage, color = TextMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::emergencyStopAutonomy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D), contentColor = Color.White)
                    ) { Text("Emergency stop") }
                    if (state.autonomyStopped) OutlinedButton(onClick = vm::resumeAutonomy) { Text("Resume") }
                }
            }
        }
        if (state.terminalHistory.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("COMMAND HISTORY", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                TextButton(onClick = vm::clearTerminalHistory) { Text("Clear", color = VioletSoft, fontSize = 11.sp) }
            }
            state.terminalHistory.take(25).forEach { row ->
                Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.commandPath.take(200), color = TextMain, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${row.status.uppercase(Locale.ROOT)}${row.exitCode?.let { " · exit $it" } ?: ""} · ${row.environment.replace('_', ' ')}",
                            color = if (row.status == "succeeded") Green else if (row.status in setOf("denied", "failed", "timeout")) Color(0xFFF2C879) else TextMuted,
                            fontSize = 10.sp
                        )
                        if (row.stdout.isNotBlank()) Text(row.stdout.take(300), color = TextMuted, fontSize = 10.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Text(
            "Setup: install Termux, run `pkg install proot-distro` if you want Debian, add `allow-external-apps=true` to ~/.termux/termux.properties, restart Termux, and grant NYX the RUN_COMMAND permission in Android settings. Debian must be installed and tested separately with `proot-distro install debian`.",
            color = TextMuted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ObjectivesPanel(state: NyxState, vm: NyxViewModel) {
    var goal by rememberSaveable { mutableStateOf("") }
    var confirmResume by rememberSaveable { mutableStateOf(false) }
    var development by rememberSaveable { mutableStateOf(false) }
    val selected = state.objectives.firstOrNull { it.id == state.selectedObjectiveId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionIntro("Autonomy control center", "Create a goal, let NYX build a bounded dependency graph, and review each real agent/tool result. Execution runs in a foreground service; terminal tools are optional and off by default.")
        Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("NEW OBJECTIVE", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                OutlinedTextField(value = goal, onValueChange = { goal = it.take(8_000) }, modifier = Modifier.fillMaxWidth(), label = { Text("What should NYX accomplish?") }, minLines = 3, maxLines = 6, shape = RoundedCornerShape(14.dp))
                Text("Uses the selected agent, current model, and its allowlisted tools. Plans are validated, limited to ${com.nyx.ai.autonomy.ObjectivePlanValidator.MAX_TASKS} tasks, and scheduled by dependency and device resources.", color = TextMuted, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = development, onCheckedChange = { development = it }, enabled = state.terminalToolsEnabled)
                    Text("Use verified project repair loop", color = TextMain, fontSize = 12.sp)
                }
                if (development) Text("Uses the saved trusted workspace/environment, detected existing build/test commands and configured repair limits. Baseline passing does not implement a new feature. No model shell, installs or publishing.", color = TextMuted, fontSize = 11.sp)
                if (development) {
                    OutlinedButton(onClick = { vm.preflightDevelopment() }) { Text("Run preflight") }
                    state.preflight?.let { pre ->
                        Text(
                            if (pre.ready) "Preflight ready." else "Preflight blocked (${pre.blockers.size}).",
                            color = if (pre.ready) Green else Color(0xFFF2C879),
                            fontSize = 11.sp
                        )
                        pre.blockers.take(4).forEach { blocker ->
                            Text("${blocker.code}: ${blocker.message} ${blocker.action}", color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
                Button(onClick = { vm.createObjective(goal, development); goal = "" }, enabled = goal.isNotBlank() && !state.objectiveRunning, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text(if (state.objectiveRunning) "Objective running" else "Plan and execute")
                }
            }
        }
        if (state.objectives.isNotEmpty()) {
            Text("OBJECTIVES", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
            state.objectives.forEach { item ->
                val selectedCard = item.id == selected?.id
                Card(onClick = { vm.selectObjective(item.id) }, colors = CardDefaults.cardColors(containerColor = if (selectedCard) Surface2 else Surface1), shape = RoundedCornerShape(14.dp), border = if (selectedCard) BorderStroke(1.dp, Violet.copy(alpha = .55f)) else null) {
                    Column(Modifier.fillMaxWidth().padding(13.dp)) {
                        Text(item.title, color = TextMain, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${item.status.replace('_', ' ').uppercase(Locale.ROOT)} · ${item.modelId}", color = if (item.status == "completed") Green else TextMuted, fontSize = 10.sp)
                        if (!item.error.isNullOrBlank()) Text(item.error.orEmpty(), color = Color(0xFFF2C879), fontSize = 11.sp)
                    }
                }
            }
        }
        if (selected != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${selected.status.replace('_', ' ').uppercase(Locale.ROOT)} · ${state.objectiveTasks.count { it.status == "completed" }}/${state.objectiveTasks.size} tasks", color = VioletSoft, fontSize = 11.sp, letterSpacing = .5.sp)
                    Text(selected.goal, color = TextMain, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.objectiveRunning) OutlinedButton(onClick = vm::pauseObjective) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(4.dp)); Text("Pause") }
                        if (selected.status in setOf("paused", "needs_attention", "failed")) OutlinedButton(onClick = { confirmResume = true }, enabled = !state.objectiveRunning) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Review & resume") }
                        if (selected.status !in setOf("completed", "cancelled")) OutlinedButton(onClick = { vm.cancelObjective(selected.id) }) { Text("Cancel") }
                    }
                    if (state.objectiveRunning) Text("Running in a foreground service (${state.objectivePhase}). Android may still stop background work; checkpoints remain on-device and uncertain tasks are never replayed automatically.", color = TextMuted, fontSize = 11.sp)
                }
            }
            if (state.objectiveStreaming.isNotBlank()) OutputCard("Live model output", state.objectiveStreaming)
            if (state.objectiveTasks.isNotEmpty()) {
                Text("TASK GRAPH · DEPENDENCY-AWARE SCHEDULER", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                state.objectiveTasks.sortedBy { it.ordinal }.forEach { task ->
                    val deps = runCatching { org.json.JSONArray(task.dependsOnJson).let { array -> (0 until array.length()).joinToString { array.optString(it).take(8) } } }.getOrDefault("")
                    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${task.ordinal + 1}. ${task.title}", color = TextMain, fontWeight = FontWeight.Medium)
                            Text("${task.status.replace('_', ' ').uppercase(Locale.ROOT)} · ${task.workerKind} · attempt ${task.attemptCount}/${task.maxAttempts}${if (deps.isNotBlank()) " · needs $deps" else " · no prerequisites"}", color = if (task.status == "completed") Green else if (task.status in setOf("failed", "needs_review", "blocked")) Color(0xFFF2C879) else TextMuted, fontSize = 10.sp)
                            Text(task.instructions, color = TextMuted, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            if (!task.error.isNullOrBlank()) Text(task.error.orEmpty(), color = Color(0xFFF2C879), fontSize = 11.sp)
                            if (!task.outcome.isNullOrBlank()) Text(task.outcome.orEmpty(), color = TextMain, fontSize = 12.sp, maxLines = 10, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (state.objectiveArtifacts.isNotEmpty()) {
                Text("ARTIFACTS", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                state.objectiveArtifacts.forEach { artifact -> OutputCard(artifact.name, artifact.content) }
            }
            if (state.objectiveEvents.isNotEmpty()) {
                Text("PERSISTED EXECUTION TRACE", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        state.objectiveEvents.takeLast(30).forEach { event ->
                            Text("${event.kind.uppercase(Locale.ROOT)} · ${event.detail}", color = if (event.kind.contains("failed") || event.kind.contains("error")) Color(0xFFF2C879) else TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
            Text("Completion requires locally checked evidence: exit codes, expected output, file existence/content, or checksums. Inference-only tasks establish output existence, not semantic correctness; prose never proves tool completion. Failed or interrupted actions require explicit review before retry.", color = TextMuted, fontSize = 10.sp)
        }
    }
    if (confirmResume && selected != null) AlertDialog(
        onDismissRequest = { confirmResume = false },
        title = { Text("Review before resuming") },
        text = { Text("A failed or interrupted task may already have changed local memory or workspace files. NYX will not automatically retry it. Proceed only after reviewing its trace and deciding a repeat is safe.") },
        confirmButton = { TextButton(onClick = { confirmResume = false; vm.resumeObjective(selected.id, userApprovedRetry = true) }) { Text("I reviewed · resume") } },
        dismissButton = { TextButton(onClick = { confirmResume = false }) { Text("Not now") } }
    )
}

@Composable
private fun AppHeader(tab: String, connected: Boolean, model: String, endpoint: String) {
    val host = Uri.parse(endpoint).host?.lowercase().orEmpty()
    val onDevice = endpoint == "native://local" || host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = painterResource(R.drawable.nyx_launcher), contentDescription = "NYX brand mark", modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("NYX", fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = TextMain)
            Text(tab.uppercase(Locale.ROOT), fontSize = 10.sp, color = TextMuted, letterSpacing = 1.4.sp)
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Green else TextMuted))
        Spacer(Modifier.width(7.dp))
        Text(
            if (!connected) "OFFLINE" else "${if (onDevice) "ON DEVICE" else "REMOTE"} · ${model.ifBlank { "ONLINE" }}",
            fontSize = 10.sp, color = if (!connected) TextMuted else if (onDevice) Green else Color(0xFFF2C879), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
    Divider(color = Surface2, thickness = 1.dp)
}

@Composable
private fun ErrorBanner(text: String, dismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF321D28)), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = Color(0xFFFFCDD3), fontSize = 13.sp)
            TextButton(onClick = dismiss) { Text("OK", color = VioletSoft) }
        }
    }
}

@Composable
private fun ChatScreen(state: NyxState, vm: NyxViewModel) {
    var draft by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 780.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) HistoryPanel(state.conversations, state.activeConversationId, vm, Modifier.width(250.dp).fillMaxSize())
            Column(Modifier.weight(1f).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.newConversation() }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New chat") }
                    if (!wide) OutlinedButton(onClick = { showHistory = true }) { Icon(Icons.Rounded.History, null); Spacer(Modifier.width(5.dp)); Text("History") }
                    ModelSelector(state.models, state.selectedModel, vm)
                    if (state.messages.any { it.role == "assistant" } && !state.isGenerating) {
                        IconButton(onClick = vm::regenerateLast) { Icon(Icons.Rounded.Refresh, "Regenerate response", tint = VioletSoft) }
                    }
                }
                if (!state.connected) OnboardingHint()
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), reverseLayout = false, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                    if (state.streamingText.isNotEmpty()) item(key = "streaming") { StreamingBubble(state.streamingText) }
                }
                Composer(draft, onDraft = { draft = it }, generating = state.isGenerating, enabled = state.connected && state.selectedModel.isNotBlank(), onSend = { vm.sendMessage(draft); draft = "" }, onStop = vm::stopGeneration)
            }
        }
    }
    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false },
        containerColor = Surface1,
        title = { Text("Conversation history", color = TextMain) },
        text = { HistoryPanel(state.conversations, state.activeConversationId, vm, Modifier.heightIn(max = 440.dp).fillMaxWidth(), onSelected = { showHistory = false }) },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
    )
}

@Composable
private fun HistoryPanel(items: List<ConversationEntity>, activeId: String?, vm: NyxViewModel, modifier: Modifier = Modifier, onSelected: (() -> Unit)? = null) {
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    Column(modifier.padding(10.dp)) {
        Text("RECENT CHATS", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(7.dp))
        if (items.isEmpty()) Text("Your chats are stored only on this device.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(items, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (item.id == activeId) Surface2 else Color.Transparent).padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.openConversation(item.id); onSelected?.invoke() }, modifier = Modifier.weight(1f)) {
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextMain, fontSize = 12.sp)
                    }
                    IconButton(onClick = { renameTarget = item; renameText = item.title }) { Icon(Icons.Rounded.Edit, "Rename conversation", tint = TextMuted, modifier = Modifier.size(17.dp)) }
                    IconButton(onClick = { vm.deleteConversation(item) }) { Icon(Icons.Rounded.DeleteOutline, "Delete conversation", tint = TextMuted, modifier = Modifier.size(18.dp)) }
                }
            }
        }
        renameTarget?.let { item ->
            AlertDialog(
                onDismissRequest = { renameTarget = null }, containerColor = Surface1,
                title = { Text("Rename conversation", color = TextMain) },
                text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("Title") }) },
                confirmButton = { TextButton(onClick = { vm.renameConversation(item, renameText); renameTarget = null }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun ModelSelector(models: List<LocalModel>, selected: String, vm: NyxViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = models.isNotEmpty()) {
            Text(selected.ifBlank { "Model" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Surface1)) {
            models.forEach { model -> DropdownMenuItem(text = { Text(model.name) }, onClick = { vm.selectModel(model.name); expanded = false }) }
        }
    }
}

@Composable
private fun OnboardingHint() {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Connect a local model to begin", color = TextMain, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("NYX checks Ollama at 127.0.0.1:11434. Start Ollama in your Android environment, then refresh Models.", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity) {
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(color = if (user) Color(0xFF302344) else Surface1, shape = RoundedCornerShape(16.dp), border = if (!user) BorderStroke(1.dp, Surface2) else null) {
            Column(Modifier.fillMaxWidth(0.88f).padding(13.dp)) {
                Text(if (user) "YOU" else "NYX · ${message.modelId.orEmpty()}", color = if (user) VioletSoft else TextMuted, fontSize = 9.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(message.content, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
                TextButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Rounded.ContentCopy, "Copy message", tint = TextMuted, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)); Text("Copy", color = TextMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(content: String) {
    Surface(Modifier.fillMaxWidth(0.9f), color = Surface1, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp)) {
            Text("NYX · GENERATING", color = VioletSoft, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text(content, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, generating: Boolean, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().imePadding().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(Surface1).padding(8.dp), verticalAlignment = Alignment.Bottom) {
        BasicTextField(
            value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f).heightIn(min = 42.dp, max = 128.dp).padding(horizontal = 10.dp, vertical = 12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = TextMain, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Violet),
            decorationBox = { inner -> Box { if (draft.isEmpty()) Text("Message your local model…", color = TextMuted, fontSize = 14.sp); inner() } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (enabled && !generating) onSend() })
        )
        if (generating) IconButton(onClick = onStop, modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF38212B))) { Icon(Icons.Rounded.Stop, "Stop generation", tint = Red) }
        else IconButton(onClick = onSend, enabled = enabled && draft.isNotBlank(), modifier = Modifier.size(44.dp).clip(CircleShape).background(if (enabled && draft.isNotBlank()) Violet else Surface2)) { Icon(Icons.Rounded.PlayArrow, "Send", tint = if (enabled && draft.isNotBlank()) Obsidian else TextMuted) }
    }
}

@Composable
private fun ModelsScreen(state: NyxState, vm: NyxViewModel, onImport: () -> Unit) {
    val busy = state.isGenerating || state.agentRunning || state.workflowRunning || state.objectiveRunning || state.nativeStatus.phase == NativePhase.LOADING
    var deleting by remember { mutableStateOf<com.nyx.ai.nativeinference.NativeModel?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionIntro("Inference providers", "Native GGUF runs offline on this phone’s CPU. Ollama and OpenAI-compatible servers remain optional. No weights are bundled.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::useNativeProvider, enabled = !busy) { Text(if (state.nativeMode) "Native · selected" else "Use native GGUF") }
                OutlinedButton(onClick = vm::refreshNativeModels) { Icon(Icons.Rounded.Refresh, "Refresh native models") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NATIVE CPU RUNTIME", color = VioletSoft, fontSize = 11.sp)
                    Text(state.nativeStatus.phase.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (state.nativeStatus.loaded) Green else TextMain)
                    state.nativeStatus.detail?.let { Text(it, color = TextMuted, fontSize = 11.sp) }
                    Text("Load ${state.nativeStatus.lastLoadMillis ?: "—"}ms · last generation ${state.nativeStatus.generatedTokens ?: "unknown"} true tokens / ${state.nativeStatus.lastGenerateMillis ?: "—"}ms", color = TextMuted, fontSize = 11.sp)
                    Text("${state.nativeStatus.modelName ?: "No model loaded"} · context ${state.nativeStatus.contextLength ?: state.contextLength}", color = TextMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport, enabled = !state.nativeImporting) { Icon(Icons.Rounded.FolderOpen, null); Text(if (state.nativeImporting) "Importing…" else "Import GGUF") }
                        if (state.nativeStatus.loaded || state.nativeStatus.phase == NativePhase.LOADING) OutlinedButton(onClick = vm::unloadNativeModel) { Text("Stop / unload") }
                    }
                    Text("Choose a compatible instruct/chat GGUF with a default template. Import copies it into private storage. ARM64 CPU only; no GPU/NPU claims. Settings controls context and threads.", color = TextMuted, fontSize = 11.sp)
                }
            }
        }
        if (state.nativeModels.isEmpty()) item { EmptyCard("No native models imported. Legacy URI registrations must be re-imported; they were not executable models.") }
        items(state.nativeModels, key = { it.id }) { model ->
            val estimate = model.estimate(state.contextLength)
            Card(colors = CardDefaults.cardColors(containerColor = if (state.nativeMode && state.selectedModel == model.path) Color(0xFF20192D) else Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(model.name, color = TextMain, fontWeight = FontWeight.SemiBold)
                    Text("${model.architecture} · ${model.sizeBytes / 1048576} MB on disk · training context ${model.trainingContext}", color = TextMuted, fontSize = 11.sp)
                    Text("Estimated FP16 KV: ${estimate.kvCacheBytes?.div(1048576) ?: "unknown"} MB at context ${state.contextLength}; graph reserve ${estimate.computeReserveBytes / 1048576} MB. Weight mapping/residency varies; this is not measured native RAM.", color = TextMuted, fontSize = 11.sp)
                    Text("Tokenizer ${model.metadata.tokenizer} · ${model.metadata.vocabularySize} entries · chat template ${if (model.metadata.chatTemplate.isNullOrBlank()) "missing (not loadable)" else "present"}", color = TextMuted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.loadNativeModel(model) }, enabled = !busy) { Text("Select & load") }
                        IconButton(onClick = { deleting = model }, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, "Delete private model copy", tint = TextMuted) }
                    }
                }
            }
        }
        if (!state.nativeMode) {
            item { Text("SERVER PROVIDER · ${state.endpoint}", color = TextMuted, fontSize = 11.sp) }
            item { OutlinedButton(onClick = vm::refreshModels) { Text("Refresh server models") } }
            items(state.models, key = { it.name }) { model -> ModelCard(model, model.name == state.selectedModel, if (state.endpoint.endsWith("/v1")) "OpenAI-compatible" else "Ollama") { vm.selectModel(model.name) } }
            item { ModelMemoryAdvisory(state) }
        } else item { Text("To use Ollama or a /v1 API instead, save its HTTP(S) URL in Settings. No automatic fallback sends native prompts to another provider.", color = TextMuted, fontSize = 11.sp) }
    }
    deleting?.let { model -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove ${model.name}?") }, text = { Text("Deletes only NYX’s imported private copy and metadata; your original document is not changed.") }, confirmButton = { TextButton(onClick = { vm.deleteNativeModel(model); deleting = null }) { Text("Delete copy") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun ModelMemoryAdvisory(state: NyxState) {
    val context = LocalContext.current
    val snapshot = remember(state.models, state.selectedModel) {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        state.models.firstOrNull { it.name == state.selectedModel }?.let { it.sizeBytes to memory.availMem }
    } ?: return
    val (modelBytes, availableBytes) = snapshot
    if (modelBytes <= 0 || modelBytes < availableBytes * 0.65) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF302619)), shape = RoundedCornerShape(14.dp)) {
        Text(
            "RAM advisory: this model's reported file size is about ${modelBytes / (1024 * 1024)} MB, while Android currently reports ${availableBytes / (1024 * 1024)} MB available. Model file size is not the same as runtime RAM; context/cache may need more, and memory mapping varies. This is a caution, not a compatibility prediction.",
            Modifier.padding(13.dp), color = Color(0xFFF2C879), fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

@Composable
private fun ModelCard(model: LocalModel, selected: Boolean, backend: String, onSelect: () -> Unit) {
    Card(onClick = onSelect, colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF20192D) else Surface1), border = if (selected) BorderStroke(1.dp, Violet.copy(alpha = 0.6f)) else null, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Memory, null, tint = VioletSoft)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(model.name, color = TextMain, fontWeight = FontWeight.Medium)
                Text(if (model.sizeBytes > 0) "${model.sizeBytes / (1024 * 1024)} MB · $backend" else "$backend model", color = TextMuted, fontSize = 11.sp)
            }
            RadioButton(selected = selected, onClick = onSelect)
        }
    }
}

@Composable
private fun AgentsScreen(state: NyxState, vm: NyxViewModel, onImportDocument: () -> Unit) {
    var showCreate by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf<AgentEntity?>(null) }
    var createName by remember { mutableStateOf("") }
    var createPrompt by remember { mutableStateOf("") }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        val dualPane = maxWidth >= 900.dp
        if (dualPane) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(290.dp).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AGENTS", Modifier.weight(1f), color = VioletSoft, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onImportDocument) { Icon(Icons.Rounded.FolderOpen, "Import text document", tint = VioletSoft) }
                        IconButton(onClick = { editingAgent = null; createName = ""; createPrompt = ""; showCreate = true }) { Icon(Icons.Rounded.Add, "Create agent", tint = VioletSoft) }
                    }
                    Text("Choose an agent, then work in the adjacent task pane.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.agents, key = { it.id }) { agent -> AgentCard(agent, agent.id == state.selectedAgentId, onEdit = { editingAgent = agent; createName = agent.name; createPrompt = agent.instructions; showCreate = true }) { vm.selectAgent(agent.id) } }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Surface2))
                AgentWorkspace(state, vm, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionIntro("Local agents", "Agents use your selected local inference model and a bounded, allowlisted tool loop.", Modifier.weight(1f))
                        IconButton(onClick = onImportDocument) { Icon(Icons.Rounded.FolderOpen, "Import text document", tint = VioletSoft) }
                        IconButton(onClick = { editingAgent = null; createName = ""; createPrompt = ""; showCreate = true }) { Icon(Icons.Rounded.Add, "Create agent", tint = VioletSoft) }
                    }
                }
                items(state.agents, key = { it.id }) { agent -> AgentCard(agent, agent.id == state.selectedAgentId, onEdit = { editingAgent = agent; createName = agent.name; createPrompt = agent.instructions; showCreate = true }) { vm.selectAgent(agent.id) } }
                item { AgentWorkspaceContent(state, vm, Modifier.fillMaxWidth()) }
            }
        }
    }
    if (showCreate) AlertDialog(
        onDismissRequest = { showCreate = false }, containerColor = Surface1,
        title = { Text(if (editingAgent == null) "Create an agent" else "Edit agent", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(createName, { createName = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(createPrompt, { createPrompt = it }, label = { Text("System instructions") }, minLines = 3)
                Text("Tools are limited to local calculator, private memory, planning, and the app sandbox workspace.", color = TextMuted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = {
            editingAgent?.let { vm.updateAgent(it, createName, createPrompt) } ?: vm.createAgent(createName, createPrompt)
            showCreate = false; editingAgent = null; createName = ""; createPrompt = ""
        }) { Text(if (editingAgent == null) "Create" else "Save") } },
        dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
    )
}

@Composable
private fun AgentWorkspace(state: NyxState, vm: NyxViewModel, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) { AgentWorkspaceContent(state, vm, Modifier.fillMaxWidth()) }
}

@Composable
private fun AgentWorkspaceContent(state: NyxState, vm: NyxViewModel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = state.agentTask, onValueChange = vm::setAgentTask, modifier = Modifier.fillMaxWidth(), label = { Text("Task") }, placeholder = { Text("Describe a task for this agent…") }, minLines = 3, shape = RoundedCornerShape(14.dp))
        if (state.importedDocumentName.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF20192D)), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, null, tint = VioletSoft); Spacer(Modifier.width(8.dp))
                    Text("Included in task: ${state.importedDocumentName}", color = TextMain, fontSize = 12.sp)
                }
            }
        }
        Text("Import accepts user-selected text, Markdown, CSV, JSON, or log files up to 256 KB. Task content is sent to the selected inference server when you run the agent.", color = TextMuted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::runAgent, enabled = !state.agentRunning, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Run agent") }
            if (state.agentRunning) OutlinedButton(onClick = vm::stopAgent) { Icon(Icons.Rounded.Stop, null); Text("Stop") }
        }
        if (state.agentEvents.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(15.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("EXECUTION TRACE", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                    state.agentEvents.forEach { event -> Text("${event.kind.uppercase(Locale.ROOT)} · ${event.text}", color = when (event.kind) { "tool" -> VioletSoft; "warning" -> Color(0xFFF2C879); else -> TextMuted }, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
                }
            }
        }
        if (state.agentOutput.isNotBlank()) OutputCard("Agent result", state.agentOutput)
    }
}

@Composable
private fun AgentCard(agent: AgentEntity, selected: Boolean, onEdit: () -> Unit, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF20192D) else Surface1), border = if (selected) BorderStroke(1.dp, Violet.copy(alpha = 0.6f)) else null, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SmartToy, null, tint = VioletSoft, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.name, color = TextMain, fontWeight = FontWeight.SemiBold)
                Text(agent.description.orEmpty(), color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit ${agent.name}", tint = TextMuted, modifier = Modifier.size(18.dp)) }
            RadioButton(selected, onClick)
        }
    }
}

@Composable
private fun WorkflowScreen(state: NyxState, vm: NyxViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        val dualPane = maxWidth >= 900.dp
        if (dualPane) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(340.dp).fillMaxHeight()) {
                    SectionIntro("Workflow sequence", "Choose the agents and execution order.")
                    WorkflowAgentSelector(state, vm)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Surface2))
                WorkflowRunPanel(state, vm, Modifier.weight(1f).fillMaxHeight(), scroll = true)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                item { SectionIntro("Sequential workflows", "Select agents to run in order. Each receives the task and accumulated output from previous steps.") }
                item { WorkflowAgentSelector(state, vm) }
                item { WorkflowRunPanel(state, vm, Modifier.fillMaxWidth(), scroll = false) }
            }
        }
    }
}

@Composable
private fun WorkflowAgentSelector(state: NyxState, vm: NyxViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("AGENT SEQUENCE", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
            state.agents.forEach { agent ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agent.id in state.workflowAgentIds, onCheckedChange = { vm.toggleWorkflowAgent(agent.id) })
                    Column(Modifier.weight(1f)) { Text(agent.name, color = TextMain); Text(agent.description.orEmpty(), color = TextMuted, fontSize = 11.sp) }
                }
            }
            Text("Order follows the list shown above. All agents use the currently selected inference model.", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun WorkflowRunPanel(state: NyxState, vm: NyxViewModel, modifier: Modifier, scroll: Boolean) {
    if (scroll) Column(modifier.verticalScroll(rememberScrollState())) { WorkflowRunContent(state, vm) }
    else Column(modifier) { WorkflowRunContent(state, vm) }
}

@Composable
private fun WorkflowRunContent(state: NyxState, vm: NyxViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        OutlinedTextField(value = state.workflowTask, onValueChange = vm::setWorkflowTask, modifier = Modifier.fillMaxWidth(), label = { Text("Workflow goal") }, minLines = 3, shape = RoundedCornerShape(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::runWorkflow, enabled = !state.workflowRunning, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Run pipeline") }
            if (state.workflowRunning) OutlinedButton(onClick = vm::stopWorkflow) { Icon(Icons.Rounded.Stop, null); Text("Pause / stop") }
        }
        if (state.workflowOutput.isNotBlank()) OutputCard("Workflow execution", state.workflowOutput)
    }
}

@Composable
private fun SettingsScreen(state: NyxState, vm: NyxViewModel) {
    var endpoint by remember(state.endpoint) { mutableStateOf(if (state.nativeMode) "http://127.0.0.1:11434" else state.endpoint) }
    var apiKeyDraft by remember(state.endpoint) { mutableStateOf("") }
    var externalConsent by remember(state.externalConsent) { mutableStateOf(state.externalConsent) }
    val openAiEndpoint = endpoint.trimEnd('/').substringBefore('?').endsWith("/v1", ignoreCase = true)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionIntro("Privacy & performance", "NYX stores app data locally. Network requests target the endpoint below; no account, analytics, or cloud backend is built in.") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("INFERENCE ENDPOINT", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Inference API base URL") }, singleLine = true, placeholder = { Text("http://127.0.0.1:11434 or http://host:port/v1") })
                    if (openAiEndpoint) {
                        Text("OpenAI-compatible credentials", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                        val endpointSaved = endpoint == state.endpoint
                        OutlinedTextField(
                            value = apiKeyDraft, onValueChange = { apiKeyDraft = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("API key") }, placeholder = { Text(if (state.credentialConfigured && endpoint == state.endpoint) "Key saved securely · leave blank to keep" else "Optional for local servers") },
                            singleLine = true, enabled = endpointSaved, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = ImeAction.Done)
                        )
                        if (!endpointSaved) Text("Save & connect this endpoint before adding its credential.", color = TextMuted, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { if (vm.saveApiKey(apiKeyDraft)) apiKeyDraft = "" }, enabled = endpointSaved && apiKeyDraft.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Save key securely") }
                            if (state.credentialConfigured && endpoint == state.endpoint) OutlinedButton(onClick = vm::removeApiKey) { Text("Remove key") }
                        }
                        Text(if (state.credentialConfigured && endpoint == state.endpoint) "A credential is encrypted with Android Keystore and stored only on this device." else "Keys are never shown again or stored in chat/settings preferences. Remote hosts require HTTPS.", color = TextMuted, fontSize = 11.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Allow external server", color = TextMain)
                            Text("Only enable for a server you trust. Localhost needs no consent.", color = TextMuted, fontSize = 11.sp)
                        }
                        Switch(checked = externalConsent, onCheckedChange = { externalConsent = it })
                    }
                    Button(onClick = { vm.saveEndpoint(endpoint, externalConsent) }, modifier = Modifier.fillMaxWidth()) { Text("Save & connect") }
                    Text("Remote endpoints may receive prompts and conversation context. NYX labels no inference as on-device unless the server is on this device.", color = TextMuted, fontSize = 11.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Tune, null, tint = VioletSoft); Spacer(Modifier.width(8.dp)); Text("INFERENCE OPTIONS", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp) }
                    Text("Temperature · ${"%.2f".format(state.temperature)}", color = TextMain, fontSize = 13.sp)
                    Slider(value = state.temperature, onValueChange = vm::setTemperature, valueRange = 0f..2f)
                    Text("Context · ${state.contextLength} tokens", color = TextMain, fontSize = 13.sp)
                    Slider(value = state.contextLength.toFloat(), onValueChange = { vm.setContextLength((it / 512).toInt() * 512) }, valueRange = 512f..32768f, steps = 62)
                    Text("CPU threads · ${state.cpuThreads}", color = TextMain, fontSize = 13.sp)
                    Slider(value = state.cpuThreads.toFloat(), onValueChange = { vm.setCpuThreads(it.toInt().coerceAtLeast(1)) }, valueRange = 1f..16f, steps = 14)
                    Text("Native CPU honors context/thread settings; Ollama servers may cap them. /v1 services control their own context and thread configuration.", color = TextMuted, fontSize = 11.sp)
                }
            }
        }
        item { AutonomyPolicyCard(state, vm) }
        item { ExecutionPermissions() }
        item { RepairBudgetCard() }
        item { DiagnosticsCard(state) }
        item {
            val context = LocalContext.current
            val installedVersion = remember(context) { formatInstalledAppVersion(installedAppVersion(context)) }
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("About this build", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text(installedVersion, color = TextMuted, fontSize = 12.sp)
                    Text("Android 8+ · Ollama and OpenAI-compatible APIs, Keystore-protected credentials, bounded agents, durable objective records, and user-approved text import. Native GGUF CPU runtime is bundled; compatible user-imported weights are required. Hardware verification remains separate.", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AutonomyPolicyCard(state: NyxState, vm: NyxViewModel) {
    var confirmFull by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("AUTONOMY POLICY", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(
                "One policy governs terminal commands, file changes, package installs, network access, objectives, and delegated workers. Android permissions and privacy stops still apply.",
                color = TextMuted, fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AutonomyMode.entries.forEach { mode ->
                    val selected = state.autonomyMode == mode
                    OutlinedButton(
                        onClick = {
                            if (mode == AutonomyMode.FULL) confirmFull = true else vm.setAutonomyMode(mode)
                        },
                        modifier = Modifier.weight(1f),
                        colors = if (selected) ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) }
                }
            }
            Text(
                state.policyMessage.ifBlank { "Manual approval is the default: consequential actions ask first." },
                color = if (state.autonomyStopped) Color(0xFFF28B82) else TextMuted,
                fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.autonomyStopped) {
                    Button(onClick = vm::resumeAutonomy) { Text("Resume autonomy") }
                } else {
                    Button(
                        onClick = vm::emergencyStopAutonomy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D), contentColor = Color.White)
                    ) { Text("Emergency stop") }
                }
                OutlinedButton(onClick = vm::revokeAutonomyGrants) { Text("Revoke grants") }
            }
            Text(
                "Full autonomy never grants root, bypasses Android permissions, or silently reveals credentials. Publishing, APK installation, system changes, and private-data transfers still require explicit approval.",
                color = TextMuted, fontSize = 10.sp
            )
        }
    }
    if (confirmFull) {
        AlertDialog(
            onDismissRequest = { confirmFull = false },
            title = { Text("Enable Full Autonomy?") },
            text = {
                Text(
                    "NYX may run terminal commands, edit authorized files, install packages from official repositories, " +
                        "use the network, and delegate tasks without asking each time. Grants are limited to the current " +
                        "environment and workspace, can be revoked here, and Emergency Stop blocks new actions."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmFull = false; vm.setAutonomyMode(AutonomyMode.FULL) }) { Text("Enable") }
            },
            dismissButton = { TextButton(onClick = { confirmFull = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DiagnosticsCard(state: NyxState) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(MemoryTelemetry.snapshot(context)) }
    val metrics by InferenceFabric.metrics.collectAsState()
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DEVICE & INFERENCE METRICS", color = VioletSoft, fontSize = 11.sp)
            Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT} · ${Build.SUPPORTED_ABIS.joinToString()}", color = TextMain, fontSize = 12.sp)
            Text(MemoryTelemetry.describe(snapshot), color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
            Text("Inference active ${metrics.active} · queued ${metrics.queued} · requests ${metrics.calls} · completed ${metrics.completed} · failed ${metrics.failed} · cancelled ${metrics.cancelled}\nLast latency ${metrics.lastLatencyMillis}ms · generated characters ${metrics.generatedCharacters} · streamed chunks ${metrics.streamedChunks} (NOT tokens)", color = TextMuted, fontSize = 11.sp)
            OutlinedButton(onClick = { snapshot = MemoryTelemetry.snapshot(context) }) { Text("Refresh snapshot") }
        }
    }
}

@Composable
private fun ExecutionPermissions() {
    val context = LocalContext.current
    var result by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> result = if (granted) "Permission granted." else "Permission denied; optional feature remains unavailable." }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow execution notifications") }
        if (Build.VERSION.SDK_INT >= 37) OutlinedButton(onClick = { permission.launch("android.permission.ACCESS_LOCAL_NETWORK") }) { Text("Allow LAN inference (Android 17)") }
        Text("LAN permission is optional and not needed for native GGUF or same-profile loopback. A denied permission never triggers another-provider fallback. $result", color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun RepairBudgetCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nyx_settings", 0) }
    var iterations by remember { mutableStateOf(prefs.getInt("repair_iterations", 3).toFloat()) }
    var calls by remember { mutableStateOf(prefs.getInt("repair_calls", 4).toFloat()) }
    var files by remember { mutableStateOf(prefs.getInt("repair_files", 8).toFloat()) }
    var duration by remember { mutableStateOf(prefs.getLong("repair_millis", 300000).toFloat() / 60000f) }
    var margin by remember { mutableStateOf(prefs.getInt("native_memory_margin_mb", 512).toFloat()) }
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("BOUNDED DEVELOPMENT & MEMORY", color = VioletSoft, fontSize = 11.sp)
            Text("Repair iterations ${iterations.toInt()} · inference calls ${calls.toInt()} · edited files ${files.toInt()} · deadline ${duration.toInt()} minutes", color = TextMuted, fontSize = 11.sp)
            Slider(iterations, { iterations = it; prefs.edit().putInt("repair_iterations", it.toInt()).apply() }, valueRange = 1f..5f, steps = 3)
            Slider(calls, { calls = it; prefs.edit().putInt("repair_calls", it.toInt()).apply() }, valueRange = 1f..6f, steps = 4)
            Slider(files, { files = it; prefs.edit().putInt("repair_files", it.toInt()).apply() }, valueRange = 1f..12f, steps = 10)
            Slider(duration, { duration = it; prefs.edit().putLong("repair_millis", it.toInt() * 60000L).apply() }, valueRange = 1f..10f, steps = 8)
            Text("Native minimum memory margin: ${margin.toInt()} MB; Android threshold and available-RAM margin may require more.", color = TextMuted, fontSize = 11.sp)
            Slider(margin, { margin = it; prefs.edit().putInt("native_memory_margin_mb", it.toInt()).apply() }, valueRange = 256f..2048f, steps = 6)
            Text("An enabled workspace permits source edits and execution of its existing build scripts. Only approve projects you trust; Termux/PRoot is not a security sandbox for hostile code. Publication, secrets, raw model shell commands and privilege escalation are not authorized.", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SectionIntro(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(14.dp)) { Text(text, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
}

@Composable
private fun OutputCard(title: String, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title.uppercase(Locale.ROOT), color = VioletSoft, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(7.dp))
            Text(text, color = TextMain, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

private fun Context.displayName(uri: Uri): String = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "imported-model.gguf"

package com.nyx.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyx.ai.data.AgentEntity
import com.nyx.ai.data.ConversationEntity
import com.nyx.ai.data.MessageEntity
import com.nyx.ai.network.LocalModel
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
    var tab by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.registerGguf(uri, context.displayName(uri))
    }
    val textDocumentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTextDocument(uri)
    }
    val destinations = listOf("Chat", "Models", "Agents", "Workflows", "Settings")
    val icons = listOf(Icons.Rounded.ChatBubbleOutline, Icons.Rounded.Memory, Icons.Rounded.SmartToy, Icons.Rounded.AccountTree, Icons.Rounded.Settings)
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
                        3 -> WorkflowScreen(state, vm)
                        else -> SettingsScreen(state, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(tab: String, connected: Boolean, model: String, endpoint: String) {
    val host = Uri.parse(endpoint).host?.lowercase().orEmpty()
    val onDevice = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
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
    var draft by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionIntro("Local models", "Discover Ollama or an OpenAI-compatible /v1 server, or register a GGUF file selected through Android's document picker.") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (state.connected) "Inference server reachable" else "Inference server not detected", color = if (state.connected) Green else TextMain, fontWeight = FontWeight.SemiBold)
                            Text(state.endpoint, color = TextMuted, fontSize = 12.sp)
                        }
                        IconButton(onClick = vm::refreshModels) { Icon(Icons.Rounded.Refresh, "Refresh") }
                    }
                    if (!state.connected) {
                        Spacer(Modifier.height(9.dp)); Divider(color = Surface2); Spacer(Modifier.height(9.dp))
                        if (state.endpoint.substringBefore('?').endsWith("/v1")) {
                            Text("Start your OpenAI-compatible server and confirm it exposes /models and /chat/completions.", color = TextMuted, fontSize = 12.sp)
                        } else {
                            Text("In Termux, start Ollama with:", color = TextMuted, fontSize = 12.sp)
                            val clipboard = LocalClipboardManager.current
                            TextButton(onClick = { clipboard.setText(AnnotatedString("ollama serve")) }) { Text("ollama serve", fontFamily = FontFamily.Monospace, color = VioletSoft); Spacer(Modifier.width(5.dp)); Icon(Icons.Rounded.ContentCopy, "Copy command", modifier = Modifier.size(15.dp)) }
                            Text("Ollama must already be installed and running. NYX does not install it.", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item { Text("AVAILABLE SERVER MODELS", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp) }
        if (state.models.isEmpty()) item { EmptyCard("No models listed yet. Pull or install a model in your Ollama environment.") }
        items(state.models, key = { it.name }) { model -> ModelCard(model, model.name == state.selectedModel, if (state.endpoint.substringBefore('?').endsWith("/v1")) "OpenAI-compatible" else "Ollama") { vm.selectModel(model.name) } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("GGUF model files", color = TextMain, fontWeight = FontWeight.SemiBold)
                            Text("Register a user-selected file. Direct llama.cpp inference is not bundled in this build.", color = TextMuted, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onImport) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(5.dp)); Text("Choose") }
                    }
                    state.registeredGguf.forEach { gguf ->
                        Divider(color = Surface2, modifier = Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Description, null, tint = VioletSoft)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(gguf.displayName, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Registered only · inference unavailable", color = TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
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
                        IconButton(onClick = { showCreate = true }) { Icon(Icons.Rounded.Add, "Create agent", tint = VioletSoft) }
                    }
                    Text("Choose an agent, then work in the adjacent task pane.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.agents, key = { it.id }) { agent -> AgentCard(agent, agent.id == state.selectedAgentId) { vm.selectAgent(agent.id) } }
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
                        IconButton(onClick = { showCreate = true }) { Icon(Icons.Rounded.Add, "Create agent", tint = VioletSoft) }
                    }
                }
                items(state.agents, key = { it.id }) { agent -> AgentCard(agent, agent.id == state.selectedAgentId) { vm.selectAgent(agent.id) } }
                item { AgentWorkspaceContent(state, vm, Modifier.fillMaxWidth()) }
            }
        }
    }
    if (showCreate) AlertDialog(
        onDismissRequest = { showCreate = false }, containerColor = Surface1,
        title = { Text("Create an agent", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(createName, { createName = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(createPrompt, { createPrompt = it }, label = { Text("System instructions") }, minLines = 3)
                Text("Tools are limited to local calculator, private memory, planning, and the app sandbox workspace.", color = TextMuted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { vm.createAgent(createName, createPrompt); showCreate = false; createName = ""; createPrompt = "" }) { Text("Create") } },
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
                    state.agentEvents.forEach { event -> Text("${event.kind.uppercase(Locale.ROOT)} · ${event.text}", color = if (event.kind == "tool") VioletSoft else TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
                }
            }
        }
        if (state.agentOutput.isNotBlank()) OutputCard("Agent result", state.agentOutput)
    }
}

@Composable
private fun AgentCard(agent: AgentEntity, selected: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF20192D) else Surface1), border = if (selected) BorderStroke(1.dp, Violet.copy(alpha = 0.6f)) else null, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SmartToy, null, tint = VioletSoft, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.name, color = TextMain, fontWeight = FontWeight.SemiBold)
                Text(agent.description.orEmpty(), color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
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
    var endpoint by remember(state.endpoint) { mutableStateOf(state.endpoint) }
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
                    Text("Context and thread parameters are sent to Ollama; a server may cap or ignore them.", color = TextMuted, fontSize = 11.sp)
                }
            }
        }
        item { DiagnosticsCard(state) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("About this build", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text("NYX 0.2 · Android 8+ · Ollama and OpenAI-compatible APIs, Keystore-protected credentials, bounded agents, and user-approved text import. Native GGUF inference is not included.", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(state: NyxState) {
    val context = LocalContext.current
    val diagnostics = remember(context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Nominal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown threshold"
                else -> "Unavailable"
            }
        } else "Not exposed by Android version"
        Triple(activityManager.memoryClass, memory, thermal)
    }
    val (heapClassMb, memory, thermal) = diagnostics
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DEVICE & CONNECTION", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", color = TextMain, fontSize = 12.sp)
            Text("CPU ABI · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"} · App heap class · ${heapClassMb} MB", color = TextMuted, fontSize = 11.sp)
            Text("Available system RAM · ${memory.availMem / (1024 * 1024)} MB of ${memory.totalMem / (1024 * 1024)} MB", color = TextMuted, fontSize = 11.sp)
            Text("Thermal status · $thermal", color = if (thermal in setOf("Severe", "Critical", "Emergency", "Shutdown threshold")) Color(0xFFF2C879) else TextMuted, fontSize = 11.sp)
            Text("Endpoint · ${if (state.connected) "Connected" else "Offline"} · ${Uri.parse(state.endpoint).host.orEmpty()} · ${state.selectedModel.ifBlank { "No model selected" }}", color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Thermal and memory figures are advisory snapshots. NYX does not use Tensor TPU, Gemini Nano, or proprietary accelerator APIs.", color = TextMuted, fontSize = 10.sp)
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

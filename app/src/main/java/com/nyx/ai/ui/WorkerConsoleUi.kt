package com.nyx.ai.ui

import com.nyx.ai.ui.prism.PrismCard as Card
import com.nyx.ai.ui.prism.PrismText as Text
import com.nyx.ai.ui.prism.PrismIconButton as IconButton
import com.nyx.ai.ui.prism.PrismTextButton as TextButton
import com.nyx.ai.ui.prism.PrismOutlinedButton as OutlinedButton
import com.nyx.ai.ui.prism.PrismButton as Button
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nyx.ai.autonomy.ConsoleOperation
import com.nyx.ai.autonomy.ConsoleRun
import com.nyx.ai.autonomy.WorkerActivity
import com.nyx.ai.autonomy.WorkerActivityBus
import com.nyx.ai.autonomy.WorkerActivityExport
import com.nyx.ai.autonomy.WorkerActivityKind
import com.nyx.ai.autonomy.WorkerConsoleModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ConsoleSurface = Color(0xFF12121A)
private val ConsoleLine = Color(0xFF2B2936)
private val ConsoleViolet = Color(0xFFA78BFA)
private val ConsoleMuted = Color(0xFFA5A2B0)
private val ConsoleGreen = Color(0xFF72D6A3)
private val ConsoleRed = Color(0xFFFF8E9B)

/** Compact, expandable preview shown directly above the chat composer while work happens. */
@Composable
fun WorkerActivityPreview(
    activities: List<WorkerActivity>,
    running: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activities.isEmpty() || (!running && activities.last().at < System.currentTimeMillis() - 15 * 60_000L)) return
    val latest = activities.last()
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleLine),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) ConsoleGreen else ConsoleMuted))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "NYX WORKING · ${activities.size} EVENTS" else "LAST ACTIVITY",
                    color = ConsoleViolet, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${latest.title} · ${latest.detail}".trim(),
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 15.sp,
                )
            }
            IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.OpenInFull, "Open live worker console", tint = ConsoleViolet, modifier = Modifier.size(17.dp))
            }
        }
    }
}

/** Visible Autonomy entry point; it remains present even before the first event arrives. */
@Composable
fun WorkerActivityEntry(
    activities: List<WorkerActivity>,
    running: Boolean,
    objectiveTitle: String,
    phase: String,
    status: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = activities.lastOrNull()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleLine),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (running) ConsoleGreen else ConsoleMuted))
                Spacer(Modifier.width(8.dp))
                Text("LIVE ACTIVITY", color = ConsoleViolet, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(if (running) "RUNNING" else "IDLE", color = if (running) ConsoleGreen else ConsoleMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                objectiveTitle.ifBlank { "No active objective" },
                color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(phase.ifBlank { "idle" }, status.ifBlank { "no execution" }).joinToString(" · "),
                color = ConsoleMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
            if (latest != null) {
                Text(
                    "${latest.kind.name} · ${latest.title}",
                    color = ConsoleMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = ConsoleViolet, contentColor = Color(0xFF09090D)),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.Terminal, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open live console", fontSize = 11.sp)
                }
                Text("${activities.size} persisted events", color = ConsoleMuted, fontSize = 10.sp)
            }
        }
    }
}

/** Observable timeline only. No hidden reasoning or fabricated progress is rendered. */
@Composable
fun WorkerConsoleDialog(
    activities: List<WorkerActivity>,
    running: Boolean,
    objectiveTitle: String = "",
    phase: String = "",
    objectiveStatus: String = "",
    activeTask: String = "",
    historyActivities: List<WorkerActivity> = activities,
    currentObjectiveId: String? = null,
    currentRunId: String? = null,
    objectiveTitles: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onClearHistory: ((String?, String?) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var selection by rememberSaveable(currentObjectiveId, currentRunId) { mutableStateOf("") }
    var rawMode by rememberSaveable { mutableStateOf(false) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var expandedRawIds by rememberSaveable(selection) { mutableStateOf(emptyList<String>()) }
    var expandedDetailIds by rememberSaveable(selection, detailKey) { mutableStateOf(emptyList<String>()) }
    // Keep the parent list position on Back; every newly selected operation starts at its heading.
    val operationListState = rememberSaveable(selection, saver = LazyListState.Saver) { LazyListState() }
    val rawListState = rememberSaveable(selection, saver = LazyListState.Saver) { LazyListState() }
    val detailListState = rememberSaveable(selection, detailKey, saver = LazyListState.Saver) { LazyListState() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val runs = remember(historyActivities) { WorkerConsoleModel.runs(historyActivities) }
    val selectedRun = runs.firstOrNull { runKey(it) == selection }
        ?: runs.firstOrNull { it.objectiveId == currentObjectiveId && it.runId == currentRunId }
        ?: runs.firstOrNull { it.objectiveId == currentObjectiveId }
        ?: runs.firstOrNull()
    LaunchedEffect(selection, runs) {
        if (selectedRun != null && selection != runKey(selectedRun)) selection = runKey(selectedRun)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val selectedEvents = selectedRun?.let { WorkerConsoleModel.forRun(historyActivities, it.objectiveId, it.runId) }.orEmpty()
    val operations = remember(selectedEvents) { WorkerConsoleModel.operations(selectedEvents) }
    val selectedOperation = operations.firstOrNull { it.key == detailKey }
    val isCurrent = selectedRun?.objectiveId == currentObjectiveId && selectedRun?.runId == currentRunId
    val displayTitle = selectedRun?.objectiveId?.let { objectiveTitles[it] }
        ?: if (isCurrent) objectiveTitle else selectedRun?.objectiveId ?: "Diagnostic events"
    val displayStatus = if (isCurrent) objectiveStatus else selectedOperation?.status ?: selectedEvents.lastOrNull()?.status.orEmpty()
    val exportText = remember(selectedEvents, displayTitle, phase, displayStatus, activeTask) {
        WorkerActivityExport.toText(selectedEvents, displayTitle, phase, displayStatus, activeTask)
    }
    LaunchedEffect(selection, rawMode) {
        // Changing run/view leaves detail mode; event arrival does not change navigation.
        detailKey = null
    }
    val contextHeader: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                displayTitle.ifBlank { "No selected objective" },
                color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "phase: ${if (isCurrent) phase.ifBlank { "idle" } else "history"} · status: ${displayStatus.ifBlank { "unknown" }} · ${operations.size} operations · ${selectedEvents.size} events",
                color = ConsoleMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
            if (isCurrent && activeTask.isNotBlank()) {
                Text("active task: ${activeTask.take(180)}", color = ConsoleViolet, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            com.nyx.ai.ui.prism.PrismActionRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running && onPause != null) OutlinedButton(onClick = onPause, shape = RoundedCornerShape(9.dp)) { Text("Pause", fontSize = 10.sp) }
                if (!running && onResume != null && displayStatus in setOf("paused", "needs_attention", "failed")) {
                    OutlinedButton(onClick = onResume, shape = RoundedCornerShape(9.dp)) { Text("Resume", fontSize = 10.sp) }
                }
                if (onCancel != null && displayStatus !in setOf("completed", "cancelled")) {
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(9.dp)) { Text("Cancel", fontSize = 10.sp) }
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(exportText)) },
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Rounded.ContentCopy, "Copy diagnostic log", tint = ConsoleViolet, modifier = Modifier.size(16.dp)) }
                IconButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, exportText)
                        }
                        context.startActivity(Intent.createChooser(send, "Share NYX worker log"))
                    },
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Rounded.Share, "Share diagnostic log", tint = ConsoleViolet, modifier = Modifier.size(16.dp)) }
            }
            com.nyx.ai.ui.prism.PrismActionRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { historyOpen = true }) {
                        Text("History · ${runs.size}", fontSize = 10.sp, color = ConsoleViolet)
                    }
                    DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
                        runs.take(40).forEach { run ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        runLabel(run, objectiveTitles),
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    selection = runKey(run)
                                    historyOpen = false
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { rawMode = !rawMode }) {
                    Text(if (rawMode) "Operations" else "Raw events", fontSize = 10.sp, color = ConsoleViolet)
                }
                if (onClearHistory != null) {
                    TextButton(onClick = { confirmClear = true }, enabled = historyActivities.isNotEmpty()) {
                        Text("Clear history", fontSize = 10.sp, color = ConsoleMuted)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = ConsoleLine)
    }
    Dialog(
        onDismissRequest = { if (detailKey != null) detailKey = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF09090D)),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Terminal, null, tint = ConsoleViolet, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("LIVE WORKER CONSOLE", color = ConsoleViolet, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (selectedOperation != null) "Operation detail · ${selectedOperation.status}" else "Run-specific execution history",
                            color = ConsoleMuted, fontSize = 10.sp,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close console", tint = ConsoleMuted) }
                }
                HorizontalDivider(Modifier.padding(top = 6.dp), color = ConsoleLine)
                when {
                    selectedOperation != null -> OperationDetail(
                        operation = selectedOperation,
                        now = now,
                        onBack = { detailKey = null },
                        listState = detailListState,
                        expandedIds = expandedDetailIds,
                        onToggle = { id -> expandedDetailIds = toggleConsoleEvent(expandedDetailIds, id) },
                        header = contextHeader,
                        modifier = Modifier.weight(1f),
                    )
                    selectedEvents.isEmpty() -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                        item { contextHeader() }
                        item { Text("No activity for this run yet. Start an objective to see real tool calls, delegation, verification, and resource events here.",
                            color = ConsoleMuted, fontSize = 12.sp, modifier = Modifier.padding(6.dp)) }
                    }
                    rawMode -> RawEventList(
                        events = selectedEvents,
                        expandedIds = expandedRawIds,
                        scrollKey = selection,
                        listState = rawListState,
                        header = contextHeader,
                        onToggle = { id -> expandedRawIds = toggleConsoleEvent(expandedRawIds, id) },
                        modifier = Modifier.weight(1f),
                    )
                    else -> OperationList(
                        operations = operations,
                        now = now,
                        scrollKey = selection,
                        listState = operationListState,
                        header = contextHeader,
                        onOpen = { operation -> detailKey = operation.key },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear historical diagnostics?") },
            text = { Text("This removes stored console events except the currently selected run. Objective records, task evidence, and active execution are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory?.invoke(selectedRun?.objectiveId, selectedRun?.runId)
                    confirmClear = false
                }) { Text("Clear diagnostics") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}

/** One bounded viewport. Live following is opt-in; user input switches it off immediately. */
@Composable
private fun ConsoleTimeline(
    listState: LazyListState,
    viewKey: String,
    newestFirst: Boolean,
    totalRows: Int,
    latestRevision: Any?,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.(() -> Unit) -> Unit,
) {
    var followLive by rememberSaveable(viewKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val manualScroll = remember(viewKey) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) followLive = false
            return Offset.Zero
        }
    } }
    val position by remember(listState) { derivedStateOf {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) "No rows" else "Rows ${visible.first().index + 1}–${visible.last().index + 1} of ${info.totalItemsCount}"
    } }
    suspend fun jumpLatest() {
        listState.scrollToItem(consoleLatestIndex(newestFirst, totalRows))
        if (!newestFirst) listState.scrollBy(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size?.toFloat() ?: 0f)
    }
    // Explicit following also tracks updated output in an existing event, not just event counts.
    LaunchedEffect(viewKey, latestRevision, totalRows, followLive) {
        if (followLive) jumpLatest()
    }
    Column(modifier.fillMaxWidth()) {
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.testTag("console_back")) {
            Text("← Operations", fontSize = 10.sp, color = ConsoleViolet)
        }
        com.nyx.ai.ui.prism.PrismActionRow(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { followLive = false; scope.launch { listState.scrollToItem(0) } }, modifier = Modifier.weight(1f).testTag("console_jump_first")) { Text("First", fontSize = 10.sp, color = ConsoleViolet) }
            TextButton(onClick = { scope.launch { jumpLatest() } }, modifier = Modifier.weight(1f).testTag("console_jump_latest")) { Text("Jump to latest", fontSize = 10.sp, color = ConsoleViolet) }
            TextButton(onClick = { followLive = !followLive }, modifier = Modifier.weight(1f).testTag("console_follow_live")) {
                Text(if (followLive) "Following live" else "Follow live", fontSize = 10.sp, color = ConsoleViolet)
            }
        }
        Text("$position · ${if (followLive) "following" else "reading history"}", color = ConsoleMuted, fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("console_position"))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(manualScroll).testTag("console_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item(key = "context_header") { header() }
            content { followLive = false }
        }
    }
}

@Composable
private fun OperationList(
    operations: List<ConsoleOperation>,
    now: Long,
    scrollKey: String,
    onOpen: (ConsoleOperation) -> Unit,
    listState: LazyListState,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConsoleTimeline(listState, "operations:$scrollKey", newestFirst = true, totalRows = 1 + operations.size,
        latestRevision = operations.firstOrNull()?.let { it.key to it.events.lastOrNull() }, header = header, modifier = modifier) { _ ->
        items(operations, key = { it.key }) { operation -> OperationRow(operation, now, onOpen = { onOpen(operation) }) }
    }
}

@Composable
private fun OperationRow(operation: ConsoleOperation, now: Long, onOpen: () -> Unit) {
    val accent = statusColor(operation.status)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("console_operation_${operation.key}").clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleLine),
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(7.dp))
                Text(operation.status.uppercase(), color = accent, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(WorkerConsoleModel.formatDuration(WorkerConsoleModel.elapsedMillis(operation, now)), color = ConsoleMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text(" · ${operation.events.size} events", color = ConsoleMuted, fontSize = 9.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(operation.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(formatTime(operation.startedAt), color = ConsoleMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Text("Tap for operation details", color = ConsoleViolet, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun RawEventList(
    events: List<WorkerActivity>,
    expandedIds: List<String>,
    scrollKey: String,
    onToggle: (String) -> Unit,
    listState: LazyListState,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConsoleTimeline(listState, "raw:$scrollKey", newestFirst = false, totalRows = 1 + consoleEventRowCount(events, expandedIds), latestRevision = events.lastOrNull(),
        header = header, modifier = modifier) { stopFollowing ->
        consoleEvents(events, expandedIds) { id -> stopFollowing(); onToggle(id) }
    }
}

@Composable
private fun OperationDetail(
    operation: ConsoleOperation,
    now: Long,
    onBack: () -> Unit,
    listState: LazyListState,
    expandedIds: List<String>,
    onToggle: (String) -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConsoleTimeline(listState, "detail:${operation.key}", newestFirst = false, totalRows = 2 + consoleEventRowCount(operation.events, expandedIds),
        latestRevision = operation.events.lastOrNull(), header = header, modifier = modifier, onBack = onBack) { stopFollowing ->
        item(key = "operation_heading") {
            Column(Modifier.fillMaxWidth().testTag("console_operation_heading")) {
                Text(operation.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("status: ${operation.status} · started ${formatTime(operation.startedAt)}", color = ConsoleMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text("${operation.events.size} events · ${WorkerConsoleModel.formatDuration(WorkerConsoleModel.elapsedMillis(operation, now))}", color = ConsoleMuted, fontSize = 9.sp)
            }
        }
        consoleEvents(operation.events, expandedIds) { id -> stopFollowing(); onToggle(id) }
    }
}

private fun consoleEventRowCount(events: List<WorkerActivity>, expandedIds: List<String>): Int = events.sumOf { event ->
    1 + if (event.id !in expandedIds) 0 else listOf(event.title, event.detail, event.toolInput, event.toolOutput)
        .filterNotNull().filter { it.isNotBlank() }.sumOf { consoleTextParts(safe(it)).size }
}

/** Expanded text is separate bounded lazy rows, never a giant, permanently expanded event item. */
private fun LazyListScope.consoleEvents(events: List<WorkerActivity>, expandedIds: List<String>, onToggle: (String) -> Unit) {
    events.forEach { activity ->
        val expanded = activity.id in expandedIds
        item(key = "event:${activity.id}") { EventCard(activity, expanded, onToggle = { onToggle(activity.id) }) }
        if (expanded) {
            listOf("TITLE" to activity.title, "DETAIL" to activity.detail, "COMMAND / INPUT" to activity.toolInput,
                "OUTPUT / STDERR" to activity.toolOutput).forEachIndexed { field, (label, text) ->
                if (!text.isNullOrBlank()) {
                    consoleTextParts(safe(text)).forEachIndexed { part, body ->
                        item(key = "event:${activity.id}:$field:$part") {
                            DetailBlock(if (part == 0) label else "$label · continued", body,
                                Modifier.testTag("console_part_${activity.id}_${field}_$part"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(activity: WorkerActivity, expanded: Boolean, onToggle: () -> Unit) {
    val accent = statusColor(activity.status) .takeIf { activity.status != null } ?: when (activity.kind) {
        WorkerActivityKind.ERROR -> ConsoleRed
        WorkerActivityKind.VERIFICATION -> ConsoleGreen
        WorkerActivityKind.SKILL, WorkerActivityKind.GATEWAY -> Color(0xFF7DD3FC)
        WorkerActivityKind.SUPERVISOR, WorkerActivityKind.DELEGATION -> Color(0xFFF2C879)
        else -> ConsoleViolet
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("console_event_${activity.id}").clickable(onClickLabel = if (expanded) "Collapse event details" else "Expand event details", onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleLine),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activity.kind.name + activity.status?.takeIf { it.isNotBlank() }?.let { " · ${it.uppercase()}" }.orEmpty(),
                    color = accent, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatTime(activity.at), color = ConsoleMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(3.dp))
            Text(activity.title.replace('_', ' '), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (activity.detail.isNotBlank()) {
                Text(
                    safe(activity.detail), color = ConsoleMuted, fontSize = 11.sp, lineHeight = 16.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!activity.toolName.isNullOrBlank()) {
                Text("tool: ${activity.toolName}", color = ConsoleViolet, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 3.dp))
            }
            Text(if (expanded) "Collapse event details" else "Expand event details", color = ConsoleViolet, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun DetailBlock(label: String, text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrEmpty()) return
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleLine)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(label, color = ConsoleViolet, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
            SelectionContainer {
                Text(safe(text), color = ConsoleMuted, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
    }
}

private fun safe(text: String): String = WorkerActivityBus.sanitizeTerminalText(text)

private fun statusColor(status: String?): Color = when (status?.lowercase()) {
    "completed" -> ConsoleGreen
    "failed", "error" -> ConsoleRed
    "cancelled", "interrupted", "blocked", "needs_attention", "needs_review" -> Color(0xFFF2C879)
    "waiting" -> Color(0xFF7DD3FC)
    "running", "verifying", "planning" -> ConsoleViolet
    else -> ConsoleMuted
}

private fun runKey(run: ConsoleRun): String = "${run.objectiveId.orEmpty()}|${run.runId.orEmpty()}"

private fun runLabel(run: ConsoleRun, titles: Map<String, String>): String {
    val title = run.objectiveId?.let { titles[it] } ?: run.objectiveId ?: "Unassigned diagnostics"
    val suffix = run.runId?.takeLast(8) ?: "legacy"
    return "$title · $suffix"
}

private fun formatTime(at: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

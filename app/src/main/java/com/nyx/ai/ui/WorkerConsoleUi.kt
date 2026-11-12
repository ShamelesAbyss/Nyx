package com.nyx.ai.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.nyx.ai.autonomy.WorkerActivity
import com.nyx.ai.autonomy.WorkerActivityExport
import com.nyx.ai.autonomy.WorkerActivityKind
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
    onDismiss: () -> Unit,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportText = remember(activities, objectiveTitle, phase, objectiveStatus, activeTask) {
        WorkerActivityExport.toText(activities, objectiveTitle, phase, objectiveStatus, activeTask)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF09090D)),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Terminal, null, tint = ConsoleViolet, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("LIVE WORKER CONSOLE", color = ConsoleViolet, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (running) "Observable actions from the active objective" else "Persisted activity history",
                            color = ConsoleMuted, fontSize = 10.sp,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close console", tint = ConsoleMuted) }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        objectiveTitle.ifBlank { "No selected objective" },
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "phase: ${phase.ifBlank { "idle" }} · status: ${objectiveStatus.ifBlank { "unknown" }}",
                        color = ConsoleMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    )
                    if (activeTask.isNotBlank()) {
                        Text("active task: ${activeTask.take(180)}", color = ConsoleViolet, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (running && onPause != null) OutlinedButton(onClick = onPause, shape = RoundedCornerShape(9.dp)) { Text("Pause", fontSize = 10.sp) }
                        if (!running && onResume != null && objectiveStatus in setOf("paused", "needs_attention", "failed")) {
                            OutlinedButton(onClick = onResume, shape = RoundedCornerShape(9.dp)) { Text("Resume", fontSize = 10.sp) }
                        }
                        if (onCancel != null && objectiveStatus !in setOf("completed", "cancelled")) {
                            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(9.dp)) { Text("Cancel", fontSize = 10.sp) }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(exportText)) },
                            modifier = Modifier.size(34.dp),
                        ) { Icon(Icons.Rounded.ContentCopy, "Copy diagnostic log", tint = ConsoleViolet, modifier = Modifier.size(16.dp)) }
                        IconButton(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, exportText)
                                }
                                context.startActivity(Intent.createChooser(send, "Share NYX worker log"))
                            },
                            modifier = Modifier.size(34.dp),
                        ) { Icon(Icons.Rounded.Share, "Share diagnostic log", tint = ConsoleViolet, modifier = Modifier.size(16.dp)) }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(ConsoleLine))
                if (activities.isEmpty()) {
                    Text(
                        "No activity yet. Start an objective to see real tool calls, delegation, verification, and resource events here.",
                        color = ConsoleMuted, fontSize = 12.sp, modifier = Modifier.padding(18.dp),
                    )
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(activities.size) {
                        if (activities.isNotEmpty()) listState.scrollToItem(activities.lastIndex)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(activities, key = { it.id }) { activity ->
                            TimelineRow(
                                activity = activity,
                                expanded = expandedId == activity.id,
                                onToggle = { expandedId = if (expandedId == activity.id) null else activity.id },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(activity: WorkerActivity, expanded: Boolean, onToggle: () -> Unit) {
    val accent = when (activity.kind) {
        WorkerActivityKind.ERROR -> ConsoleRed
        WorkerActivityKind.VERIFICATION -> ConsoleGreen
        WorkerActivityKind.SKILL, WorkerActivityKind.GATEWAY -> Color(0xFF7DD3FC)
        WorkerActivityKind.SUPERVISOR, WorkerActivityKind.DELEGATION -> Color(0xFFF2C879)
        else -> ConsoleViolet
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleLine),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activity.kind.name, color = accent, fontSize = 8.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(formatTime(activity.at), color = ConsoleMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(3.dp))
            Text(activity.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (activity.detail.isNotBlank()) {
                Text(
                    activity.detail, color = ConsoleMuted, fontSize = 10.sp, lineHeight = 14.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!activity.toolName.isNullOrBlank()) {
                Text("tool: ${activity.toolName}", color = ConsoleViolet, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 3.dp))
            }
            if (expanded) {
                DetailBlock("INPUT", activity.toolInput)
                DetailBlock("OUTPUT", activity.toolOutput)
                Text("Tap to collapse", color = ConsoleMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 5.dp))
            } else if (!activity.toolInput.isNullOrBlank() || !activity.toolOutput.isNullOrBlank()) {
                Text("Tap for input/output", color = ConsoleViolet, fontSize = 8.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun DetailBlock(label: String, text: String?) {
    if (text.isNullOrBlank()) return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = ConsoleViolet, fontSize = 8.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(
                text, color = ConsoleMuted, fontSize = 9.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

private fun formatTime(at: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

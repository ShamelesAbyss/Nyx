package com.nyx.ai.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nyx.ai.autonomy.WorkerActivity
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

/** Observable timeline only. No hidden reasoning or fabricated progress is rendered. */
@Composable
fun WorkerConsoleDialog(activities: List<WorkerActivity>, running: Boolean, onDismiss: () -> Unit) {
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
                    TextButton(onClick = onDismiss) { Text("Close", color = ConsoleViolet) }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close console", tint = ConsoleMuted) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(ConsoleLine))
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(activities, key = { it.id }) { activity -> TimelineRow(activity) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(activity: WorkerActivity) {
    val accent = when (activity.kind) {
        WorkerActivityKind.ERROR -> ConsoleRed
        WorkerActivityKind.VERIFICATION -> ConsoleGreen
        WorkerActivityKind.SKILL, WorkerActivityKind.GATEWAY -> Color(0xFF7DD3FC)
        WorkerActivityKind.SUPERVISOR, WorkerActivityKind.DELEGATION -> Color(0xFFF2C879)
        else -> ConsoleViolet
    }
    Card(colors = CardDefaults.cardColors(containerColor = ConsoleSurface), shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleLine)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activity.kind.name, color = accent, fontSize = 8.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(formatTime(activity.at), color = ConsoleMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(3.dp))
            Text(activity.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (activity.detail.isNotBlank()) {
                Text(activity.detail, color = ConsoleMuted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (!activity.toolName.isNullOrBlank()) {
                Text("tool: ${activity.toolName}", color = ConsoleViolet, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

private fun formatTime(at: Long): String = rememberTime(at)

private fun rememberTime(at: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

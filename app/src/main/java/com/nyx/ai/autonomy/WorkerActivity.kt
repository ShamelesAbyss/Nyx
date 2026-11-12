package com.nyx.ai.autonomy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/** Observable actions, never hidden chain-of-thought. */
enum class WorkerActivityKind {
    PLAN, TASK, TOOL, SKILL, GATEWAY, FILE, DELEGATION, SUPERVISOR,
    VERIFICATION, TERMINAL, METRIC, ERROR, RECOVERY, INFO,
}

data class WorkerActivity(
    val id: String,
    val at: Long,
    val objectiveId: String?,
    val taskId: String?,
    val kind: WorkerActivityKind,
    val title: String,
    val detail: String,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
)

/**
 * Process-wide, persisted timeline for the Live Worker Console. The engine records observable
 * events here at the same points it writes Room events, so the console does not invent progress.
 */
object WorkerActivityBus {
    const val MAX_EVENTS = 300
    private const val FILE_NAME = "worker_activity.jsonl"

    private val lock = Any()
    private val buffer = ArrayDeque<WorkerActivity>()
    private var file: File? = null
    private var next = 0L
    private val _events = MutableStateFlow<List<WorkerActivity>>(emptyList())
    val events: StateFlow<List<WorkerActivity>> = _events.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val target = File(context.applicationContext.filesDir, FILE_NAME)
            file = target
            if (target.isFile) {
                target.useLines { lines ->
                    lines.toList().takeLast(MAX_EVENTS).forEach { line ->
                        decode(line)?.let { buffer.addLast(it) }
                    }
                }
            }
            publish()
        }
    }

    fun record(
        kind: WorkerActivityKind,
        title: String,
        detail: String = "",
        objectiveId: String? = null,
        taskId: String? = null,
        toolName: String? = null,
        toolInput: String? = null,
        toolOutput: String? = null,
    ) {
        val safeTitle = redact(title).take(120).ifBlank { kind.name.lowercase() }
        val safeDetail = redact(detail).take(2_000)
        val safeInput = toolInput?.let { redact(it).take(4_000) }?.takeIf { it.isNotBlank() }
        val safeOutput = toolOutput?.let { redact(it).take(4_000) }?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            val item = WorkerActivity(
                id = "activity-${System.currentTimeMillis()}-${next++}",
                at = System.currentTimeMillis(),
                objectiveId = objectiveId,
                taskId = taskId,
                kind = kind,
                title = safeTitle,
                detail = safeDetail,
                toolName = toolName?.take(80),
                toolInput = safeInput,
                toolOutput = safeOutput,
            )
            buffer.addLast(item)
            while (buffer.size > MAX_EVENTS) buffer.removeFirst()
            append(item)
            publish()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            file?.delete()
            publish()
        }
    }

    private fun publish() {
        _events.value = buffer.toList()
    }

    private fun append(item: WorkerActivity) {
        val target = file ?: return
        runCatching {
            target.appendText(JSONObject().apply {
                put("id", item.id)
                put("at", item.at)
                put("objectiveId", item.objectiveId ?: "")
                put("taskId", item.taskId ?: "")
                put("kind", item.kind.name)
                put("title", item.title)
                put("detail", item.detail)
                put("toolName", item.toolName ?: "")
                put("toolInput", item.toolInput ?: "")
                put("toolOutput", item.toolOutput ?: "")
            }.toString() + "\n")
            // Keep the file bounded even after many sessions.
            if (target.length() > 1_500_000L) {
                target.writeText(buffer.joinToString("\n") { encode(it) } + "\n")
            }
        }
    }

    private fun encode(item: WorkerActivity): String = JSONObject().apply {
        put("id", item.id)
        put("at", item.at)
        put("objectiveId", item.objectiveId ?: "")
        put("taskId", item.taskId ?: "")
        put("kind", item.kind.name)
        put("title", item.title)
        put("detail", item.detail)
        put("toolName", item.toolName ?: "")
        put("toolInput", item.toolInput ?: "")
        put("toolOutput", item.toolOutput ?: "")
    }.toString()

    private fun decode(line: String): WorkerActivity? = runCatching {
        val json = JSONObject(line)
        WorkerActivity(
            id = json.optString("id").ifBlank { "activity-${json.optLong("at")}" },
            at = json.optLong("at"),
            objectiveId = json.optString("objectiveId").takeIf { it.isNotBlank() },
            taskId = json.optString("taskId").takeIf { it.isNotBlank() },
            kind = runCatching { WorkerActivityKind.valueOf(json.optString("kind")) }
                .getOrDefault(WorkerActivityKind.INFO),
            title = json.optString("title").take(120),
            detail = json.optString("detail").take(2_000),
            toolName = json.optString("toolName").takeIf { it.isNotBlank() },
            toolInput = json.optString("toolInput").take(4_000).takeIf { it.isNotBlank() },
            toolOutput = json.optString("toolOutput").take(4_000).takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Hides common credential shapes before anything reaches the UI or the log file. */
    internal fun redact(raw: String): String {
        var text = raw
        val patterns = listOf(
            Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer|basic|token)?\\s*)([^\\s,;]+)"),
            Regex("(?i)(api[_-]?key\\s*[:=]\\s*)([^\\s,;]+)"),
            Regex("(?i)(token\\s*[:=]\\s*)([^\\s,;]+)"),
            Regex("(?i)(bearer\\s+)([A-Za-z0-9._~+/=-]{12,})"),
        )
        patterns.forEach { pattern ->
            text = pattern.replace(text) { match -> match.groupValues[1] + "[redacted]" }
        }
        return text
    }
}

/**
 * Bounded plain-text diagnostics for copy/share. It exports only observable worker events and
 * already-redacted tool payloads; it never adds model reasoning or reads arbitrary files.
 */
object WorkerActivityExport {
    const val MAX_CHARS = 60_000
    private const val MAX_EVENT_CHARS = 3_000

    fun toText(
        activities: List<WorkerActivity>,
        objectiveTitle: String = "",
        phase: String = "",
        status: String = "",
        activeTask: String = "",
        limit: Int = MAX_CHARS,
    ): String {
        val bounded = limit.coerceIn(1_000, MAX_CHARS)
        val out = StringBuilder()
        fun line(text: String) {
            if (out.length >= bounded) return
            out.append(text.take(bounded - out.length)).append('\n')
        }
        line("NYX LIVE ACTIVITY")
        if (objectiveTitle.isNotBlank()) line("Objective: ${WorkerActivityBus.redact(objectiveTitle).take(200)}")
        if (phase.isNotBlank()) line("Phase: ${phase.take(80)}")
        if (status.isNotBlank()) line("Status: ${status.take(80)}")
        if (activeTask.isNotBlank()) line("Active task: ${WorkerActivityBus.redact(activeTask).take(200)}")
        line("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        line("")
        activities.takeLast(300).forEach { event ->
            val body = buildString {
                append('[').append(formatTime(event.at)).append("] ")
                append(event.kind.name).append(" · ").append(WorkerActivityBus.redact(event.title)).append('\n')
                if (event.detail.isNotBlank()) append("  ").append(WorkerActivityBus.redact(event.detail)).append('\n')
                if (!event.toolName.isNullOrBlank()) append("  tool: ").append(WorkerActivityBus.redact(event.toolName)).append('\n')
                if (!event.toolInput.isNullOrBlank()) append("  input: ").append(WorkerActivityBus.redact(event.toolInput)).append('\n')
                if (!event.toolOutput.isNullOrBlank()) append("  output: ").append(WorkerActivityBus.redact(event.toolOutput)).append('\n')
            }.take(MAX_EVENT_CHARS)
            line(body.trimEnd())
        }
        return out.toString().trimEnd()
    }

    private fun formatTime(at: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))
}

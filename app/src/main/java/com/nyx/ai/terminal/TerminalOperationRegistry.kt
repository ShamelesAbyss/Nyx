package com.nyx.ai.terminal

import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID

/** A finite command context, not an interactive terminal session or a process-control handle. */
data class TerminalOperationContext(
    val operationId: String = UUID.randomUUID().toString(),
    val objectiveId: String? = null,
    val taskId: String? = null,
    val namespace: String = "termux_host",
    val workspace: String? = null,
    val readOnly: Boolean = false,
)

enum class TerminalOperationState { QUEUED, RUNNING, FINISHED, UNCERTAIN }

data class TerminalOperationEvidence(
    val context: TerminalOperationContext,
    val state: TerminalOperationState,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val resultKind: String? = null,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val message: String? = null,
)

/** Atomic, app-private evidence; a failed write prevents dispatch. No credentials/model settings. */
class TerminalOperationJournal(private val directory: File) {
    @Synchronized fun contains(id: String): Boolean {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.properties").exists()
    }

    @Synchronized fun write(evidence: TerminalOperationEvidence) {
        require(UUID.fromString(evidence.context.operationId).toString() == evidence.context.operationId)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create terminal evidence directory" }
        val data = Properties().apply {
            setProperty("id", evidence.context.operationId)
            evidence.context.objectiveId?.let { setProperty("objective", it) }
            evidence.context.taskId?.let { setProperty("task", it) }
            setProperty("namespace", evidence.context.namespace)
            evidence.context.workspace?.let { setProperty("workspace", it) }
            setProperty("readOnly", evidence.context.readOnly.toString())
            setProperty("state", evidence.state.name)
            setProperty("startedAt", evidence.startedAt.toString())
            evidence.finishedAt?.let { setProperty("finishedAt", it.toString()) }
            evidence.resultKind?.let { setProperty("resultKind", it) }
            evidence.exitCode?.let { setProperty("exitCode", it.toString()) }
            setProperty("stdout", evidence.stdout.take(32_768))
            setProperty("stderr", evidence.stderr.take(16_384))
            evidence.message?.let { setProperty("message", it.take(600)) }
        }
        val target = File(directory, "${evidence.context.operationId}.properties")
        val temporary = File(directory, "${evidence.context.operationId}.tmp")
        FileOutputStream(temporary).use { stream -> data.store(stream, "NYX finite terminal operation"); stream.fd.sync() }
        check(temporary.renameTo(target)) { "Cannot atomically save terminal evidence" }
    }

    @Synchronized fun load(): List<TerminalOperationEvidence> {
        val files = directory.listFiles { file -> file.extension == "properties" } ?: return emptyList()
        // Never silently discard unresolved evidence. Corrupt evidence fails admission closed.
        return files.map { file ->
            val data = Properties().apply { file.inputStream().use(::load) }
            val id = data.getProperty("id")
            require(UUID.fromString(id).toString() == id && file.name == "$id.properties")
            TerminalOperationEvidence(
                TerminalOperationContext(id, data.getProperty("objective"), data.getProperty("task"),
                    data.getProperty("namespace"), data.getProperty("workspace"), data.getProperty("readOnly").toBoolean()),
                TerminalOperationState.valueOf(data.getProperty("state")), data.getProperty("startedAt").toLong(),
                data.getProperty("finishedAt")?.toLong(), data.getProperty("resultKind"), data.getProperty("exitCode")?.toInt(),
                data.getProperty("stdout", ""), data.getProperty("stderr", ""), data.getProperty("message"),
            )
        }
    }

    @Synchronized fun pruneCompleted(keep: Int = 200) {
        load().filter { it.state == TerminalOperationState.FINISHED }.sortedByDescending { it.startedAt }
            .drop(keep).forEach { File(directory, "${it.context.operationId}.properties").delete() }
    }
}

/** Shared admission/ownership policy. It never sends signals or treats timeout as process death. */
class TerminalOperationRegistry(private val journal: TerminalOperationJournal? = null) {
    private val operations = linkedMapOf<String, TerminalOperationEvidence>()
    private var completionsSincePrune = 0

    init {
        journal?.load()?.filter { it.state != TerminalOperationState.FINISHED }?.sortedBy { it.startedAt }?.forEach { old ->
            val recovered = old.copy(
                state = if (old.state == TerminalOperationState.QUEUED) TerminalOperationState.FINISHED else TerminalOperationState.UNCERTAIN,
                finishedAt = System.currentTimeMillis(),
                message = if (old.state == TerminalOperationState.QUEUED) "Interrupted before dispatch; no command started."
                    else "Interrupted command ownership is uncertain; review before replay or releasing reservation.",
            )
            journal.write(recovered)
            if (recovered.state != TerminalOperationState.FINISHED) operations[recovered.context.operationId] = recovered
        }
    }

    @Synchronized fun enqueue(context: TerminalOperationContext) {
        require(context.operationId !in operations && journal?.contains(context.operationId) != true) { "Operation ID already registered" }
        require(operations.size < MAX_OUTSTANDING) { "Terminal operation queue/reservations are full; review unresolved commands" }
        val evidence = TerminalOperationEvidence(context, TerminalOperationState.QUEUED, System.currentTimeMillis())
        journal?.write(evidence)
        operations[context.operationId] = evidence
    }

    /** FIFO among conflicting queued operations; unrelated work can make progress independently. */
    @Synchronized fun tryStart(id: String, capacity: Int): Boolean {
        val current = operations[id] ?: return false
        if (current.state != TerminalOperationState.QUEUED) return false
        val reserved = operations.values.filter { it.state == TerminalOperationState.RUNNING || it.state == TerminalOperationState.UNCERTAIN }
        if (reserved.size >= capacity.coerceIn(0, MAX_CONTEXTS)) return false
        if (reserved.any { conflicts(it.context, current.context) }) return false
        val earlier = operations.values.takeWhile { it.context.operationId != id }
        if (earlier.any { it.state == TerminalOperationState.QUEUED && conflicts(it.context, current.context) }) return false
        val running = current.copy(state = TerminalOperationState.RUNNING)
        operations[id] = running
        journal?.write(running) // persist ownership BEFORE the service dispatch, even across process death
        return true
    }

    @Synchronized fun finish(id: String, kind: String, exitCode: Int?, stdout: String, stderr: String, message: String?, uncertain: Boolean) {
        val current = operations[id] ?: return
        val evidence = current.copy(state = if (uncertain && current.state != TerminalOperationState.QUEUED) TerminalOperationState.UNCERTAIN else TerminalOperationState.FINISHED,
            finishedAt = System.currentTimeMillis(), resultKind = kind, exitCode = exitCode,
            stdout = stdout.take(32_768), stderr = stderr.take(16_384), message = message)
        // On disk failure retain the reservation in memory, rather than admitting a conflicting job.
        journal?.write(evidence)
        if (evidence.state == TerminalOperationState.FINISHED) operations.remove(id) else operations[id] = evidence
        if (++completionsSincePrune >= 25) {
            journal?.pruneCompleted()
            completionsSincePrune = 0
        }
    }

    @Synchronized fun snapshot(): List<TerminalOperationEvidence> = operations.values.toList()
    @Synchronized fun history(): List<TerminalOperationEvidence> = journal?.load()?.sortedByDescending { it.startedAt } ?: snapshot()

    /** Explicit review acknowledgment, not proof of process termination. Never automatic on resume. */
    @Synchronized fun acknowledgeReviewed(id: String, confirmedNoLongerRunning: Boolean): Boolean {
        val old = operations[id] ?: return false
        if (!confirmedNoLongerRunning || old.state != TerminalOperationState.UNCERTAIN) return false
        journal?.write(old.copy(state = TerminalOperationState.FINISHED, message = "User explicitly confirmed this operation no longer runs; reservation released."))
        operations.remove(id)
        return true
    }

    companion object {
        const val MAX_CONTEXTS = 8
        const val MAX_OUTSTANDING = 256
        /** Null/unverified workspace is namespace-wide exclusive; nested directories overlap. */
        fun conflicts(a: TerminalOperationContext, b: TerminalOperationContext): Boolean {
            // PRoot bind mappings are not known here. Cross-environment writes stay exclusive.
            if (a.namespace != b.namespace) return !(a.readOnly && b.readOnly)
            val left = normalizedWorkspace(a.workspace)
            val right = normalizedWorkspace(b.workspace)
            if (left == null || right == null) return true
            val overlap = left == right || left.startsWith("$right/") || right.startsWith("$left/") || left == "/" || right == "/"
            return overlap && !(a.readOnly && b.readOnly)
        }
        private fun normalizedWorkspace(path: String?): String? = path?.trimEnd('/')?.takeIf {
            it.startsWith('/') && it.isNotEmpty() && '\u0000' !in it && it.split('/').none { part -> part == "." || part == ".." }
        }
    }
}

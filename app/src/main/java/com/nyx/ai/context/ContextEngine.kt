package com.nyx.ai.context

/** Inputs for assembling the bounded context supplied to an objective task. */
data class ContextRequest(
    val objectiveId: String,
    val goal: String,
    val taskTitle: String,
    val taskInstructions: String,
    val verification: String?,
    val dependencyTaskIds: List<String> = emptyList(),
    val records: List<ContextRecord> = emptyList(),
    val budgetChars: Int = 12_000,
    val maxHistoryRecords: Int = 6,
    val sourceValidator: ((ProjectSourceReference) -> Boolean)? = null,
    val projectContext: ProjectRetrieval? = null,
)

data class BuiltContext(
    val text: String,
    val includedRecordIds: List<String>,
    val truncated: Boolean,
    val usedChars: Int,
    val invalidatedRecordIds: List<String> = emptyList(),
)

/** Deterministically selects and formats stored context without model calls. */
object ContextEngine {
    private val ignoredKeywords = setOf(
        "about", "after", "again", "also", "and", "are", "because", "before", "being", "but",
        "can", "could", "does", "for", "from", "have", "into", "its", "more", "not", "only",
        "our", "over", "should", "that", "the", "their", "then", "there", "these", "they",
        "this", "those", "through", "under", "using", "was", "were", "what", "when", "where",
        "which", "while", "with", "would", "your",
    )

    fun build(request: ContextRequest, store: ContextStore): BuiltContext {
        val budget = request.budgetChars.coerceAtLeast(0)
        if (budget == 0) return BuiltContext("", emptyList(), true, 0)

        val reminder = "GOAL REMINDER: ${request.goal.take(300)}"
        val availableForSections = (budget - reminder.length - 2).coerceAtLeast(0)
        val output = StringBuilder()
        val included = mutableListOf<String>()
        var truncated = request.goal.length > 2_000 || request.taskTitle.length + request.taskInstructions.length + 1 > 4_000 ||
            (request.verification?.length ?: 0) > 2_000 || request.goal.length > 300
        var baseBlocked = false

        fun appendText(value: String, allowCut: Boolean): Boolean {
            if (value.isEmpty()) return true
            val separator = if (output.isEmpty()) "" else "\n\n"
            val available = availableForSections - output.length - separator.length
            if (available >= value.length) {
                output.append(separator).append(value)
                return true
            }
            truncated = true
            if (allowCut && available > 0) output.append(separator).append(value.take(available))
            return false
        }

        val goalSection = "GOAL\n${request.goal.take(2_000)}"
        baseBlocked = !appendText(goalSection, allowCut = true)
        if (!baseBlocked) {
            val taskBody = "${request.taskTitle}\n${request.taskInstructions}"
            val taskSection = "TASK\n${taskBody.take(4_000)}"
            baseBlocked = !appendText(taskSection, allowCut = true)
        }
        if (!baseBlocked && request.verification != null) {
            baseBlocked = !appendText("VERIFICATION\n${request.verification.take(2_000)}", allowCut = true)
        }

        val candidates = (store.list(request.objectiveId) + request.records)
            .filter { it.objectiveId == request.objectiveId }
            .associateBy { it.id }
            .values
            .sortedWith(compareBy<ContextRecord> { it.createdAt }.thenBy { it.id })
        val invalidated = candidates.filterNot { store.isUsable(it, request.sourceValidator) }.map { it.id }.toMutableList()
        if (invalidated.isNotEmpty()) truncated = true
        val allRecords = candidates.filterNot { it.id in invalidated }
        val byNewest = allRecords.sortedWith(compareByDescending<ContextRecord> { it.createdAt }.thenBy { it.id })
        val includedSet = mutableSetOf<String>()
        val representedEvidence = mutableSetOf<String>()
        var recordsBlocked = false

        fun appendRecordBatch(heading: String, records: List<ContextRecord>, contentLimit: Int) {
            if (baseBlocked || recordsBlocked || records.isEmpty()) return
            var first = true
            for (record in records) {
                if (record.id in includedSet || record.id in representedEvidence) continue
                val rawContent = store.read(record, contentLimit + 1)
                if (rawContent.isEmpty()) continue
                if (!store.isUsable(record, request.sourceValidator)) {
                    truncated = true
                    invalidated += record.id
                    continue
                }
                if (rawContent.length > contentLimit) truncated = true
                val content = rawContent.take(contentLimit)
                val evidenceNote = if (record.evidence.isEmpty()) "" else
                    "\nEVIDENCE: " + record.evidence.take(8).joinToString(", ") { it.recordId } +
                        if (record.evidence.size > 8) " (+${record.evidence.size - 8} retained references)" else ""
                val entry = if (first) "$heading\n${record.title}\n$content$evidenceNote" else "${record.title}\n$content$evidenceNote"
                if (!appendText(entry, allowCut = false)) {
                    recordsBlocked = true
                    break
                }
                first = false
                included += record.id
                includedSet += record.id
                representedEvidence += record.evidence.map { it.recordId }
            }
        }

        val dependencyIds = request.dependencyTaskIds.toSet()
        val dependencyRecords = byNewest.filter { it.taskId != null && it.taskId in dependencyIds }
        appendRecordBatch("DEPENDENCY RESULTS", dependencyRecords, 1_500)

        appendRecordBatch("CHECKPOINT", byNewest.filter { it.kind == "checkpoint" }.take(1), 1_500)

        request.projectContext?.let { project ->
            // Prebuilt retrieval must be validated again at assembly time; fail closed without a validator.
            val fresh = try {
                project.sources.isNotEmpty() && project.sources.all { request.sourceValidator?.invoke(it) == true }
            } catch (_: Exception) { false }
            if (!baseBlocked && fresh && project.text.isNotBlank()) {
                appendText("PROJECT EVIDENCE\n${project.text}", allowCut = true)
            } else if (!fresh) truncated = true
            if (project.truncated) truncated = true
        }

        if (!baseBlocked && request.maxHistoryRecords > 0) {
            val taskKeywords = keywords("${request.taskTitle} ${request.taskInstructions}")
            val rankedRelevant = allRecords.asSequence()
                .filter { it.kind != "failure" && it.id !in includedSet && it.id !in representedEvidence }
                .map { record -> record to overlap(taskKeywords, keywords("${record.title} ${record.summary}")) }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<ContextRecord, Int>> { it.first.kind == "summary" }
                        .thenByDescending { it.second }
                        .thenByDescending { it.first.createdAt }
                        .thenBy { it.first.id },
                )
                .toList()
            if (rankedRelevant.size > request.maxHistoryRecords) truncated = true
            val relevant = rankedRelevant.take(request.maxHistoryRecords).map { it.first }
            appendRecordBatch("RELEVANT HISTORY", relevant, 1_200)
        }

        val availableFailures = byNewest.filter { it.kind == "failure" && it.id !in includedSet }
        if (availableFailures.size > 3) truncated = true
        val failures = availableFailures.take(3)
        appendRecordBatch("FAILURE HISTORY", failures, 600)

        val reminderAvailable = budget - output.length - if (output.isEmpty()) 0 else 2
        if (reminderAvailable > 0) {
            val separator = if (output.isEmpty()) "" else "\n\n"
            if (reminder.length <= reminderAvailable) {
                output.append(separator).append(reminder)
            } else {
                output.append(separator).append(reminder.take(reminderAvailable))
                truncated = true
            }
        } else {
            truncated = true
        }

        val finalText = output.toString().take(budget)
        if (finalText.length != output.length) truncated = true
        return BuiltContext(finalText, included, truncated, finalText.length, invalidated.distinct())
    }

    private fun keywords(value: String): Set<String> = Regex("[\\p{L}\\p{N}]{2,}")
        .findAll(value.lowercase(java.util.Locale.ROOT))
        .map { it.value }
        .filterNot { it in ignoredKeywords }
        .toSet()

    private fun overlap(left: Set<String>, right: Set<String>): Int = left.intersect(right).size
}

package com.nyx.ai.development

/** A file that may be relevant to a goal, with its observed size. */
data class SourceCandidate(val path: String, val sizeBytes: Int = 0)

/**
 * A scored candidate.
 *
 * @property path the candidate path, unchanged.
 * @property score the deterministic score; higher is more relevant.
 * @property reasons 1..4 short strings describing the score.
 */
data class RankedSource(val path: String, val score: Int, val reasons: List<String>)

/**
 * Deterministic ranking of source candidates for a goal.
 *
 * Signals, in decreasing weight: failure path (+80), exact basename mention (+60), diagnostics
 * mention (+40), path segment match (+30), same directory as a failure (+25), identifier from the
 * goal appearing in the path (+20), test file for a test-repair goal (+15), source extension (+10),
 * depth penalty (-2 per separator) and a large-file penalty (-10 over 64 KiB). Unsafe paths are
 * excluded before scoring. Ordering is score descending, then path ascending, and is stable.
 */
object SourceRelevance {
    private const val MAX_RESULTS = 64
    private const val MAX_TEXT_CHARS = 8_000
    private const val MAX_PATH_CHARS = 512
    private const val LARGE_FILE_BYTES = 65_536
    private const val MAX_REASONS = 4

    private val PROTECTED = setOf(".git", "build", "node_modules", ".gradle")
    private val SOURCE_EXTENSIONS = setOf("kt", "java", "c", "cpp", "h", "py", "js", "ts", "rs", "go")
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{2,}")
    private val TEST_GOAL = Regex("\\btests?\\b|\\btesting\\b|\\bfailing\\b")
    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "this", "that", "from", "into", "make", "them", "pass",
        "please", "fix", "failing", "failure", "build", "builds", "test", "tests", "testing",
        "add", "new", "change", "update", "modify", "verify", "inspect", "report", "project",
        "existing", "artifact", "should", "when", "then", "also", "have", "has", "not", "are",
        "was", "were", "all", "any", "can", "will", "would", "could", "its", "use", "using",
        "file", "files", "code", "source", "line", "lines", "error", "errors", "compile", "run",
        "running", "after", "before", "there", "their", "about", "want", "need", "needs", "like",
        "just", "only", "more", "most", "some", "such", "than", "very", "well", "what", "which",
        "while", "who", "why", "how", "your", "our", "you", "but", "because", "does", "did",
        "done", "been", "being", "over", "under", "again", "still", "same", "other", "each",
        "every", "both", "few", "many", "much", "here", "where", "them", "these", "those",
    )

    /**
     * Ranks [candidates] for [goal].
     *
     * @param failurePaths paths reported by a failing build or test run; exact matches dominate.
     * @param diagnostics compiler or test output; mentions are a strong signal.
     * @param limit requested result count, coerced into `1..64`.
     * @return at most `limit` ranked paths; empty when nothing is safe or relevant.
     */
    fun rank(
        candidates: List<SourceCandidate>,
        goal: String,
        failurePaths: List<String>,
        diagnostics: String,
        limit: Int,
    ): List<RankedSource> {
        val cap = limit.coerceIn(1, MAX_RESULTS)
        val goalText = goal.lowercase().take(MAX_TEXT_CHARS)
        val diagnosticText = diagnostics.lowercase().take(MAX_TEXT_CHARS)
        val failures = failurePaths.mapNotNull { normalizeFailure(it) }.toSet()
        val context = Context(
            goal = goalText,
            diagnostics = diagnosticText,
            identifiers = identifiers(goalText),
            failures = failures,
            failureDirectories = failures.mapNotNull { directoryOf(it) }.toSet(),
            testGoal = TEST_GOAL.containsMatchIn(goalText),
        )
        return candidates
            .filter { isSafe(it.path) }
            .map { score(it, context) }
            .sortedWith(compareByDescending<RankedSource> { it.score }.thenBy { it.path })
            .take(cap)
    }

    private fun score(candidate: SourceCandidate, context: Context): RankedSource {
        val path = candidate.path
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        val directory = lower.substringBeforeLast('/', "")
        val stem = name.substringBeforeLast('.', name)
        var score = 0
        val reasons = ArrayList<String>(MAX_REASONS)

        if (mentions(context.goal, name)) {
            score += 60
            reasons += "basename in goal"
        }
        val segment = lower.split('/').dropLast(1).firstOrNull { it in context.identifiers }
            ?: stem.takeIf { it in context.identifiers }
        if (segment != null) {
            score += 30
            reasons += "segment " + segment
        }
        if (context.failures.any { it == path || it.endsWith("/" + path) }) {
            score += 80
            reasons += "failure path"
        } else if (directory.isNotEmpty() &&
            context.failureDirectories.any { it == directory || it.endsWith("/" + directory) }
        ) {
            score += 25
            reasons += "failure directory"
        }
        val identifier = context.identifiers.firstOrNull { mentions(lower, it) }
        if (identifier != null && identifier != segment) {
            score += 20
            reasons += "identifier " + identifier
        }
        if (mentions(context.diagnostics, name) ||
            (path.length >= 4 && context.diagnostics.contains(lower))
        ) {
            score += 40
            reasons += "mentioned in diagnostics"
        }
        val extension = name.substringAfterLast('.', "")
        if (extension in SOURCE_EXTENSIONS) {
            score += 10
            reasons += "source file"
        }
        if (context.testGoal && isTestPath(lower, name)) {
            score += 15
            reasons += "test file"
        }
        val depth = path.count { it == '/' }
        if (depth > 0) {
            score -= 2 * depth
            if (depth >= 4) reasons += "depth -" + (2 * depth)
        }
        if (candidate.sizeBytes > LARGE_FILE_BYTES) {
            score -= 10
            reasons += "large file"
        }
        val kept = if (reasons.isEmpty()) listOf("no strong signal") else reasons.take(MAX_REASONS)
        return RankedSource(path, score, kept)
    }

    private fun identifiers(text: String): List<String> {
        val result = LinkedHashSet<String>()
        for (match in IDENTIFIER.findAll(text)) {
            val word = match.value.lowercase()
            if (word.length > 64 || word in STOP_WORDS || word.all { it.isDigit() }) continue
            result += word
        }
        return result.toList()
    }

    private fun isSafe(path: String): Boolean {
        if (path.isBlank() || path.length > MAX_PATH_CHARS) return false
        if (path.startsWith('/') || path.contains("..") || path.contains('\\') || path.contains('\u0000')) {
            return false
        }
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        return segments.none { it.lowercase() in PROTECTED }
    }

    private fun isTestPath(lowerPath: String, name: String): Boolean {
        val segments = lowerPath.split('/')
        if (segments.any { it == "test" || it == "tests" || it == "androidtest" }) return true
        return name.endsWith("test.kt") || name.endsWith("tests.kt") || name.endsWith("_test.go") ||
            name.endsWith("test.py") || name.startsWith("test_") ||
            name.endsWith(".test.ts") || name.endsWith(".test.js")
    }

    private fun normalizeFailure(value: String): String? {
        val trimmed = value.trim().replace('\\', '/').removePrefix("./")
        return trimmed.takeIf { it.isNotEmpty() && it.length <= MAX_PATH_CHARS }
    }

    private fun directoryOf(path: String): String? =
        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && it != "." }

    private fun mentions(text: String, word: String): Boolean {
        if (text.isEmpty() || word.isEmpty()) return false
        var index = text.indexOf(word)
        while (index >= 0) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + word.length)
            val leftOk = before == null || !before.isLetterOrDigit()
            val rightOk = after == null || !after.isLetterOrDigit()
            if (leftOk && rightOk) return true
            index = text.indexOf(word, index + 1)
        }
        return false
    }

    private data class Context(
        val goal: String,
        val diagnostics: String,
        val identifiers: List<String>,
        val failures: Set<String>,
        val failureDirectories: Set<String>,
        val testGoal: Boolean,
    )
}

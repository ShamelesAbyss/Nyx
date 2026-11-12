package com.nyx.ai.development

/** The kind of development work a goal asks for. */
enum class GoalKind { DIAGNOSE, REPAIR_BUILD, REPAIR_TEST, MODIFY_FEATURE, CREATE_FEATURE, VERIFY_ARTIFACT }

/**
 * Deterministic classification of a goal.
 *
 * @property kind the work the goal asks for.
 * @property confidence bounded score in `0.0..1.0`; more matching signals raise it.
 * @property reasons 1..5 short, human-readable signals that produced [kind].
 */
data class GoalClassification(
    val kind: GoalKind,
    val confidence: Double,
    val reasons: List<String>,
)

/**
 * Keyword-only goal classifier.
 *
 * The goal is lowercased, tokenized, suffix-stripped and matched against fixed word sets. No model,
 * no randomness and no locale-sensitive operation is involved, so the same goal always produces the
 * same [GoalClassification]. Only the first [MAX_GOAL_CHARS] characters are inspected.
 *
 * Precedence: repair words plus build/test context, then feature verbs with new functionality, then
 * change verbs, then verification, then diagnosis. Anything else defaults to [GoalKind.MODIFY_FEATURE]
 * with low confidence.
 */
object GoalClassifier {
    /** Goals longer than this are truncated before analysis. */
    const val MAX_GOAL_CHARS = 8_000

    private const val MAX_REASONS = 5
    private val TOKEN = Regex("[a-z0-9_]+")

    private val REPAIR_WORDS = setOf(
        "fix", "repair", "resolve", "resolv", "fail", "failure", "broken", "break",
        "error", "crash", "regression", "bug", "wrong", "flaky",
    )
    private val BUILD_WORDS = setOf(
        "build", "compile", "compil", "compiler", "compilation", "gradle", "assemble", "assembl",
        "kotlinc", "javac", "apk", "aar", "cmake", "ninja",
    )
    private val TEST_WORDS = setOf(
        "test", "junit", "spec", "suite", "coverage", "assert", "assertion", "pytest", "jest", "vitest",
    )
    private val FEATURE_WORDS = setOf(
        "add", "create", "creat", "implement", "introduce", "introduc", "support", "enable",
        "scaffold", "generate", "generat", "provision", "expose", "expos",
    )
    private val CHANGE_WORDS = setOf(
        "change", "chang", "update", "updat", "modify", "modifi", "rename", "renam", "adjust",
        "tweak", "replace", "refactor", "move", "remove", "delete", "switch", "convert", "set",
    )
    private val VERIFY_WORDS = setOf(
        "verify", "verifi", "validate", "validat", "confirm", "check", "ensure", "artifact", "prove",
    )
    private val DIAGNOSE_WORDS = setOf(
        "inspect", "analyze", "analyz", "analyse", "analys", "report", "diagnose", "diagnos",
        "explain", "describe", "review", "explore", "examine", "structure", "overview", "summarize",
        "audit", "investigate", "understand",
    )
    private val NEW_WORDS = setOf("new", "additional", "another", "fresh", "extra")
    private val FEATURE_NOUNS = setOf(
        "function", "method", "class", "feature", "endpoint", "command", "option", "module",
        "screen", "component", "api", "capability", "mode", "hook", "handler", "parser", "formatter",
        "route", "service", "test",
    )

    /**
     * Classifies [goal] without any model call.
     *
     * @return a classification whose reasons are never empty and whose confidence is in `0.0..1.0`.
     */
    fun classify(goal: String): GoalClassification {
        val tokens = tokens(goal.lowercase().take(MAX_GOAL_CHARS))
        val repair = signal("repair", REPAIR_WORDS, tokens)
        val build = signal("build", BUILD_WORDS, tokens)
        val test = signal("test", TEST_WORDS, tokens)
        val feature = signal("feature", FEATURE_WORDS, tokens)
        val change = signal("change", CHANGE_WORDS, tokens)
        val verify = signal("verify", VERIFY_WORDS, tokens)
        val diagnose = signal("diagnose", DIAGNOSE_WORDS, tokens)
        val fresh = signal("new", NEW_WORDS, tokens) ?: signal("target", FEATURE_NOUNS, tokens)
        return when {
            repair != null && build != null -> result(GoalKind.REPAIR_BUILD, listOfNotNull(repair, build, test))
            repair != null && test != null -> result(GoalKind.REPAIR_TEST, listOfNotNull(repair, test))
            feature != null && (fresh != null || change == null) ->
                result(GoalKind.CREATE_FEATURE, listOfNotNull(feature, fresh))
            change != null -> result(GoalKind.MODIFY_FEATURE, listOfNotNull(change, feature))
            feature != null -> result(GoalKind.CREATE_FEATURE, listOf(feature))
            verify != null -> result(GoalKind.VERIFY_ARTIFACT, listOfNotNull(verify, build))
            diagnose != null -> result(GoalKind.DIAGNOSE, listOf(diagnose))
            else -> GoalClassification(
                GoalKind.MODIFY_FEATURE,
                0.2,
                listOf("no explicit signal", "defaulted to a feature change"),
            )
        }
    }

    private fun result(kind: GoalKind, signals: List<String>): GoalClassification {
        val reasons = signals.take(MAX_REASONS)
        val confidence = (0.45 + 0.15 * reasons.size).coerceIn(0.05, 0.98)
        return GoalClassification(kind, confidence, reasons)
    }

    private fun signal(label: String, words: Set<String>, tokens: Set<String>): String? {
        val hits = words.filter { it in tokens }.sorted().take(3)
        return if (hits.isEmpty()) null else label + ": " + hits.joinToString(", ")
    }

    private fun tokens(text: String): Set<String> {
        val raw = TOKEN.findAll(text).mapTo(mutableSetOf()) { it.value }
        raw += raw.map { stem(it) }
        return raw
    }

    /** Small, fixed suffix stripper; only used to align inflections such as "failing" and "fails". */
    private fun stem(word: String): String = when {
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        word.length > 4 && word.endsWith("es") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }
}

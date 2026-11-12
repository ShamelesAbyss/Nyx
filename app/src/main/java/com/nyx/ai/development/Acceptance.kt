package com.nyx.ai.development

/**
 * The evidence contract a goal must satisfy before it may be reported complete.
 *
 * @property goalKind the classified kind of work.
 * @property requestedChange the bounded goal text this contract was derived from.
 * @property expectedArtifact a concrete artifact named in the goal, when one was named.
 * @property requiredCommands project commands that must be exercised to gather evidence.
 * @property requiresSourceChange whether a source file must actually change.
 * @property requiresNewTestEvidence whether the test count must grow (or an acceptance check pass).
 * @property failureConditions human-readable reasons the goal cannot be accepted.
 */
data class AcceptanceCriteria(
    val goalKind: GoalKind,
    val requestedChange: String,
    val expectedArtifact: String?,
    val requiredCommands: Set<ProjectRunKind>,
    val requiresSourceChange: Boolean,
    val requiresNewTestEvidence: Boolean,
    val failureConditions: List<String>,
)

/** Terminal verdict for a development goal. Only evidence, never model prose, selects it. */
enum class DevelopmentOutcome {
    BASELINE_HEALTHY,
    REPAIR_VERIFIED,
    FEATURE_IMPLEMENTED_AND_VERIFIED,
    GOAL_NOT_VERIFIED,
    ENVIRONMENT_UNAVAILABLE,
    HUMAN_REVIEW_REQUIRED,
}

/**
 * Observed evidence for one goal.
 *
 * @property baselinePassed whether build and tests passed before any change.
 * @property appliedFileCount files written by the agent.
 * @property changedSourceCount files whose bytes actually changed.
 * @property postBuildPassed build result after the change.
 * @property postTestsPassed test result after the change.
 * @property acceptanceCheckPassed optional explicit acceptance check; `null` means not run.
 * @property testCountBaseline observed test count before the change, when measurable.
 * @property testCountAfter observed test count after the change, when measurable.
 * @property infrastructureOk whether the toolchain was available.
 * @property budgetExhausted whether the run stopped on a budget rather than on evidence.
 * @property reviewRequired whether a human must review the result.
 */
data class AcceptanceEvidence(
    val baselinePassed: Boolean,
    val appliedFileCount: Int,
    val changedSourceCount: Int,
    val postBuildPassed: Boolean,
    val postTestsPassed: Boolean,
    val acceptanceCheckPassed: Boolean?,
    val testCountBaseline: Int?,
    val testCountAfter: Int?,
    val infrastructureOk: Boolean,
    val budgetExhausted: Boolean,
    val reviewRequired: Boolean,
)

/**
 * Derives acceptance criteria and evaluates observed evidence.
 *
 * The evaluator is deliberately strict: a healthy baseline never counts as a successful feature,
 * and a repair is only verified when the post-change build and tests pass after a real source
 * change. Model prose is not accepted as evidence anywhere.
 */
object AcceptanceEvaluator {
    private const val MAX_GOAL_CHARS = 8_000
    private val TEST_WORD = Regex("\\btests?\\b|\\btesting\\b")
    private val ARTIFACT = Regex("[A-Za-z0-9_][A-Za-z0-9_./-]*\\.[A-Za-z0-9]{1,8}")

    /** Builds the contract for [kind] from the goal text. Blank goals are accepted and bounded. */
    fun criteriaFor(kind: GoalKind, goal: String): AcceptanceCriteria {
        val text = goal.trim().take(MAX_GOAL_CHARS)
        val lower = text.lowercase()
        return AcceptanceCriteria(
            goalKind = kind,
            requestedChange = text,
            expectedArtifact = artifactOf(text),
            requiredCommands = commandsFor(kind),
            requiresSourceChange = requiresSourceChange(kind),
            requiresNewTestEvidence = kind == GoalKind.CREATE_FEATURE ||
                (kind == GoalKind.MODIFY_FEATURE && TEST_WORD.containsMatchIn(lower)),
            failureConditions = failureConditions(kind),
        )
    }

    /**
     * Evaluates [evidence] against [criteria].
     *
     * Precedence: missing infrastructure, then required human review, then the kind-specific rule.
     * An exhausted budget can only withhold success, never create it.
     */
    fun evaluate(criteria: AcceptanceCriteria, evidence: AcceptanceEvidence): DevelopmentOutcome {
        if (!evidence.infrastructureOk) return DevelopmentOutcome.ENVIRONMENT_UNAVAILABLE
        if (evidence.reviewRequired) return DevelopmentOutcome.HUMAN_REVIEW_REQUIRED
        val verified = when (criteria.goalKind) {
            GoalKind.REPAIR_BUILD, GoalKind.REPAIR_TEST -> {
                if (evidence.baselinePassed) return DevelopmentOutcome.BASELINE_HEALTHY
                evidence.postBuildPassed && evidence.postTestsPassed && evidence.changedSourceCount >= 1
            }
            GoalKind.CREATE_FEATURE, GoalKind.MODIFY_FEATURE -> featureVerified(criteria, evidence)
            GoalKind.VERIFY_ARTIFACT -> evidence.acceptanceCheckPassed == true ||
                (evidence.postBuildPassed && evidence.postTestsPassed)
            GoalKind.DIAGNOSE -> evidence.acceptanceCheckPassed == true
        }
        if (!verified) return DevelopmentOutcome.GOAL_NOT_VERIFIED
        return when (criteria.goalKind) {
            GoalKind.REPAIR_BUILD, GoalKind.REPAIR_TEST -> DevelopmentOutcome.REPAIR_VERIFIED
            GoalKind.CREATE_FEATURE, GoalKind.MODIFY_FEATURE, GoalKind.VERIFY_ARTIFACT,
            GoalKind.DIAGNOSE -> DevelopmentOutcome.FEATURE_IMPLEMENTED_AND_VERIFIED
        }
    }

    private fun featureVerified(criteria: AcceptanceCriteria, evidence: AcceptanceEvidence): Boolean {
        if (evidence.changedSourceCount < 1) return false
        if (!evidence.postBuildPassed || !evidence.postTestsPassed) return false
        if (evidence.acceptanceCheckPassed == false) return false
        if (!criteria.requiresNewTestEvidence) return true
        val baseline = evidence.testCountBaseline
        val after = evidence.testCountAfter
        val grew = baseline != null && after != null && after > baseline
        return grew || evidence.acceptanceCheckPassed == true
    }

    private fun commandsFor(kind: GoalKind): Set<ProjectRunKind> = when (kind) {
        GoalKind.REPAIR_BUILD -> setOf(ProjectRunKind.BUILD)
        GoalKind.REPAIR_TEST -> setOf(ProjectRunKind.TEST)
        GoalKind.CREATE_FEATURE, GoalKind.MODIFY_FEATURE, GoalKind.VERIFY_ARTIFACT ->
            setOf(ProjectRunKind.BUILD, ProjectRunKind.TEST)
        GoalKind.DIAGNOSE -> emptySet()
    }

    private fun requiresSourceChange(kind: GoalKind): Boolean = when (kind) {
        GoalKind.REPAIR_BUILD, GoalKind.REPAIR_TEST, GoalKind.CREATE_FEATURE,
        GoalKind.MODIFY_FEATURE -> true
        GoalKind.DIAGNOSE, GoalKind.VERIFY_ARTIFACT -> false
    }

    private fun failureConditions(kind: GoalKind): List<String> = when (kind) {
        GoalKind.REPAIR_BUILD -> listOf(
            "baseline build must fail before the repair",
            "post-repair build must pass",
            "post-repair tests must pass",
            "at least one source file must change",
            "build environment must be available",
        )
        GoalKind.REPAIR_TEST -> listOf(
            "baseline tests must fail before the repair",
            "post-repair tests must pass",
            "post-repair build must pass",
            "at least one source file must change",
            "test environment must be available",
        )
        GoalKind.CREATE_FEATURE, GoalKind.MODIFY_FEATURE -> listOf(
            "at least one source file must change",
            "post-change build must pass",
            "post-change tests must pass",
            "acceptance check must not fail",
            "new test evidence is required when requested",
        )
        GoalKind.VERIFY_ARTIFACT -> listOf(
            "artifact check must pass, or build and tests must pass",
            "verification environment must be available",
        )
        GoalKind.DIAGNOSE -> listOf(
            "acceptance check must pass",
            "diagnosis environment must be available",
        )
    }

    private fun artifactOf(goal: String): String? = ARTIFACT.find(goal)?.value
        ?.takeIf { it.length <= 200 && !it.startsWith("/") && !it.contains("..") }
}

package com.nyx.ai.autonomy.policy

/**
 * How much autonomy the user has delegated to NYX.
 *
 * The mode is a ceiling, never a bypass: no mode overrides Android runtime
 * permissions, root availability, or an explicit emergency stop.
 */
enum class AutonomyMode { MANUAL, SMART, FULL }

/** A single side effect NYX can attempt on a device or workspace. */
enum class ActionKind {
    READ_WORKSPACE,
    WRITE_WORKSPACE,
    DELETE_WORKSPACE,
    RUN_READONLY_COMMAND,
    RUN_MUTATING_COMMAND,
    INSTALL_PACKAGE,
    NETWORK_READ,
    NETWORK_DOWNLOAD,
    NETWORK_UPLOAD,
    GIT_COMMIT,
    GIT_PUSH,
    PUBLISH,
    INSTALL_APK,
    SYSTEM_CONFIG,
    USE_CREDENTIAL,
    DELEGATE,
}

/** Ordered severity of an action, from harmless reads to irreversible effects. */
enum class RiskLevel { ROUTINE, ELEVATED, HIGH, CRITICAL }

/**
 * An intent to act, described before it happens.
 *
 * [insideAuthorizedWorkspace] and [trustedSource] are caller assertions about
 * context; they are only trusted because the caller is the policy layer's own
 * execution pipeline, never model output parsed directly.
 */
data class ActionRequest(
    val kind: ActionKind,
    val target: String,
    val environmentId: String = "termux_host",
    val insideAuthorizedWorkspace: Boolean = false,
    val destructive: Boolean = false,
    val estimatedBytes: Long? = null,
    val trustedSource: Boolean = false,
    val privateDataOutbound: Boolean = false,
    val delegationDepth: Int = 0,
)

/**
 * A time-bounded capability token.
 *
 * [environmentId] may be `"*"` to cover every environment. [scope] may be
 * `"*"` or a path prefix that covers the target itself and anything below it.
 */
data class CapabilityGrant(
    val id: String,
    val capability: String,
    val environmentId: String,
    val scope: String,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val revoked: Boolean = false,
)

/** Classified risk with short, human-readable justifications. */
data class ActionRisk(val level: RiskLevel, val reasons: List<String>, val requiresApproval: Boolean)

/**
 * The outcome of [AutonomyPolicyEngine.authorize].
 *
 * [reason] is a stable snake_case code (for example `smart_high_requires_approval`
 * or `emergency_stop`) safe to log and branch on. [allowed] is false whenever
 * [requiresApproval] is true: nothing runs until a human confirms.
 */
data class AuthorizationDecision(
    val allowed: Boolean,
    val requiresApproval: Boolean,
    val reason: String,
    val risk: RiskLevel,
)

/**
 * A narrowing scope handed to a delegated sub-agent.
 *
 * Empty sets mean "no restriction on this axis"; the delegation can never widen
 * the parent's grants, because the ordinary capability check still applies.
 */
data class DelegatedPermissionScope(
    val allowedKinds: Set<ActionKind>,
    val maxRisk: RiskLevel = RiskLevel.ELEVATED,
    val environmentIds: Set<String> = emptySet(),
    val scopePrefixes: List<String> = emptyList(),
    val maxDepth: Int = 1,
)

/** A persistable view of the policy state. */
data class PolicySnapshot(
    val mode: AutonomyMode,
    val stopped: Boolean,
    val grants: List<CapabilityGrant>,
)

/** One authorization decision, as written to the audit journal. */
data class AuthorizationEntry(
    val at: Long,
    val mode: AutonomyMode,
    val kind: ActionKind,
    val target: String,
    val risk: RiskLevel,
    val decision: String,
    val reason: String,
)

/** Capability token required for an [ActionKind]. */
internal fun capabilityFor(kind: ActionKind): String = when (kind) {
    ActionKind.READ_WORKSPACE -> "workspace.read"
    ActionKind.WRITE_WORKSPACE -> "workspace.write"
    ActionKind.DELETE_WORKSPACE -> "workspace.delete"
    ActionKind.RUN_READONLY_COMMAND -> "command.readonly"
    ActionKind.RUN_MUTATING_COMMAND -> "command.mutating"
    ActionKind.INSTALL_PACKAGE -> "packages.install"
    ActionKind.NETWORK_READ -> "network.read"
    ActionKind.NETWORK_DOWNLOAD -> "network.download"
    ActionKind.NETWORK_UPLOAD -> "network.upload"
    ActionKind.GIT_COMMIT -> "git.commit"
    ActionKind.GIT_PUSH -> "git.push"
    ActionKind.PUBLISH -> "publish"
    ActionKind.INSTALL_APK -> "apk.install"
    ActionKind.SYSTEM_CONFIG -> "system.config"
    ActionKind.USE_CREDENTIAL -> "credential.use"
    ActionKind.DELEGATE -> "delegate"
}

/** Reads that [AutonomyMode.MANUAL] treats as harmless checks. */
internal val HARMLESS_READS: Set<ActionKind> = setOf(
    ActionKind.READ_WORKSPACE,
    ActionKind.RUN_READONLY_COMMAND,
)

/**
 * True when [scope] covers [target] or anything below it.
 *
 * Matching is path-aware, so `/root/p` covers `/root/p/x` but not `/root/p2`.
 */
internal fun scopeCovers(scope: String, target: String): Boolean {
    if (scope == "*") return true
    val base = scope.trimEnd('/')
    if (base.isEmpty()) {
        // A bare root prefix covers every absolute path.
        return scope.startsWith("/") && target.startsWith("/")
    }
    val normalizedTarget = target.trimEnd('/')
    return normalizedTarget == base || normalizedTarget.startsWith("$base/")
}

/** One escalation step, clamped at [RiskLevel.CRITICAL]. */
private fun RiskLevel.escalate(): RiskLevel = when (this) {
    RiskLevel.ROUTINE -> RiskLevel.ELEVATED
    RiskLevel.ELEVATED -> RiskLevel.HIGH
    RiskLevel.HIGH -> RiskLevel.CRITICAL
    RiskLevel.CRITICAL -> RiskLevel.CRITICAL
}

/** Baseline risk of every action kind, before contextual escalation. */
private fun baseRiskOf(kind: ActionKind): RiskLevel = when (kind) {
    ActionKind.READ_WORKSPACE, ActionKind.NETWORK_READ -> RiskLevel.ROUTINE

    ActionKind.WRITE_WORKSPACE, ActionKind.RUN_READONLY_COMMAND, ActionKind.RUN_MUTATING_COMMAND,
    ActionKind.NETWORK_DOWNLOAD, ActionKind.GIT_COMMIT, ActionKind.DELEGATE -> RiskLevel.ELEVATED

    ActionKind.DELETE_WORKSPACE, ActionKind.INSTALL_PACKAGE, ActionKind.NETWORK_UPLOAD,
    ActionKind.GIT_PUSH, ActionKind.INSTALL_APK -> RiskLevel.HIGH

    ActionKind.PUBLISH, ActionKind.SYSTEM_CONFIG, ActionKind.USE_CREDENTIAL -> RiskLevel.CRITICAL
}

/**
 * HIGH-risk actions that still need a human in [AutonomyMode.FULL]: private data
 * leaving the device, publishing, APK installation, and destructive work outside
 * the authorized workspace.
 */
internal fun highRiskNeedsHumanReview(request: ActionRequest): Boolean =
    request.privateDataOutbound ||
        request.kind == ActionKind.PUBLISH ||
        request.kind == ActionKind.INSTALL_APK ||
        (request.destructive && !request.insideAuthorizedWorkspace)

/**
 * Assigns a [RiskLevel] to an [ActionRequest] and explains why.
 *
 * The classifier is pure and mode-aware only for its advisory
 * [ActionRisk.requiresApproval] flag; [AutonomyPolicyEngine] owns the decision.
 */
class RiskClassifier {
    /**
     * Classifies [request] under [mode].
     *
     * Baseline levels come from the policy table. Destructive actions, outbound
     * private data, untrusted package sources, and delegated execution each add
     * one escalation step; multiple triggers do not stack.
     */
    fun classify(request: ActionRequest, mode: AutonomyMode): ActionRisk {
        val base = baseRiskOf(request.kind)
        val triggers = buildList {
            if (request.destructive) add("destructive")
            if (request.privateDataOutbound) add("private data outbound")
            if (request.kind == ActionKind.INSTALL_PACKAGE && !request.trustedSource) add("untrusted package source")
            if (request.delegationDepth > 0) add("delegated depth ${request.delegationDepth}")
        }
        val level = if (triggers.isEmpty()) base else base.escalate()
        val reasons = buildList {
            add("${request.kind.readableName()} is ${base.name.lowercase()} risk by default")
            if (triggers.isNotEmpty()) {
                add("escalated to ${level.name.lowercase()} because ${triggers.joinToString(", ")}")
            }
            if (request.destructive && level == RiskLevel.CRITICAL) {
                add("irreversible effects need explicit human intent")
            }
        }.take(MAX_REASONS)
        return ActionRisk(
            level = level,
            reasons = reasons,
            requiresApproval = requiresApproval(level, request, mode),
        )
    }

    private fun requiresApproval(
        level: RiskLevel,
        request: ActionRequest,
        mode: AutonomyMode,
    ): Boolean = when (mode) {
        AutonomyMode.MANUAL -> request.kind !in HARMLESS_READS
        AutonomyMode.SMART -> level == RiskLevel.HIGH || level == RiskLevel.CRITICAL
        AutonomyMode.FULL -> level == RiskLevel.CRITICAL ||
            (level == RiskLevel.HIGH && highRiskNeedsHumanReview(request))
    }

    private fun ActionKind.readableName(): String = name.lowercase().replace('_', ' ')

    private companion object {
        /** Keeps journal and UI text bounded. */
        const val MAX_REASONS = 4
    }
}

/**
 * Bounded in-memory audit log of authorization decisions.
 *
 * The newest [capacity] entries are retained; older entries are dropped.
 */
class AuthorizationJournal(private val capacity: Int = 200) {
    private val buffer = ArrayDeque<AuthorizationEntry>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** Appends [entry], evicting the oldest record when full. */
    fun record(entry: AuthorizationEntry) {
        buffer.addLast(entry)
        while (buffer.size > capacity) {
            buffer.removeFirst()
        }
    }

    /** Returns a copy of the retained entries, oldest first. */
    fun entries(): List<AuthorizationEntry> = buffer.toList()

    /** Drops every retained entry. */
    fun clear() {
        buffer.clear()
    }
}

/**
 * Decides whether an [ActionRequest] may proceed.
 *
 * The engine is pure Kotlin with an injectable clock, so it is safe to exercise
 * from JVM tests and from the execution pipeline. It never performs the action
 * itself and never grants Android permissions.
 *
 * Mode policy, in addition to the stop flag and any delegated scope:
 * - [AutonomyMode.MANUAL] allows workspace reads and read-only commands; every
 *   other kind waits for a human, and critical kinds are refused unless granted,
 *   in which case they still wait.
 * - [AutonomyMode.SMART] auto-allows routine work inside the workspace, elevated
 *   work only with a grant, and asks for high risk. Critical work is refused.
 * - [AutonomyMode.FULL] auto-allows granted routine and elevated work, routine work
 *   inside the workspace, and granted high-risk work unless it is exfiltration,
 *   publishing, an APK install, or destructive work outside the workspace.
 *   Critical work is refused.
 *
 * Public operations are synchronized. Emergency Stop and revocation are visible
 * to the next authorization, even when callers use different coroutines.
 */
class AutonomyPolicyEngine(
    initialMode: AutonomyMode = AutonomyMode.MANUAL,
    grants: List<CapabilityGrant> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val classifier = RiskClassifier()
    private val journal = AuthorizationJournal()
    @Volatile
    private var activeGrants: List<CapabilityGrant> = grants.toList()

    /** Current autonomy ceiling. */
    @Volatile
    var mode: AutonomyMode = initialMode
        private set

    /** True after [emergencyStop] until [resetStop]. */
    @Volatile
    var stopped: Boolean = false
        private set

    /** Replaces the mode ceiling. */
    @Synchronized
    fun updateMode(mode: AutonomyMode) {
        this.mode = mode
    }

    /** Replaces every grant, for example after loading persisted state. */
    @Synchronized
    fun replaceGrants(grants: List<CapabilityGrant>) {
        activeGrants = grants.toList()
    }

    /** Adds [grant], replacing any grant that shares its id. */
    @Synchronized
    fun grant(grant: CapabilityGrant) {
        val existing = activeGrants.indexOfLast { it.id == grant.id }
        activeGrants = if (existing >= 0) {
            activeGrants.toMutableList().also { it[existing] = grant }
        } else {
            activeGrants + grant
        }
    }

    /** Marks the grant [grantId] revoked; returns false when no active grant matched. */
    @Synchronized
    fun revoke(grantId: String): Boolean {
        val index = activeGrants.indexOfLast { it.id == grantId && !it.revoked }
        if (index < 0) return false
        activeGrants = activeGrants.toMutableList().also { it[index] = it[index].copy(revoked = true) }
        return true
    }

    /** Stops all autonomous work until [resetStop]. */
    @Synchronized
    fun emergencyStop() {
        stopped = true
    }

    /** Clears the emergency stop. */
    @Synchronized
    fun resetStop() {
        stopped = false
    }

    /**
     * Authorizes [request], optionally constrained by [delegated].
     *
     * When [stopped] every request is denied with reason `emergency_stop`.
     * A delegated scope can only narrow: kind, depth, environment, path prefix,
     * and risk ceiling are checked before the ordinary mode policy runs. Every
     * call appends an [AuthorizationEntry] to [journalEntries].
     */
    @Synchronized
    fun authorize(
        request: ActionRequest,
        delegated: DelegatedPermissionScope? = null,
    ): AuthorizationDecision {
        val risk = classifier.classify(request, mode)
        val decision = when {
            stopped -> deny(risk.level, "emergency_stop")
            delegated != null -> delegationFailure(request, delegated, risk)?.let { deny(risk.level, it) }
                ?: decide(request, risk)
            else -> decide(request, risk)
        }
        journal.record(
            AuthorizationEntry(
                at = now(),
                mode = mode,
                kind = request.kind,
                target = request.target,
                risk = risk.level,
                decision = decisionCode(decision),
                reason = decision.reason,
            ),
        )
        return decision
    }

    /** Immutable view of the current policy state. */
    @Synchronized
    fun snapshot(): PolicySnapshot = PolicySnapshot(mode, stopped, activeGrants.toList())

    /** Audit trail of decisions made by this engine, oldest first. */
    @Synchronized
    fun journalEntries(): List<AuthorizationEntry> = journal.entries()

    private fun decide(request: ActionRequest, risk: ActionRisk): AuthorizationDecision = when (mode) {
        AutonomyMode.MANUAL -> decideManual(request, risk)
        AutonomyMode.SMART -> decideSmart(request, risk)
        AutonomyMode.FULL -> decideFull(request, risk)
    }

    private fun decideManual(request: ActionRequest, risk: ActionRisk): AuthorizationDecision {
        if (request.kind in HARMLESS_READS) {
            return allow(risk.level, "manual_read_only")
        }
        val granted = hasActiveGrant(request)
        val critical = risk.level == RiskLevel.CRITICAL || request.kind in MANUAL_CRITICAL_KINDS
        return when {
            critical && granted -> AuthorizationDecision(
                allowed = false,
                requiresApproval = true,
                reason = "manual_critical_granted_requires_approval",
                risk = risk.level,
            )
            critical -> AuthorizationDecision(false, false, "manual_critical_denied", risk.level)
            granted -> AuthorizationDecision(false, true, "manual_granted_requires_approval", risk.level)
            else -> AuthorizationDecision(false, true, "manual_requires_approval", risk.level)
        }
    }

    private fun decideSmart(request: ActionRequest, risk: ActionRisk): AuthorizationDecision = when (risk.level) {
        RiskLevel.ROUTINE -> when {
            hasActiveGrant(request) -> allow(risk.level, "smart_routine_grant")
            request.insideAuthorizedWorkspace -> allow(risk.level, "smart_routine_workspace")
            else -> deny(risk.level, "smart_routine_not_granted")
        }

        RiskLevel.ELEVATED -> if (hasActiveGrant(request)) {
            allow(risk.level, "smart_elevated_grant")
        } else {
            deny(risk.level, "smart_elevated_grant_required")
        }

        RiskLevel.HIGH -> AuthorizationDecision(false, true, "smart_high_requires_approval", risk.level)
        RiskLevel.CRITICAL -> deny(risk.level, "smart_critical_denied")
    }

    private fun decideFull(request: ActionRequest, risk: ActionRisk): AuthorizationDecision = when (risk.level) {
        RiskLevel.ROUTINE -> when {
            hasActiveGrant(request) -> allow(risk.level, "full_routine_grant")
            request.insideAuthorizedWorkspace -> allow(risk.level, "full_routine_workspace")
            else -> deny(risk.level, "full_routine_not_granted")
        }

        RiskLevel.ELEVATED -> if (hasActiveGrant(request)) {
            allow(risk.level, "full_elevated_grant")
        } else {
            deny(risk.level, "full_elevated_grant_required")
        }

        RiskLevel.HIGH -> when {
            highRiskNeedsHumanReview(request) ->
                AuthorizationDecision(false, true, "full_high_requires_approval", risk.level)

            hasActiveGrant(request) -> allow(risk.level, "full_high_grant")
            else -> deny(risk.level, "full_high_grant_required")
        }

        RiskLevel.CRITICAL -> deny(risk.level, "full_critical_denied")
    }

    private fun delegationFailure(
        request: ActionRequest,
        scope: DelegatedPermissionScope,
        risk: ActionRisk,
    ): String? {
        if (request.kind !in scope.allowedKinds) return "delegation_kind_not_allowed"
        if (request.delegationDepth > scope.maxDepth) return "delegation_depth_exceeded"
        val environmentAllowed = scope.environmentIds.isEmpty() ||
            "*" in scope.environmentIds ||
            request.environmentId in scope.environmentIds
        if (!environmentAllowed) return "delegation_environment_not_allowed"
        if (scope.scopePrefixes.isNotEmpty() && scope.scopePrefixes.none { scopeCovers(it, request.target) }) {
            return "delegation_scope_not_allowed"
        }
        if (risk.level.ordinal > scope.maxRisk.ordinal) return "delegation_risk_exceeded"
        return null
    }

    private fun hasActiveGrant(request: ActionRequest): Boolean {
        val capability = capabilityFor(request.kind)
        val at = now()
        return activeGrants.any { grant ->
            val expiresAt = grant.expiresAt
            grant.capability == capability &&
                !grant.revoked &&
                (expiresAt == null || at < expiresAt) &&
                (grant.environmentId == "*" || grant.environmentId == request.environmentId) &&
                scopeCovers(grant.scope, request.target)
        }
    }

    private fun allow(level: RiskLevel, reason: String): AuthorizationDecision =
        AuthorizationDecision(true, false, reason, level)

    private fun deny(level: RiskLevel, reason: String): AuthorizationDecision =
        AuthorizationDecision(false, false, reason, level)

    private fun decisionCode(decision: AuthorizationDecision): String = when {
        decision.allowed -> "allowed"
        decision.requiresApproval -> "approval_required"
        else -> "denied"
    }

    private companion object {
        /** Kinds MANUAL mode refuses unless they were explicitly granted. */
        val MANUAL_CRITICAL_KINDS = setOf(
            ActionKind.SYSTEM_CONFIG,
            ActionKind.PUBLISH,
            ActionKind.INSTALL_APK,
            ActionKind.USE_CREDENTIAL,
        )
    }
}

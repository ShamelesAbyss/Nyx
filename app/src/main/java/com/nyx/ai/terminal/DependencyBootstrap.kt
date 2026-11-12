package com.nyx.ai.terminal

import com.nyx.ai.development.DevelopmentPaths

/** Result of preparing an environment before an objective continues. */
enum class BootstrapStatus {
    /** Every required tool is already verified; nothing was installed. */
    READY,

    /** The plan was installed and every required tool was re-probed successfully. */
    INSTALLED,

    /** The policy requires explicit approval before the exact plan may run. */
    APPROVAL_REQUIRED,

    /** The environment cannot be prepared with a trusted, compatible package plan. */
    BLOCKED,

    /** Installation or post-install verification failed. */
    FAILED,
}

/** Inputs for one bounded dependency-preparation attempt. */
data class BootstrapRequest(
    val objectiveId: String,
    val projectKind: ProjectKind,
    val environment: TerminalEnvironment,
    val guestAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS,
    val workspace: String,
    val environmentId: String,
)

/** Result of [DependencyBootstrap.prepare], including the exact observed plan and report. */
data class BootstrapOutcome(
    val status: BootstrapStatus,
    val plan: DependencyPlan? = null,
    val report: InstallationReport? = null,
    val message: String = "",
)

/** The small, fakeable surface needed by [DependencyBootstrap]. */
interface DependencyGateway {
    suspend fun probeCapabilities(environment: TerminalEnvironment, guestAlias: String): EnvironmentCapabilities

    suspend fun resolveGuestJdk(guestAlias: String): JdkResolution

    suspend fun install(
        plan: DependencyPlan,
        environment: TerminalEnvironment,
        guestAlias: String,
        objectiveId: String?,
        authorize: (PackageCandidate) -> Boolean,
    ): InstallationReport
}

/** Production adapter over the existing audited terminal repository and installer. */
class TerminalDependencyGateway(private val repository: TerminalRepository) : DependencyGateway {
    override suspend fun probeCapabilities(
        environment: TerminalEnvironment,
        guestAlias: String,
    ): EnvironmentCapabilities = repository.probeCapabilities(environment, guestAlias)

    override suspend fun resolveGuestJdk(guestAlias: String): JdkResolution = repository.resolveGuestJdk(guestAlias)

    override suspend fun install(
        plan: DependencyPlan,
        environment: TerminalEnvironment,
        guestAlias: String,
        objectiveId: String?,
        authorize: (PackageCandidate) -> Boolean,
    ): InstallationReport = DependencyInstaller(repository).install(
        plan = plan,
        environment = environment,
        guestAlias = guestAlias,
        objectiveId = objectiveId,
        authorize = authorize,
    )
}

/**
 * Plans and installs only trusted candidates produced by [DependencyPlanner], then re-probes the
 * environment before reporting success. The orchestrator is deliberately independent of Android so
 * the objective continuation rules can be tested without a device.
 *
 * It never retries an uncertain installation loop: a failed or approval-blocked plan returns to the
 * caller, which must record the exact blocker and stop. A later attempt starts from a fresh probe, so
 * already-installed tools are not installed again.
 */
object DependencyBootstrap {
    private const val MAX_MESSAGE_CHARS = 900

    suspend fun prepare(
        request: BootstrapRequest,
        gateway: DependencyGateway,
        authorize: (PackageCandidate) -> Boolean,
        onProgress: suspend (kind: String, detail: String) -> Unit = { _, _ -> },
    ): BootstrapOutcome {
        if (!DevelopmentPaths.isAbsoluteNormalizedWorkspace(request.workspace)) {
            return BootstrapOutcome(
                BootstrapStatus.BLOCKED,
                message = "The authorized workspace is not an absolute normalized project path.",
            )
        }
        if (request.environmentId.isBlank()) {
            return BootstrapOutcome(BootstrapStatus.BLOCKED, message = "The execution environment identity is missing.")
        }

        val capabilities = gateway.probeCapabilities(request.environment, request.guestAlias)
        val tools = capabilities.toToolStates(request.environmentId)
        val jdk = if (request.environment == TerminalEnvironment.DEBIAN_PROOT) {
            gateway.resolveGuestJdk(request.guestAlias)
        } else {
            null
        }
        val plan = DependencyPlanner.plan(
            projectKind = request.projectKind,
            environmentKind = request.environmentId,
            tools = tools,
            guestJdkPackage = jdk?.packageName,
            jdkDiagnostic = jdk?.diagnostic,
        )
        onProgress("dependency_plan", planSummary(plan))

        if (plan.blockers.isNotEmpty()) {
            return BootstrapOutcome(
                BootstrapStatus.BLOCKED,
                plan = plan,
                message = plan.blockers.first().take(MAX_MESSAGE_CHARS),
            )
        }
        if (plan.missing.isEmpty()) {
            return BootstrapOutcome(
                BootstrapStatus.READY,
                plan = plan,
                message = "All required tools are already verified in ${request.environmentId}.",
            )
        }
        if (!plan.canProceed || plan.candidates.isEmpty()) {
            return BootstrapOutcome(
                BootstrapStatus.BLOCKED,
                plan = plan,
                message = "No trusted, compatible installation plan covers the missing tools.",
            )
        }

        onProgress(
            "dependency_install",
            "Installing ${plan.candidates.size} verified package candidate(s) for ${plan.missing.joinToString { it.tool }}.",
        )
        val report = gateway.install(
            plan = plan,
            environment = request.environment,
            guestAlias = request.guestAlias,
            objectiveId = request.objectiveId,
            authorize = authorize,
        )
        return when {
            report.needsApproval.isNotEmpty() -> BootstrapOutcome(
                BootstrapStatus.APPROVAL_REQUIRED,
                plan = plan,
                report = report,
                message = "Approval is required for ${report.needsApproval.joinToString { it.packageName }} before the objective can continue.",
            )

            report.succeeded -> {
                onProgress(
                    "dependency_verified",
                    "Verified ${report.verifiedTools.joinToString().ifBlank { plan.missing.joinToString { it.tool } }} after installation.",
                )
                BootstrapOutcome(
                    BootstrapStatus.INSTALLED,
                    plan = plan,
                    report = report,
                    message = "Installed and verified ${report.verifiedTools.size} tool(s); the original objective can continue.",
                )
            }

            else -> BootstrapOutcome(
                BootstrapStatus.FAILED,
                plan = plan,
                report = report,
                message = report.message.take(MAX_MESSAGE_CHARS),
            )
        }
    }

    /** Converts an observed capability snapshot into planner evidence, preserving guest origin. */
    fun EnvironmentCapabilities.toToolStates(environmentId: String): Map<String, ToolState> {
        val guest = ExecutableClassifier.isGuestEnvironment(environmentId)
        return tools.mapValues { (_, tool) ->
            ToolState(
                present = tool.state != CapabilityState.MISSING && tool.state != CapabilityState.NOT_CHECKED,
                usable = tool.state == CapabilityState.VERIFIED &&
                    (!guest || tool.origin == ExecutableOrigin.GUEST_NATIVE),
                origin = tool.origin,
                version = tool.version,
            )
        }
    }

    private fun planSummary(plan: DependencyPlan): String = buildString {
        append("Project ${plan.projectKind.name.lowercase()}: ")
        append("${plan.satisfied.size} satisfied, ${plan.missing.size} missing")
        if (plan.candidates.isNotEmpty()) append(", ${plan.candidates.size} candidate(s)")
        if (plan.blockers.isNotEmpty()) append("; blocker: ${plan.blockers.first()}")
    }.take(MAX_MESSAGE_CHARS)
}

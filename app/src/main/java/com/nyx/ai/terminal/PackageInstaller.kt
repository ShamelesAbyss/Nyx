package com.nyx.ai.terminal

/** One package installation attempt and its real process result. */
data class InstallStep(
    val packageName: String,
    val manager: String,
    val command: String,
    val status: String,
    val exitCode: Int? = null,
    val output: String = "",
)

/** Aggregate result of applying a [DependencyPlan]. */
data class InstallationReport(
    val steps: List<InstallStep> = emptyList(),
    val needsApproval: List<PackageCandidate> = emptyList(),
    val verifiedTools: Set<String> = emptySet(),
    val stillMissing: List<String> = emptyList(),
    val message: String = "",
) {
    val succeeded: Boolean get() = needsApproval.isEmpty() && stillMissing.isEmpty() && steps.all { it.status == "succeeded" }
}

/**
 * Applies only commands produced by [DependencyPlanner]. A model cannot supply an arbitrary install
 * command here: the package name and command come from the trusted candidate record, and every step
 * is authorized by the caller before it runs.
 */
class DependencyInstaller(private val repository: TerminalRepository) {
    companion object {
        const val INSTALL_TIMEOUT_SECONDS = 900L
        private val TRANSIENT_FAILURES = listOf(
            "could not get lock",
            "unable to acquire the dpkg frontend lock",
            "temporary failure",
            "temporary failure resolving",
            "connection timed out",
            "network is unreachable",
        )
    }

    /**
     * Runs each candidate for which [authorize] returns true. A candidate that is not authorized is
     * returned in [InstallationReport.needsApproval] and no later package is attempted, so the user
     * can approve the exact plan instead of discovering it halfway through.
     */
    suspend fun install(
        plan: DependencyPlan,
        environment: TerminalEnvironment,
        guestAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS,
        objectiveId: String? = null,
        authorize: (PackageCandidate) -> Boolean,
    ): InstallationReport {
        val pending = plan.candidates.filterNot(authorize)
        if (pending.isNotEmpty()) {
            return InstallationReport(
                needsApproval = pending,
                message = "Approval is required before installing ${pending.size} package(s).",
            )
        }
        val steps = ArrayList<InstallStep>()
        for (candidate in plan.candidates) {
            val step = runCandidate(candidate, environment, guestAlias, objectiveId)
            steps += step
            if (step.status != "succeeded") break
        }
        val refreshed = repository.probeCapabilities(environment, guestAlias)
        val verified = plan.missing.map { it.tool }.filter { tool ->
            refreshed.stateOf(tool) == CapabilityState.VERIFIED &&
                (environment == TerminalEnvironment.TERMUX_HOST ||
                    refreshed.tools[tool]?.origin == ExecutableOrigin.GUEST_NATIVE)
        }.toSet()
        val stillMissing = plan.missing.map { it.tool }.filterNot { it in verified }
        val failedStep = steps.firstOrNull { it.status != "succeeded" }
        return InstallationReport(
            steps = steps,
            verifiedTools = verified,
            stillMissing = stillMissing,
            message = when {
                failedStep != null -> "Installation stopped at ${failedStep.packageName}: ${failedStep.output.take(240)}"
                stillMissing.isNotEmpty() -> "Packages installed but these tools are still unverified: ${stillMissing.joinToString()}."
                else -> "All required tools are verified."
            },
        )
    }

    private suspend fun runCandidate(
        candidate: PackageCandidate,
        environment: TerminalEnvironment,
        guestAlias: String,
        objectiveId: String?,
    ): InstallStep {
        val first = execute(candidate, environment, guestAlias, objectiveId)
        if (first.status == "succeeded" || !isTransient(first.output)) return first
        // One bounded retry for a lock or a temporary network failure. The same strategy is never
        // repeated more than twice, so a persistent failure becomes a reported blocker.
        val second = execute(candidate, environment, guestAlias, objectiveId)
        return if (second.status == "succeeded") second else second.copy(output = (second.output + "\nretry after transient failure").take(4_000))
    }

    private suspend fun execute(
        candidate: PackageCandidate,
        environment: TerminalEnvironment,
        guestAlias: String,
        objectiveId: String?,
    ): InstallStep {
        if (!candidate.trusted || candidate.command.isBlank()) {
            return InstallStep(candidate.packageName, candidate.manager, candidate.command, "refused", null, "Untrusted package source.")
        }
        val result = repository.execute(
            command = candidate.command,
            environment = environment,
            workingDirectory = null,
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            approvedByUser = true,
            objectiveId = objectiveId,
            useTimeoutBinary = true,
            guestAlias = guestAlias,
        )
        val output = (result.stdout + "\n" + result.stderr).trim().take(4_000)
        val succeeded = TerminalResultPolicy.isSuccessfulInstall(result.exitCode, result.kind)
        return InstallStep(
            packageName = candidate.packageName,
            manager = candidate.manager,
            command = candidate.command,
            status = if (succeeded) "succeeded" else "failed",
            exitCode = result.exitCode,
            output = output,
        )
    }

    private fun isTransient(output: String): Boolean {
        val text = output.lowercase()
        return TRANSIENT_FAILURES.any { text.contains(it) }
    }
}

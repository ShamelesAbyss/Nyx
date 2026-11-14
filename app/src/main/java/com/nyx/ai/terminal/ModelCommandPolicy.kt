package com.nyx.ai.terminal

import android.content.Context
import com.nyx.ai.autonomy.policy.ActionKind
import com.nyx.ai.autonomy.policy.ActionRequest
import com.nyx.ai.autonomy.policy.AutonomyRuntime
import com.nyx.ai.development.DevelopmentPaths

/** The authorization surface between [TerminalAgentTools] and the shared autonomy policy. */
fun interface ModelCommandGate {
    suspend fun authorize(
        command: String,
        workingDirectory: String,
        timeoutSeconds: Long,
    ): ModelCommandDecision
}

/** A validated model command. [workingDirectory] is canonical and inside the approved workspace. */
data class ModelCommandDecision(
    val allowed: Boolean,
    val reason: String,
    val workingDirectory: String? = null,
    val timeoutSeconds: Long = ModelCommandPolicy.DEFAULT_TIMEOUT_SECONDS,
    val risk: CommandRisk = CommandRisk.BLOCKED,
    val requiresApproval: Boolean = false,
)

data class ModelCommandRequest(
    val command: String,
    val workspaceRoot: String,
    val workingDirectory: String? = null,
    val timeoutSeconds: Long? = null,
    val environment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST,
    val maximumTimeoutSeconds: Long = ModelCommandPolicy.MAX_TIMEOUT_SECONDS,
)

/**
 * Pure validation for a model-proposed command. It confines the initial working directory to the
 * approved workspace and classifies the command with the existing conservative [CommandPolicy].
 *
 * This is a policy boundary, not an OS sandbox: a shell command can still invoke tools that read or
 * write outside the workspace when the underlying environment permits it. Credential paths and
 * destructive operations are denied here rather than pretending the command is contained.
 */
object ModelCommandPolicy {
    const val DEFAULT_TIMEOUT_SECONDS = 120L
    const val MAX_TIMEOUT_SECONDS = 600L
    const val MAX_WORKING_DIRECTORY_CHARS = 1_024

    private val sensitivePatterns = listOf(
        Regex("""(^|[\s/'"])(?:\.ssh|\.aws|\.gnupg|\.env)(?:/|$|[\s'";|&])"""),
        Regex("""\bid_(?:rsa|ed25519|ecdsa|dsa)\b"""),
        Regex("""(^|[\s/'"])(?:etc/shadow|etc/sudoers|proc/self/environ|data/data)(?:/|$|[\s'";|&])"""),
        Regex("""(?i)(?:^|[\s/'"])(?:credential|secret|keystore|\.pem|\.key)(?:/|$|[\s'";|&])"""),
    )

    private val destructivePatterns = listOf(
        Regex("""\brm\s+(?:-[A-Za-z]*[rR][A-Za-z]*\s+)+"""),
        Regex("""\bgit\s+(?:clean\s+-[A-Za-z]*[fdx]|reset\s+--hard|push\b[^\n]*--force)\b"""),
        Regex("""\b(?:shred|wipefs|mkfs(?:\.\w+)?)\b"""),
    )

    private val publishingPatterns = listOf(
        Regex("""\bgit\s+push\b"""),
        Regex("""\b(?:gh|hub)\s+release\b"""),
        Regex("""\bnpm\s+publish\b"""),
        Regex("""\btwine\s+upload\b"""),
        Regex("""\bdocker\s+push\b"""),
        Regex("""\bcargo\s+publish\b"""),
    )

    fun assess(request: ModelCommandRequest): ModelCommandDecision {
        if (request.maximumTimeoutSeconds !in 1L..7_200L) return denied("Invalid caller-owned command timeout ceiling.")
        val command = request.command.trim()
        if (command.isEmpty()) return denied("The command is empty.")
        if (command.length > CommandPolicy.MAX_COMMAND_CHARS) {
            return denied("The command exceeds ${CommandPolicy.MAX_COMMAND_CHARS} characters.")
        }
        if ('\u0000' in command) return denied("The command contains a NUL character.")
        val workspace = request.workspaceRoot
        if (!DevelopmentPaths.isAbsoluteNormalizedWorkspace(workspace)) {
            return denied("The approved workspace is not an absolute normalized path.")
        }
        val workingDirectory = resolveWorkingDirectory(workspace, request.workingDirectory)
            ?: return denied("The working directory must be the approved workspace or a safe subdirectory of it.")
        if (workingDirectory.length > MAX_WORKING_DIRECTORY_CHARS) {
            return denied("The working directory is too long.")
        }

        ModelShellSyntax.rejection(command)?.let { reason ->
            return denied(reason, workingDirectory)
        }

        val assessment = CommandPolicy.assess(command)
        if (assessment.blocked) {
            return ModelCommandDecision(
                allowed = false,
                reason = assessment.reasons.joinToString("; ").ifBlank { "The command policy blocked this command." },
                workingDirectory = workingDirectory,
                risk = CommandRisk.BLOCKED,
            )
        }
        if (assessment.risk == CommandRisk.PRIVILEGED || assessment.risk == CommandRisk.BLOCKED) {
            return denied("Privileged or blocked commands are never available to a model.", workingDirectory)
        }
        if (sensitivePatterns.any { it.containsMatchIn(command) }) {
            return ModelCommandDecision(
                allowed = false,
                reason = "Credential and private-data paths require explicit human handling.",
                workingDirectory = workingDirectory,
                risk = assessment.risk,
                requiresApproval = true,
            )
        }
        if (publishingPatterns.any { it.containsMatchIn(command) }) {
            return ModelCommandDecision(
                allowed = false,
                reason = "Publishing commands require explicit human approval and are not executed by the model.",
                workingDirectory = workingDirectory,
                risk = CommandRisk.NETWORK_MUTATING,
                requiresApproval = true,
            )
        }
        if (assessment.risk == CommandRisk.DESTRUCTIVE || destructivePatterns.any { it.containsMatchIn(command) }) {
            return ModelCommandDecision(
                allowed = false,
                reason = "Destructive commands require explicit human approval and are not executed by the model.",
                workingDirectory = workingDirectory,
                risk = CommandRisk.DESTRUCTIVE,
                requiresApproval = true,
            )
        }

        val timeout = (request.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS).coerceIn(1L, request.maximumTimeoutSeconds)
        return ModelCommandDecision(
            allowed = true,
            reason = if (assessment.risk == CommandRisk.READ_ONLY) {
                "Read-only command accepted for policy evaluation."
            } else {
                "Command accepted for autonomy-policy evaluation."
            },
            workingDirectory = workingDirectory,
            timeoutSeconds = timeout,
            risk = assessment.risk,
            requiresApproval = assessment.requiresConfirmation,
        )
    }

    /** Resolves a relative or absolute working directory without allowing traversal outside the root. */
    fun resolveWorkingDirectory(workspaceRoot: String, requested: String?): String? {
        val raw = requested?.trim().orEmpty()
        if (raw.isEmpty() || raw == ".") return workspaceRoot
        val candidate = when {
            raw.startsWith('/') -> raw
            raw.startsWith("~") -> return null
            DevelopmentPaths.isSafeRelative(raw) -> "$workspaceRoot/$raw"
            else -> return null
        }
        if (!DevelopmentPaths.isAbsoluteNormalizedWorkspace(candidate)) return null
        if (candidate != workspaceRoot && !candidate.startsWith("$workspaceRoot/")) return null
        return candidate
    }

    private fun denied(reason: String, workingDirectory: String? = null) = ModelCommandDecision(
        allowed = false,
        reason = reason,
        workingDirectory = workingDirectory,
        risk = CommandRisk.BLOCKED,
    )
}

/**
 * Applies the shared autonomy policy to a validated model command. The target is the working
 * directory, so a routine grant scoped to the workspace covers it; a grant for another workspace or
 * environment cannot be reused.
 */
class AutonomyModelCommandGate(
    private val context: Context,
    private val environmentId: String,
    private val workspaceRoot: String,
    private val environment: TerminalEnvironment,
    private val maximumTimeoutSeconds: Long = ModelCommandPolicy.MAX_TIMEOUT_SECONDS,
) : ModelCommandGate {
    override suspend fun authorize(
        command: String,
        workingDirectory: String,
        timeoutSeconds: Long,
    ): ModelCommandDecision {
        val assessment = ModelCommandPolicy.assess(
            ModelCommandRequest(
                command = command,
                workspaceRoot = workspaceRoot,
                workingDirectory = workingDirectory,
                timeoutSeconds = timeoutSeconds,
                environment = environment,
                maximumTimeoutSeconds = maximumTimeoutSeconds,
            ),
        )
        if (!assessment.allowed) return assessment
        val target = assessment.workingDirectory ?: return assessment.copy(allowed = false, reason = "No working directory was resolved.")
        if (assessment.risk != CommandRisk.READ_ONLY) {
            val grants = AutonomyRuntime.policy().snapshot().grants
            if (WorkspaceLifecycle.hasScopeMismatch(grants, environmentId, workspaceRoot, "command.mutating")) {
                return assessment.copy(
                    allowed = false,
                    reason = "Workspace scope mismatch: the authorized policy scope does not cover $workspaceRoot. Reselect the verified workspace before running mutating commands.",
                    requiresApproval = true,
                )
            }
        }
        val kind = if (assessment.risk == CommandRisk.READ_ONLY) {
            ActionKind.RUN_READONLY_COMMAND
        } else {
            ActionKind.RUN_MUTATING_COMMAND
        }
        val decision = AutonomyRuntime.authorize(
            context,
            ActionRequest(
                kind = kind,
                target = target,
                environmentId = environmentId,
                insideAuthorizedWorkspace = true,
            ),
        )
        return if (decision.allowed) {
            assessment.copy(reason = "Authorized by ${decision.reason}.")
        } else {
            assessment.copy(
                allowed = false,
                reason = "Autonomy policy denied the command (${decision.reason}).",
                requiresApproval = decision.requiresApproval,
            )
        }
    }
}

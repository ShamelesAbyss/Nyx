package com.nyx.ai.development

import com.nyx.ai.agent.ToolExecutionEvidence
import java.security.MessageDigest

enum class ProjectRunKind { BUILD, TEST }

data class DevelopmentCommand(
    val kind: ProjectRunKind,
    val command: String,
    val exitCode: Int?,
    val stdout: String = "",
    val stderr: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = System.currentTimeMillis(),
    val infrastructureOk: Boolean = exitCode != null,
) {
    val passed: Boolean get() = infrastructureOk && exitCode == 0
}

data class DevelopmentDiscovery(
    val workspace: String,
    val projectType: String,
    val commands: Map<ProjectRunKind, String> = emptyMap(),
    val missingTools: List<String> = emptyList(),
    val ready: Boolean = false,
    val reason: String = "",
    val markers: List<String> = emptyList(),
    /** Only an observed empty, approved workspace with file-operation tools may be initialized. */
    val canCreateProject: Boolean = false,
)

data class SourceSnapshot(val path: String, val sha256: String, val content: String)
data class PatchFile(val path: String, val expectedSha256: String, val content: String)
data class DevelopmentPatch(val files: List<PatchFile>)
data class CheckpointFile(
    val path: String,
    val before: SourceSnapshot?,
    val afterSha256: String,
    val backupPath: String?,
    val committed: Boolean = true,
)
data class DevelopmentCheckpoint(val id: String, val files: List<CheckpointFile>)
data class PatchApplication(
    val applied: Boolean,
    val checkpoint: DevelopmentCheckpoint? = null,
    val reason: String = "",
    val humanReviewRequired: Boolean = false,
)

/** Implementations enforce remote confinement and stale hashes, not just caller validation. */
interface DevelopmentEnvironment {
    suspend fun discover(): DevelopmentDiscovery
    suspend fun index(maxFiles: Int, maxFileBytes: Int): List<SourceSnapshot>
    suspend fun run(kind: ProjectRunKind): DevelopmentCommand
    suspend fun apply(patch: DevelopmentPatch): PatchApplication
    suspend fun rollback(checkpoint: DevelopmentCheckpoint): PatchApplication
    suspend fun diff(checkpoints: List<DevelopmentCheckpoint>, maxChars: Int): String
    fun drainEvidence(): List<ToolExecutionEvidence>
}

/** Limits are hard ceilings even when the model requests more work. */
data class DevelopmentBudget(
    val maxIterations: Int = 3,
    val maxInferenceCalls: Int = 4,
    val maxElapsedMillis: Long = 300_000,
    val maxEditedFiles: Int = 12,
    val maxFilesPerPatch: Int = 4,
    val maxFileBytes: Int = 64_000,
    val maxTotalEditBytes: Int = 256_000,
    val maxIndexFiles: Int = 64,
    val maxContextChars: Int = 48_000,
    val maxResponseChars: Int = 300_000,
    val maxDiffChars: Int = 32_000,
) {
    init {
        require(maxIterations in 1..20 && maxInferenceCalls in 1..6)
        require(maxElapsedMillis in 1..7_200_000)
        require(maxEditedFiles in 1..100 && maxFilesPerPatch in 1..16)
        require(maxFileBytes in 1..128_000 && maxTotalEditBytes in 1..1_024_000)
        require(maxIndexFiles in 1..256 && maxContextChars in 1_024..128_000)
        require(maxResponseChars in 1..1_200_000 && maxDiffChars in 1..128_000)
    }
}

data class DevelopmentIteration(
    val number: Int,
    val commands: List<DevelopmentCommand>,
    val files: List<String> = emptyList(),
    val checkpointId: String? = null,
    val reason: String,
)
data class DevelopmentEvent(val phase: String, val iteration: Int, val message: String)
data class DevelopmentReport(
    val passed: Boolean,
    val reason: String,
    val discovery: DevelopmentDiscovery?,
    val commands: List<DevelopmentCommand>,
    val files: List<String>,
    val diff: String,
    val iterations: List<DevelopmentIteration>,
    val inferenceCalls: Int,
    val elapsedMillis: Long,
    val checkpoints: List<DevelopmentCheckpoint>,
    /** Original observations are retained separately from model responses and summaries. */
    val evidence: List<ToolExecutionEvidence>,
    val modelResponses: List<String>,
    val humanReviewRequired: Boolean = false,
    val goalKind: GoalKind = GoalKind.MODIFY_FEATURE,
    val outcome: DevelopmentOutcome = DevelopmentOutcome.GOAL_NOT_VERIFIED,
    val acceptance: AcceptanceCriteria? = null,
)

object DevelopmentPaths {
    const val MISSING = "MISSING"
    private val protectedSegments = setOf(
        ".git", ".hg", ".svn", ".ssh", ".aws", ".gnupg", ".nyx-checkpoints",
        ".gradle", ".idea", "node_modules", "build", "dist", "target", "__pycache__", ".venv", "venv",
    )
    fun isSafeRelative(path: String): Boolean {
        if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.startsWith('~')) return false
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        if (parts.any { part -> part.any { !it.isLetterOrDigit() && it !in "._- +@" } }) return false
        if (parts.any { it.lowercase() in protectedSegments }) return false
        val name = parts.last().lowercase()
        return !(name == ".env" || name.startsWith(".env.") || name == "local.properties" ||
            name.contains("credential") || name.contains("secret") || name == "id_rsa" || name == "id_ed25519" ||
            name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".keystore") || name.endsWith(".jks") ||
            name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".apk") || name.endsWith(".jar"))
    }
    fun isAbsoluteNormalizedWorkspace(path: String): Boolean = path.startsWith('/') && path != "/" &&
        path.length <= 1_024 && !path.endsWith('/') && path.split('/').drop(1).all {
            it.isNotBlank() && it != "." && it != ".." && it.all { c -> c.isLetterOrDigit() || c in "._- +@" }
        }
}

fun developmentSha256(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Bounded before/after evidence, independent of git and model-generated prose. */
fun developmentDiff(before: SourceSnapshot?, after: SourceSnapshot?, path: String, maxChars: Int): String {
    if (before?.sha256 == after?.sha256) return ""
    return buildString {
        append("--- a/").append(path).append("\n+++ b/").append(path).append('\n')
        append("@@ whole-file replacement; sha256 ").append(before?.sha256 ?: DevelopmentPaths.MISSING)
            .append(" -> ").append(after?.sha256 ?: DevelopmentPaths.MISSING).append(" @@\n")
        before?.content?.lineSequence()?.forEach { if (length < maxChars) append('-').append(it).append('\n') }
        after?.content?.lineSequence()?.forEach { if (length < maxChars) append('+').append(it).append('\n') }
    }.take(maxChars)
}

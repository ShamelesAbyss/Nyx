package com.nyx.ai.development

import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.terminal.TerminalAgentTools

/** All filesystem/command operations run on the approved remote workspace, never the Android FS. */
class TerminalDevelopmentEnvironment(private val tools: TerminalAgentTools) : DevelopmentEnvironment {
    override suspend fun discover(): DevelopmentDiscovery = tools.inspectProject()
    override suspend fun index(maxFiles: Int, maxFileBytes: Int): List<SourceSnapshot> = tools.indexSources(maxFiles, maxFileBytes)
    override suspend fun run(kind: ProjectRunKind): DevelopmentCommand = tools.runProject(kind)
    override suspend fun apply(patch: DevelopmentPatch): PatchApplication = tools.applyPatch(patch)
    override suspend fun rollback(checkpoint: DevelopmentCheckpoint): PatchApplication = tools.rollbackCheckpoint(checkpoint)
    override suspend fun diff(checkpoints: List<DevelopmentCheckpoint>, maxChars: Int): String = tools.checkpointDiff(checkpoints, maxChars)
    override fun drainEvidence(): List<ToolExecutionEvidence> = tools.drainEvidence()
}

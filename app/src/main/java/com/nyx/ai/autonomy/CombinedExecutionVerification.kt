package com.nyx.ai.autonomy

import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.terminal.FixedProjectExecution

/** Caller-owned internal checks must not be truncated by the model declaration budget. */
object CombinedExecutionVerification {
    suspend fun verify(declared: List<EvidenceSpec>, mandatory: List<EvidenceSpec>, observation: ObservationBundle,
                       failures: Int = 0): VerificationReport {
        require(declared.size <= EvidenceSpecParser.MAX_SPECS && mandatory.size <= 128) { "Verification budget exceeded; no checks omitted." }
        val reports = mutableListOf<VerificationReport>()
        if (declared.isNotEmpty() || mandatory.isEmpty()) reports += TaskVerifier.verify(declared, observation, failures)
        mandatory.chunked(EvidenceSpecParser.MAX_SPECS).forEachIndexed { index, checks ->
            reports += TaskVerifier.verify(checks, observation, if (declared.isEmpty() && index == 0) failures else 0)
        }
        val results = reports.flatMap { it.results }
        return VerificationReport(results.isNotEmpty() && results.all { it.passed }, results, reports.firstOrNull { !it.passed }?.reason)
    }

    /** Workbench/API status and source/probe/discovery exits are never child-process proof. */
    fun processEvidence(evidence: List<ToolExecutionEvidence>, fixed: List<FixedProjectExecution>): List<ToolExecutionEvidence> =
        evidence.filter { item ->
            item.exitCode != null && item.exitCode !in setOf(124, 137) &&
                (item.toolName == "terminal_run" || fixed.any { actual ->
                    actual.infrastructureOk && item.toolName == "project_run_${actual.kind.name.lowercase()}" &&
                        item.objectiveId == actual.objectiveId && item.taskId == actual.taskId &&
                        item.exitCode == actual.exitCode && item.startedAt >= actual.startedAt && item.finishedAt <= actual.finishedAt
                })
        }
}

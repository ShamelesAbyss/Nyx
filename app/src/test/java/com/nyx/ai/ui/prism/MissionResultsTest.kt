package com.nyx.ai.ui.prism

import com.nyx.ai.autonomy.*
import com.nyx.ai.data.*
import com.nyx.ai.workbench.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class MissionResultsTest {
    private fun objective(status: String = "completed") = ObjectiveEntity("mission", "Inspect calculator", "goal", status = status, createdAt = 1000, updatedAt = 4000, completedAt = 4000)
    private fun task(output: String = "A genuine recorded answer", status: String = "completed", evidence: String = """{"results":[{"kind":"artifact_nonempty","passed":true,"actual":"non-empty"}]}""") =
        ObjectiveTaskEntity("task", "mission", 0, "Inspection", "instructions", status = status, attemptCount = 1, outcome = output, verificationStatus = "passed", evidenceJson = evidence)
    private fun event(kind: String, detail: String, taskId: String? = null, output: String? = null) = ObjectiveEventEntity(kind, "mission", taskId, kind, detail, 2000, toolOutput = output)
    private fun config(type: QuickActionType = QuickActionType.INSPECT_PROJECT) = event("execution_config", JSONObject()
        .put("environment", "DEBIAN_PROOT").put("workspace", "/tmp/project").put("configurationSequence", 1)
        .put(QuickAction.KEY, QuickAction("mission", type, true, "/tmp/project").toJson()).toString())
    private fun inspection() = JSONObject().put("path", "/tmp/project").put("projectType", "empty").put("markers", JSONArray()).put("ready", false).put("reason", "Empty workspace; no supported procedure").toString()
    private fun witness() = event("quick_operation_evidence", JSONObject().put("objectiveId", "mission").put("taskId", "task").put("tool", "project_inspect").put("exitCode", 0).toString(), "task", "EMPTY:1\nTOOL:sh=/usr/bin/dash\n")

    @Test fun inspectionEmptyWorkspaceBecomesReadableOwnedEvidenceWithoutRawJsonAsPrimary() {
        val result = MissionResults.present(objective(), listOf(task(inspection())), listOf(config(), witness()))
        assertTrue(result.summary.contains("observed to be empty"))
        assertTrue(result.summary.contains("No project files were created"))
        assertEquals("sh", result.facts.single { it.label == "Available tools observed" }.value)
        assertTrue(result.outputs.isEmpty()); assertTrue(result.quickAction)
        assertEquals("1/1 recorded local checks passed", result.verification)
    }
    @Test fun modelProseAndInventedEmptyMarkersCannotBecomeInspectionEvidence() {
        val result = MissionResults.present(objective(), listOf(task("EMPTY:1 I inspected everything")), emptyList())
        assertFalse(result.summary.contains("empty")); assertTrue(result.facts.none { it.label == "Workspace" })
        assertEquals("EMPTY:1 I inspected everything", result.outputs.single().text)
    }
    @Test fun foreignTaskAndObservationOwnershipIsRejected() {
        val foreign = task(inspection()).copy(objectiveId = "foreign")
        val result = MissionResults.present(objective(), listOf(foreign), listOf(config(), witness()))
        assertTrue(result.outputs.isEmpty()); assertTrue(result.facts.none { it.label == "Project type" })
        val mismatched = witness().copy(detail = witness().detail!!.replace("mission", "foreign"))
        assertTrue(MissionResults.present(objective(), listOf(task(inspection())), listOf(config(), mismatched)).facts.none { it.label == "Project type" })
    }
    @Test fun failedOrMissingChecksCannotEstablishCompletedVerification() {
        for (proof in listOf("{}", "broken", """{"results":[{"passed":"true"}]}""", """{"results":[{"passed":false}]}""")) {
            val result = MissionResults.present(objective(), listOf(task(evidence = proof)), emptyList())
            assertTrue(result.summary.contains("incomplete")); assertFalse(result.outputs.single().verified)
        }
    }
    @Test fun failedInspectionDoesNotClaimAnEmptyWorkspaceOrFileSuccess() {
        val result = MissionResults.present(objective("needs_attention"), listOf(task(inspection(), status = "failed")), listOf(config(), witness()))
        assertFalse(result.summary.contains("observed to be empty")); assertTrue(result.needsReview)
        assertTrue(result.facts.single { it.label == "Recovery" }.value.contains("new Quick Action"))
    }
    @Test fun testResultsShowActualExitAndPlainOutputInsteadOfJson() {
        val run = JSONObject().put("objectiveId", "mission").put("taskId", "task").put("workspace", "/tmp/project").put("kind", "TEST").put("resultKind", "PROCESS_EXIT").put("exitCode", 7).put("command", "/usr/bin/sh ./test.sh")
        val output = JSONObject().put("stdout", "real test failed").put("stderr", "assertion failed").put("exitCode", 7).toString()
        val result = MissionResults.present(objective("needs_attention"), listOf(task(output, "failed", """{"results":[{"passed":false}]}""")), listOf(config(QuickActionType.RUN_TESTS), event("quick_project_execution", run.toString(), "task")))
        assertTrue(result.facts.any { it.label == "Test outcome" && it.value.contains("exit 7") })
        assertEquals("real test failed\nassertion failed", result.outputs.single().text)
        assertFalse(result.outputs.single().verified)
    }
    @Test fun foreignCommandLedgerCannotProveTestsRan() {
        val ledger = event("quick_project_execution", """{"objectiveId":"other","taskId":"task","workspace":"/tmp/project","kind":"TEST","exitCode":0}""", "task")
        val result = MissionResults.present(objective(), listOf(task()), listOf(config(QuickActionType.RUN_TESTS), ledger))
        assertTrue(result.facts.none { it.label == "Test outcome" })
    }
    @Test fun historicalResultsReopenDeterministicallyWithoutModelOrToolDependencies() {
        val record = task("Full retained answer\nwith details")
        val first = MissionResults.present(objective(), listOf(record), emptyList())
        assertEquals(first, MissionResults.present(objective().copy(), listOf(record.copy()), emptyList()))
        assertEquals(record.outcome, first.outputs.single().text)
        assertTrue(first.facts.any { it.label == "Recorded elapsed time" && it.value == "3s" })
    }
    @Test fun pausedWithoutAttemptsSaysNoTaskExecutionAndRequiresReview() {
        val result = MissionResults.present(objective("paused"), listOf(task().copy(status = "pending", attemptCount = 0)), emptyList())
        assertTrue(result.needsReview); assertTrue(result.facts.any { it.label == "Execution" && it.value.startsWith("No task attempt") })
    }
    @Test fun retainedFilesAreNeverInventedFromModelProseOrForeignManifest() {
        val record = ArtifactRecord("artifact", "result.md", ArtifactFormat.MARKDOWN, 5, "a".repeat(64), 1000, relativePath = "artifacts/result.md")
        val result = MissionResults.present(objective(), listOf(task("Created result.md")), emptyList(), manifest = WorkbenchManifest("foreign", 0, 0, artifacts = listOf(record)))
        assertTrue(result.facts.none { it.label == "Registered Workbench files" })
    }
    @Test fun sourceLinksComeOnlyFromTheSelectedRetainedManifest() {
        val source = ResearchSource("https://example.com/observed", status = "FETCHED", bytes = 12, sha256 = "a".repeat(64))
        val result = MissionResults.present(objective(), listOf(task("A response")), emptyList(), manifest = WorkbenchManifest("mission", 0, 0, sources = listOf(source)))
        assertEquals(source.url, result.facts.single { it.label == "Retained source record" }.value)
        assertFalse(result.copyText.contains("factual accuracy verified"))
    }
    @Test fun changedFilesNeedDevelopmentExecutionRecordsAndAreNotClaimedCurrent() {
        val result = MissionResults.present(objective(), listOf(task("Changed files: Main.kt").copy(workerKind = "development")), emptyList())
        assertTrue(result.facts.single { it.label == "Recorded edited paths" }.value.contains("not rechecked"))
        assertTrue(MissionResults.present(objective(), listOf(task("Changed files: fake.txt")), emptyList()).facts.none { it.label == "Recorded edited paths" })
    }
    @Test fun oldReportsKeepOwnershipAndDoNotTurnIntoDiskArtifacts() {
        val report = ObjectiveArtifactEntity("report", "foreign", "task", "forged", content = "foreign", createdAt = 0)
        val result = MissionResults.present(objective(), listOf(task().copy(outcome = null)), emptyList(), reports = listOf(report))
        assertTrue(result.outputs.isEmpty())
    }
    @Test fun malformedDeepAndOversizedDataFailsClosedWithoutHidingTheRecordedResponse() {
        for (raw in listOf("{".repeat(1000), "x".repeat(65_000), "[]", "bad JSON")) {
            val result = MissionResults.present(objective(), listOf(task(raw)), listOf(config(), witness()))
            assertTrue(result.facts.none { it.label == "Project type" }); assertEquals(raw, result.outputs.single().text)
        }
    }
    @Test fun credentialsAndTerminalEscapesAreRedactedFromTheProjection() {
        val result = MissionResults.present(objective(), listOf(task("Authorization: Bearer secretcredential123456\u001B[31mRED\u001B[0m")), emptyList())
        assertFalse(result.copyText.contains("secretcredential")); assertFalse(result.copyText.contains('\u001B'))
    }
    @Test fun negativeClockIntervalsAreUnavailableRatherThanInventedDurations() {
        val result = MissionResults.present(objective().copy(completedAt = 0), listOf(task()), emptyList())
        assertTrue(result.facts.none { it.label == "Recorded elapsed time" })
    }
    @Test fun malformedCheckArrayCannotPartiallyPass() {
        val result = MissionResults.present(objective(), listOf(task(evidence = """{"results":[{"passed":true},false]}""")), emptyList())
        assertFalse(result.outputs.single().verified)
        assertTrue(result.summary.contains("incomplete"))
    }
    @Test fun fractionalExitIsNotSuccessfulInspectionEvidence() {
        val bad = witness().copy(detail = witness().detail!!.replace("\"exitCode\":0", "\"exitCode\":0.5"))
        val result = MissionResults.present(objective(), listOf(task(inspection())), listOf(config(), bad))
        assertFalse(result.summary.contains("observed to be empty"))
    }

}

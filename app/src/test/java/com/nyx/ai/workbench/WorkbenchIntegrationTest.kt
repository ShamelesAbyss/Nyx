package com.nyx.ai.workbench

import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.autonomy.CompactPlanningPolicy
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.TaskCapabilityScope
import com.nyx.ai.network.ToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkbenchIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun approval() = WorkbenchAuthorization(enabled = true, requiredFiles = mapOf("report.md" to ArtifactFormat.MARKDOWN)).validate()
    private fun context(task: String = "task-one", objective: String = "objective-one") = AgentToolTaskContext(objective, task, ExecutionTimeoutPolicy.forProfile())
    private fun call(name: String, vararg values: Pair<String, Any>) = ToolCall("call-one", name, JSONObject(mapOf(*values)))
    private fun tools(store: WorkbenchStore) = WorkbenchAgentTools(store, "objective-one", approval()).forTask(context())

    @Test fun writeDiskVerifyReadSaveBytesAndReopenLifecycle() = runBlocking {
        val root = temporary.newFolder()
        val store = WorkbenchStore(root, "objective-one")
        val tools = tools(store)
        val output = tools.execute(call(WorkbenchAgentTools.WRITE, "name" to "report.md", "format" to "MARKDOWN", "content" to "# Report\nVerified file output.\n"))
        val result = JSONObject(output)
        val record = store.listArtifacts().single()
        assertEquals(record.sha256, result.getString("sha256"))
        val witness = tools.drainEvidence().single()
        assertEquals(0, witness.exitCode)
        assertEquals("objective-one", witness.objectiveId)
        assertEquals("task-one", witness.taskId)
        assertEquals(listOf("workbench/report.md"), witness.files)
        assertTrue(witness.mutating)
        val reopened = WorkbenchStore(root, "objective-one")
        val probe = WorkbenchArtifactProbe(reopened)
        assertTrue(probe.exists("workbench/report.md"))
        assertEquals(record.sha256, probe.sha256("workbench/report.md"))
        assertEquals("# Report\nVerified file output.\n", AndroidArtifactHandoff.checkedBytes(reopened, record).toString(Charsets.UTF_8))
        val witnesses = listOf(WorkbenchCompletionPolicy.WriteWitness("task-one", record.fileName, record.sha256))
        assertTrue(WorkbenchCompletionPolicy.missing(approval(), reopened, setOf("task-one"), witnesses).isEmpty())
        assertTrue(tools.execute(call(WorkbenchAgentTools.READ, "kind" to "artifact", "id" to record.id)).contains("UNTRUSTED"))
    }

    @Test fun modelAssertionsAndForeignOrStaleWitnessesNeverCompleteFiles() = runBlocking {
        val store = WorkbenchStore(temporary.newFolder(), "objective-one")
        assertEquals(listOf("report.md"), WorkbenchCompletionPolicy.missing(approval(), store, setOf("task-one"), emptyList()))
        val record = store.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "actual bytes".toByteArray())
        assertEquals(listOf("report.md"), WorkbenchCompletionPolicy.missing(approval(), store, setOf("task-one"), emptyList()))
        assertEquals(listOf("report.md"), WorkbenchCompletionPolicy.missing(approval(), store, setOf("task-one"), listOf(WorkbenchCompletionPolicy.WriteWitness("other-task", "report.md", record.sha256))))
        assertEquals(listOf("report.md"), WorkbenchCompletionPolicy.missing(approval(), store, setOf("task-one"), listOf(WorkbenchCompletionPolicy.WriteWitness("task-one", "report.md", "0".repeat(64)))))
        assertFalse(WorkbenchArtifactProbe(store).exists("workbench/../imports/private.md"))
        assertFalse(WorkbenchArtifactProbe(store).exists("/data/user/0/com.nyx.ai/files/secrets"))
    }

    @Test fun invalidFormatsConflictingWritesAndUntaskedCallsFailWithEvidence() = runBlocking {
        val store = WorkbenchStore(temporary.newFolder(), "objective-one")
        val unbound = WorkbenchAgentTools(store, "objective-one", approval())
        assertTrue(unbound.execute(call(WorkbenchAgentTools.WRITE, "name" to "report.md", "format" to "MARKDOWN", "content" to "text")).startsWith("Tool error:"))
        assertEquals(1, unbound.drainEvidence().single().exitCode)
        assertTrue(store.listArtifacts().isEmpty())
        val tools = tools(store)
        assertTrue(tools.execute(call(WorkbenchAgentTools.WRITE, "name" to "report.json", "format" to "JSON", "content" to "{bad")).startsWith("Tool error:"))
        assertTrue(store.listArtifacts().isEmpty())
        store.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "first".toByteArray())
        assertTrue(tools.execute(call(WorkbenchAgentTools.WRITE, "name" to "report.md", "format" to "MARKDOWN", "content" to "second")).startsWith("Tool error:"))
        assertEquals("first", store.readArtifact(store.listArtifacts().single()).toString(Charsets.UTF_8))
        assertTrue(tools.drainEvidence().all { it.exitCode == 1 })
    }

    @Test fun importsAndSourcesCannotBecomeExportsOrBundlePayloads() = runBlocking {
        val store = WorkbenchStore(temporary.newFolder(), "objective-one")
        val imported = store.importFile("selected.txt", "private imported text".toByteArray(), "text/plain")
        val tools = tools(store)
        assertTrue(tools.execute(call(WorkbenchAgentTools.READ, "id" to imported.id, "kind" to "import")).contains("UNTRUSTED"))
        val result = tools.execute(call(WorkbenchAgentTools.BUNDLE, "name" to "output.zip", "ids" to JSONArray().put(imported.id)))
        assertTrue(result.startsWith("Tool error:"))
        assertTrue(store.listArtifacts().isEmpty())
        val record = store.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "report".toByteArray())
        val forged = record.copy(relativePath = imported.relativePath)
        assertTrue(runCatching { AndroidArtifactHandoff.checkedBytes(store, forged) }.isFailure)
    }

    @Test fun validBundleContainsArtifactBytesButNotSourceImportFiles() = runBlocking {
        val store = WorkbenchStore(temporary.newFolder(), "objective-one")
        val record = store.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "# Report".toByteArray())
        store.importFile("private.txt", "private".toByteArray(), "text/plain")
        val output = tools(store).execute(call(WorkbenchAgentTools.BUNDLE, "name" to "report.zip", "ids" to JSONArray().put(record.id)))
        assertEquals("ZIP", JSONObject(output).getString("format"))
        val archive = store.listArtifacts().single { it.format == ArtifactFormat.ZIP }
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(store.readArtifact(archive).inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names += entry.name; zip.closeEntry() }
        }
        assertEquals(setOf("artifacts/report.md", "artifact-manifest.json", "README.txt"), names.toSet())
        assertFalse(names.any { it.contains("source") || it.contains("import") || it.contains("private") })
    }

    @Test fun taskKindsAndPlannerSupportWorkbenchWithoutTermux() {
        val names = setOf(WorkbenchAgentTools.WRITE, WorkbenchAgentTools.BUNDLE, WorkbenchAgentTools.READ, WorkbenchAgentTools.LIST)
        val inference = TaskCapabilityScope.forTask("inference", names, names, terminalAvailable = false)
        assertTrue(inference.readOnly)
        assertFalse(WorkbenchAgentTools.WRITE in inference.allowed)
        assertFalse(WorkbenchAgentTools.BUNDLE in inference.allowed)
        val files = TaskCapabilityScope.forTask("filesystem", names, names, terminalAvailable = false)
        assertFalse(files.readOnly)
        assertTrue(WorkbenchAgentTools.WRITE in files.allowed)
        val verify = TaskCapabilityScope.forTask("verification", names, names, terminalAvailable = false)
        assertFalse(WorkbenchAgentTools.WRITE in verify.allowed)
        assertTrue(WorkbenchAgentTools.READ in verify.allowed)
        assertTrue(CompactPlanningPolicy.availableWorkers(false, names).containsAll(setOf("inference", "filesystem", "verification")))
        assertFalse("terminal" in CompactPlanningPolicy.availableWorkers(false, names))
    }

    @Test fun approvalsPersistAndDoNotEnableResearchOrForeignTasks() {
        val approval = approval()
        val snapshot = JSONObject().put(WorkbenchAuthorization.SNAPSHOT_KEY, approval.toJson())
        assertEquals(approval, WorkbenchAuthorization.fromSnapshot(snapshot))
        val store = WorkbenchStore(temporary.newFolder(), "objective-one")
        val tools = WorkbenchAgentTools(store, "objective-one", approval)
        assertFalse(WorkbenchAgentTools.RESEARCH in tools.names)
        assertTrue(runCatching { tools.forTask(context(objective = "other-objective")) }.isFailure)
        assertTrue(runCatching { approval.copy(researchEnabled = true, authorizedUrls = listOf("https://user:secret@example.com/")).validate() }.isFailure)
        assertTrue(runCatching { approval.copy(requiredFiles = mapOf("../report.md" to ArtifactFormat.MARKDOWN)).validate() }.isFailure)
        assertTrue(runCatching { approval.copy(requiredFiles = mapOf("report.pdf" to ArtifactFormat.PDF)).validate() }.isFailure)
    }

    @Test fun researchToolNeverExtendsTrustedUrlAllowlist() = runBlocking {
        val approval = approval().copy(researchEnabled = true, authorizedUrls = listOf("https://example.com/selected")).validate()
        val store = WorkbenchStore(temporary.newFolder(), "objective-one")
        val visited = mutableListOf<String>()
        val tools = WorkbenchAgentTools(store, "objective-one", approval, research = { url -> visited += url; WorkbenchResearchObservation(true, "UNTRUSTED evidence") }).forTask(context())
        assertTrue(tools.execute(call(WorkbenchAgentTools.RESEARCH, "url" to "https://example.com/not-selected")).startsWith("Tool error:"))
        assertTrue(visited.isEmpty())
        assertEquals("UNTRUSTED evidence", tools.execute(call(WorkbenchAgentTools.RESEARCH, "url" to "https://example.com/selected")))
        assertEquals(listOf("https://example.com/selected"), visited)
        assertEquals(listOf(1, 0), tools.drainEvidence().map { it.exitCode })
    }
}

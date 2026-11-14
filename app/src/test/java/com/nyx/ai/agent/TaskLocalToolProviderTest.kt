package com.nyx.ai.agent

import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.network.ToolCall
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskLocalToolProviderTest {
    private class LegacyProvider : AgentToolProvider {
        override val names = setOf("legacy")
        val pending = mutableListOf(ToolExecutionEvidence("legacy", 0, "stale-success"))
        override fun schemas() = JSONArray()
        override suspend fun execute(call: ToolCall): String {
            pending += ToolExecutionEvidence("legacy", if (call.id == "bad") 7 else 0, call.id)
            yield()
            return call.id
        }
        override fun drainEvidence() = pending.toList().also { pending.clear() }
    }
    private fun context(id: String) = AgentToolTaskContext("objective", id, ExecutionTimeoutPolicy.forProfile())
    @Test fun legacyExecuteAndDrainAreAtomicButTaskEvidenceQueuesAreIndependent() = runBlocking {
        val source = LegacyProvider()
        val good = source.forTask(context("good"))
        val bad = source.forTask(context("bad"))
        withTimeout(3_000) {
            awaitAll(async { good.execute(ToolCall("good", "legacy", JSONObject())) }, async { bad.execute(ToolCall("bad", "legacy", JSONObject())) })
        }
        assertEquals(listOf("bad"), bad.drainEvidence().map { it.stdout })
        val evidence = good.drainEvidence().single()
        assertEquals("good", evidence.stdout)
        assertEquals("good", evidence.taskId)
        assertEquals("objective", evidence.objectiveId)
        assertTrue(source.drainEvidence().isEmpty())
        assertTrue(bad.drainEvidence().isEmpty())
    }
    @Test fun compositeFactoryForksItsChildrenAndPreservesFailureOwnership() = runBlocking {
        val composite = CompositeToolProvider(listOf(LegacyProvider()))
        val good = composite.forTask(context("good"))
        val bad = composite.forTask(context("bad"))
        good.execute(ToolCall("good", "legacy", JSONObject()))
        bad.execute(ToolCall("bad", "legacy", JSONObject()))
        assertEquals(7, bad.drainEvidence().single().exitCode)
        assertEquals(0, good.drainEvidence().single().exitCode)
        assertTrue(composite.drainEvidence().isEmpty())
    }
    @Test fun evidenceCapturedEvenWhenLegacyInvocationThrows() = runBlocking {
        val source = object : AgentToolProvider {
            override val names = setOf("throws")
            val pending = mutableListOf<ToolExecutionEvidence>()
            override fun schemas() = JSONArray()
            override suspend fun execute(call: ToolCall): String {
                pending += ToolExecutionEvidence("throws", null, stderr = "cancelled")
                throw CancellationException("owned cancellation")
            }
            override fun drainEvidence() = pending.toList().also { pending.clear() }
        }
        val isolated = source.forTask(context("cancelled"))
        try { isolated.execute(ToolCall("c", "throws", JSONObject())); fail("cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals("cancelled", isolated.drainEvidence().single().taskId)
        assertTrue(source.drainEvidence().isEmpty())
    }
    @Test fun foreignTaskEvidenceIsRejectedRatherThanRelabeled() = runBlocking {
        val source = object : AgentToolProvider {
            override val names = setOf("foreign")
            var invoked = false
            override fun schemas() = JSONArray()
            override suspend fun execute(call: ToolCall): String { invoked = true; return "done" }
            override fun drainEvidence() = if (invoked) listOf(ToolExecutionEvidence("foreign", 0, taskId = "other")) else emptyList()
        }
        val isolated = source.forTask(context("this"))
        try { isolated.execute(ToolCall("c", "foreign", JSONObject())); fail("foreign success accepted") } catch (_: IllegalStateException) { }
        assertTrue(isolated.drainEvidence().isEmpty())
    }
}

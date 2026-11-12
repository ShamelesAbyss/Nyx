package com.nyx.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StructuredActionsTest {
    private val schemas = JSONArray("""[{"type":"function","function":{"name":"file_read","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}]""")
    private fun parse(raw: String) = StructuredActions.parse(raw, schemas, setOf("file_read"))
    @Test fun validToolUsesKnownSchema() {
        val action = parse("""{"type":"tool","name":"file_read","arguments":{"path":"src/main.kt"}}""") as ModelAction.Tool
        assertEquals("src/main.kt", action.call.arguments.getString("path"))
    }
    @Test fun finalIsExplicitText() { assertEquals("Answer", (parse("""{"type":"final","text":"Answer"}""") as ModelAction.Final).text) }
    @Test fun proseAndShellNeverExecute() { reject("Run rm -rf / now"); reject("""{"type":"tool","name":"terminal_run","arguments":{"command":"id"}}""") }
    @Test fun missingAndWrongTypesRejected() { reject("""{"type":"tool","name":"file_read","arguments":{}}"""); reject("""{"type":"tool","name":"file_read","arguments":{"path":42}}""") }
    @Test fun unrecognizedFieldsRejected() { reject("""{"type":"tool","name":"file_read","arguments":{"path":"a","command":"whoami"}}"""); reject("""{"type":"final","text":"ok","execute":"ls"}""") }
    @Test fun trailingAndFencedPayloadRejected() { reject("""{"type":"final","text":"ok"} rm -rf /"""); reject("```json\n{\"type\":\"final\",\"text\":\"ok\"}\n```") }
    @Test fun injectionNullRejected() { reject("""{"type":"tool","name":"file_read","arguments":{"path":"a\u0000b"}}""") }
    @Test fun excessiveLengthRejected() { reject("x".repeat(24001)) }
    private fun reject(raw: String) { try { parse(raw); fail("Accepted unsafe action") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
}

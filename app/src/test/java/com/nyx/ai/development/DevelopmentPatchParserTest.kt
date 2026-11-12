package com.nyx.ai.development

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DevelopmentPatchParserTest {
    private fun json(path: String = "src/answer.kt", content: String = "fixed", hash: String = "a".repeat(64)): String = JSONObject()
        .put("files", JSONArray().put(JSONObject().put("path", path).put("expectedSha256", hash).put("content", content))).toString()
    private fun denied(text: String, budget: DevelopmentBudget = DevelopmentBudget(), files: Int = 12, bytes: Int = 256_000) {
        try { DevelopmentPatchParser.parse(text, budget, files, bytes); fail("Expected rejection: $text") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
    @Test fun acceptsExactTypedMultiFilePatch() {
        val first = JSONObject(json()).getJSONArray("files").getJSONObject(0)
        val second = JSONObject().put("path", "test/answer.kt").put("expectedSha256", "MISSING").put("content", "assert")
        val patch = DevelopmentPatchParser.parse(JSONObject().put("files", JSONArray().put(first).put(second)).toString(), DevelopmentBudget(), 2, 100)
        assertEquals(2, patch.files.size)
        assertEquals("MISSING", patch.files[1].expectedSha256)
    }
    @Test fun rejectsTraversalAbsoluteAndProtectedFiles() {
        for (path in listOf("../x", "src/../x", "/tmp/x", "~/x", "src//x", ".git/config", "a/.env", "a/.env.production", "local.properties", "credentials.json", ".nyx-checkpoints/x", "id_rsa", "build/x", "node_modules/x", "a/key.pem", "a\\b")) denied(json(path))
    }
    @Test fun rejectsLenientJsonDuplicateKeysTrailingAndUnknownActions() {
        denied("{files:[]}"); denied("{\"files\":[],\"files\":[]}"); denied(json() + " trailing")
        denied("```json\n${json()}\n```"); denied("{\"files\":[],\"command\":\"rm -rf /\"}")
        denied("{\"files\":[{\"path\":\"a\",\"path\":\"b\",\"expectedSha256\":\"MISSING\",\"content\":\"x\"}]}")
        denied("{\"files\":[true]}"); denied("{\"files\":[{\"path\":\"a\",\"expectedSha256\":\"MISSING\",\"content\":\"\\uD800\"}]}")
    }
    @Test fun rejectsDuplicatePathsInvalidHashAndNul() {
        val file = JSONObject(json()).getJSONArray("files").getJSONObject(0)
        denied(JSONObject().put("files", JSONArray().put(file).put(file)).toString())
        denied(json(hash = "abc")); denied(json(content = "a\u0000b"))
    }
    @Test fun enforcesUtf8FileAndTotalBudgetsBeforeApplying() {
        denied(json(content = "éé"), DevelopmentBudget(maxFileBytes = 3))
        denied(json(), files = 0); denied(json(), bytes = 2)
        denied(json(), DevelopmentBudget(maxResponseChars = 10))
    }
    @Test fun workspaceMustBeAbsoluteNormalizedAndNotRoot() {
        for (root in listOf("~/project", "/", "/tmp/../x", "/tmp/x/", "tmp/x", "/tmp//x", "/tmp/./x")) assertFalse(DevelopmentPaths.isAbsoluteNormalizedWorkspace(root))
        assertTrue(DevelopmentPaths.isAbsoluteNormalizedWorkspace("/tmp/approved-workspace"))
    }
}

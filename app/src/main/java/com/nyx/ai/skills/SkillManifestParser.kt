package com.nyx.ai.skills

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Parsing produces untrusted data only; callers must use [SkillValidator.validateUntrusted]. */
object SkillManifestParser {
    const val MAX_MANIFEST_CHARS = 32_000

    fun parse(text: String): SkillParseResult {
        if (text.length > MAX_MANIFEST_CHARS) return failure(SkillIssueCode.INVALID_FORMAT, "Manifest exceeds 32000 characters")
        return try {
            val tokener = JSONTokener(text)
            val json = tokener.nextValue() as? JSONObject ?: throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "Expected a manifest object")
            if (tokener.nextClean() != '\u0000') throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "Unexpected trailing input")
            fields(json, setOf("id", "version", "description", "tools", "requiredPermissions", "preconditions", "constraints", "failureHandling", "compatibility", "instructions"))
            val constraints = objectValue(json, "constraints")
            fields(constraints, setOf("workspaceOnly", "allowNetwork", "allowDestructive", "allowAutoCommit", "requireWriteApproval", "maxSteps", "maxOutputChars"))
            val failure = objectValue(json, "failureHandling")
            fields(failure, setOf("mode", "maxRetries", "preserveEvidence"))
            val compatibility = objectValue(json, "compatibility")
            fields(compatibility, setOf("manifestVersion", "minHostVersion", "maxHostMajor"))
            val manifest = SkillManifest(
                string(json, "id"), string(json, "version"), string(json, "description"),
                strings(json, "tools").toSet(),
                strings(json, "requiredPermissions").map { SkillPermission.valueOf(it) }.toSet(),
                strings(json, "preconditions").map { SkillPrecondition.valueOf(it) }.toSet(),
                SkillConstraints(boolean(constraints, "workspaceOnly"), boolean(constraints, "allowNetwork"),
                    boolean(constraints, "allowDestructive"), boolean(constraints, "allowAutoCommit"),
                    boolean(constraints, "requireWriteApproval"), integer(constraints, "maxSteps"), integer(constraints, "maxOutputChars")),
                SkillFailureHandling(SkillFailureMode.valueOf(string(failure, "mode")), integer(failure, "maxRetries"), boolean(failure, "preserveEvidence")),
                SkillCompatibility(integer(compatibility, "manifestVersion"), string(compatibility, "minHostVersion"), integer(compatibility, "maxHostMajor")),
                if (json.has("instructions")) strings(json, "instructions") else emptyList(),
            )
            SkillParseResult(manifest, emptyList())
        } catch (error: ParseFailure) {
            failure(error.code, error.message ?: "Invalid manifest")
        } catch (_: Exception) {
            failure(SkillIssueCode.INVALID_FORMAT, "Malformed manifest, unknown enum, missing field, or incorrect field type")
        }
    }

    private fun fields(json: JSONObject, allowed: Set<String>) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowed) throw ParseFailure(SkillIssueCode.UNKNOWN_FIELD, "Unsupported manifest field: $key")
        }
    }

    private fun objectValue(json: JSONObject, key: String): JSONObject = json.get(key) as? JSONObject
        ?: throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "$key must be an object")

    private fun string(json: JSONObject, key: String): String = json.get(key) as? String
        ?: throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "$key must be a string")

    private fun boolean(json: JSONObject, key: String): Boolean = json.get(key) as? Boolean
        ?: throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "$key must be a boolean")

    private fun integer(json: JSONObject, key: String): Int {
        val value = json.get(key)
        if (value !is Int && value !is Long) throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "$key must be an integer")
        val number = (value as Number).toLong()
        if (number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "$key is outside integer range")
        return number.toInt()
    }

    private fun strings(json: JSONObject, key: String): List<String> {
        val values = json.get(key) as? JSONArray ?: throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "$key must be an array")
        if (values.length() > 32) throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "$key has too many entries")
        val result = (0 until values.length()).map { index -> values.get(index) as? String
            ?: throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "$key must contain strings") }
        if (result.distinct().size != result.size) throw ParseFailure(SkillIssueCode.INVALID_FORMAT, "$key contains duplicate entries")
        return result
    }

    private fun failure(code: SkillIssueCode, message: String): SkillParseResult =
        SkillParseResult(null, listOf(SkillValidationIssue(code, message)))

    private class ParseFailure(val code: SkillIssueCode, message: String) : IllegalArgumentException(message)
}

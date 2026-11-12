package com.nyx.ai.autonomy.policy

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Persists the autonomy policy and its audit journal.
 *
 * Policy state lives in SharedPreferences so it survives process death; the
 * journal is a capped JSON Lines file. Every read is defensive: corrupt payloads
 * fall back to [AutonomyMode.MANUAL] with no grants and no stop flag rather than
 * crashing the app. No credentials, tokens, or user content are stored here.
 */
class AutonomyPolicyStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val journalFile = File(appContext.filesDir, JOURNAL_FILE_NAME)

    /** Loads the persisted snapshot, falling back to safe defaults. */
    fun load(): PolicySnapshot {
        val mode = AutonomyPolicyCodec.decodeMode(readString(KEY_MODE))
        val stopped = runCatching { prefs.getBoolean(KEY_STOPPED, false) }.getOrDefault(false)
        val grants = AutonomyPolicyCodec.decodeGrants(readString(KEY_GRANTS))
        return PolicySnapshot(mode = mode, stopped = stopped, grants = grants)
    }

    /** Persists [snapshot], replacing any previous policy state. */
    fun save(snapshot: PolicySnapshot) {
        prefs.edit()
            .putString(KEY_MODE, snapshot.mode.name)
            .putBoolean(KEY_STOPPED, snapshot.stopped)
            .putString(KEY_GRANTS, AutonomyPolicyCodec.encodeGrants(snapshot.grants))
            .apply()
    }

    /** Reads the journal, skipping unreadable records; oldest entry first. */
    fun journal(): List<AuthorizationEntry> = readJournalLines().mapNotNull { line ->
        AutonomyPolicyCodec.decodeEntry(line)
    }

    /** Appends [entry], keeping only the most recent [MAX_JOURNAL_LINES] records. */
    fun appendJournal(entry: AuthorizationEntry) {
        val lines = readJournalLines().takeLast(MAX_JOURNAL_LINES - 1) +
            AutonomyPolicyCodec.encodeEntry(entry)
        writeJournalLines(lines)
    }

    /** Removes every journal record. */
    fun clearJournal() {
        runCatching { journalFile.delete() }
            .onFailure { Log.w(TAG, "Unable to clear authorization journal", it) }
    }

    private fun readString(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    private fun readJournalLines(): List<String> {
        if (!journalFile.isFile) return emptyList()
        return try {
            journalFile.readLines().filter { it.isNotBlank() }
        } catch (error: IOException) {
            Log.w(TAG, "Unable to read authorization journal", error)
            emptyList()
        }
    }

    private fun writeJournalLines(lines: List<String>) {
        try {
            val temporary = File(journalFile.parentFile, "$JOURNAL_FILE_NAME.tmp")
            temporary.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
            if (!temporary.renameTo(journalFile)) {
                journalFile.writeText(temporary.readText())
                temporary.delete()
            }
        } catch (error: IOException) {
            Log.w(TAG, "Unable to append authorization journal", error)
        }
    }

    private companion object {
        const val PREFS_NAME = "nyx_autonomy_policy"
        const val KEY_MODE = "mode"
        const val KEY_STOPPED = "stopped"
        const val KEY_GRANTS = "grants_json"
        const val JOURNAL_FILE_NAME = "authorization_journal.jsonl"
        const val MAX_JOURNAL_LINES = 200
        const val TAG = "AutonomyPolicyStore"
    }
}

/**
 * JSON encoding for policy state and journal records.
 *
 * Kept separate from [AutonomyPolicyStore] so parsing can be verified on the JVM
 * without an Android context.
 */
internal object AutonomyPolicyCodec {
    private const val KEY_ID = "id"
    private const val KEY_CAPABILITY = "capability"
    private const val KEY_ENVIRONMENT = "environment_id"
    private const val KEY_SCOPE = "scope"
    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_REVOKED = "revoked"
    private const val KEY_AT = "at"
    private const val KEY_MODE = "mode"
    private const val KEY_KIND = "kind"
    private const val KEY_TARGET = "target"
    private const val KEY_RISK = "risk"
    private const val KEY_DECISION = "decision"
    private const val KEY_REASON = "reason"

    /** Serializes [grants] as a JSON array. */
    fun encodeGrants(grants: List<CapabilityGrant>): String {
        val array = JSONArray()
        grants.forEach { grant ->
            array.put(
                JSONObject()
                    .put(KEY_ID, grant.id)
                    .put(KEY_CAPABILITY, grant.capability)
                    .put(KEY_ENVIRONMENT, grant.environmentId)
                    .put(KEY_SCOPE, grant.scope)
                    .put(KEY_CREATED_AT, grant.createdAt)
                    .put(KEY_EXPIRES_AT, grant.expiresAt ?: JSONObject.NULL)
                    .put(KEY_REVOKED, grant.revoked),
            )
        }
        return array.toString()
    }

    /** Parses a grant array, skipping malformed entries; blank input yields no grants. */
    fun decodeGrants(raw: String?): List<CapabilityGrant> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            return emptyList()
        }
        val grants = ArrayList<CapabilityGrant>(array.length())
        for (index in 0 until array.length()) {
            val grant = array.optJSONObject(index)?.let(::decodeGrant)
            if (grant != null) grants += grant
        }
        return grants
    }

    /** Parses one grant, or null when required fields are missing or blank. */
    fun decodeGrant(json: JSONObject): CapabilityGrant? {
        val id = json.optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
        val capability = json.optString(KEY_CAPABILITY).takeIf { it.isNotBlank() } ?: return null
        val environmentId = json.optString(KEY_ENVIRONMENT).takeIf { it.isNotBlank() } ?: return null
        val scope = json.optString(KEY_SCOPE).takeIf { it.isNotBlank() } ?: return null
        return CapabilityGrant(
            id = id,
            capability = capability,
            environmentId = environmentId,
            scope = scope,
            createdAt = json.optLong(KEY_CREATED_AT, 0L),
            expiresAt = if (json.has(KEY_EXPIRES_AT) && !json.isNull(KEY_EXPIRES_AT)) {
                json.optLong(KEY_EXPIRES_AT, 0L)
            } else {
                null
            },
            revoked = json.optBoolean(KEY_REVOKED, false),
        )
    }

    /** Serializes [entry] as a single JSON line. */
    fun encodeEntry(entry: AuthorizationEntry): String = JSONObject()
        .put(KEY_AT, entry.at)
        .put(KEY_MODE, entry.mode.name)
        .put(KEY_KIND, entry.kind.name)
        .put(KEY_TARGET, entry.target)
        .put(KEY_RISK, entry.risk.name)
        .put(KEY_DECISION, entry.decision)
        .put(KEY_REASON, entry.reason)
        .toString()

    /** Parses one journal line, or null when it is corrupt. */
    fun decodeEntry(raw: String): AuthorizationEntry? {
        val json = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return null
        }
        return AuthorizationEntry(
            at = json.optLong(KEY_AT, 0L),
            mode = decodeMode(json.optString(KEY_MODE)),
            kind = decodeEnum<ActionKind>(json.optString(KEY_KIND)) ?: return null,
            target = json.optString(KEY_TARGET),
            risk = decodeEnum<RiskLevel>(json.optString(KEY_RISK)) ?: return null,
            decision = json.optString(KEY_DECISION).takeIf { it.isNotBlank() } ?: return null,
            reason = json.optString(KEY_REASON),
        )
    }

    /** Parses a persisted mode name, defaulting to [AutonomyMode.MANUAL]. */
    fun decodeMode(raw: String?): AutonomyMode =
        decodeEnum<AutonomyMode>(raw) ?: AutonomyMode.MANUAL

    private inline fun <reified T : Enum<T>> decodeEnum(value: String?): T? {
        if (value.isNullOrBlank()) return null
        return try {
            enumValueOf<T>(value.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

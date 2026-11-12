package com.nyx.ai.terminal

import android.content.Context
import com.nyx.ai.autonomy.policy.CapabilityGrant
import com.nyx.ai.autonomy.policy.scopeCovers
import org.json.JSONArray
import org.json.JSONObject

/** A verified workspace NYX created or adopted under a managed parent. */
data class WorkspaceRecord(
    val environment: TerminalEnvironment,
    val guestAlias: String,
    val base: String,
    val path: String,
    val verifiedAt: Long,
)

/** Result of a guarded workspace deletion. */
sealed interface WorkspaceDeleteResult {
    data class Deleted(val path: String) : WorkspaceDeleteResult
    data class Refused(val code: String, val message: String) : WorkspaceDeleteResult
}

sealed interface WorkspaceStartupDecision {
    data object None : WorkspaceStartupDecision
    data class Reuse(val path: String) : WorkspaceStartupDecision
    data object Unavailable : WorkspaceStartupDecision
}

/**
 * Persists only NYX-managed workspace records. The registry is not authority by itself: every
 * selection is re-probed and every deletion is re-validated on the device before removal.
 */
class WorkspaceRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nyx_workspace_registry", 0)

    fun list(): List<WorkspaceRecord> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val environment = runCatching {
                    TerminalEnvironment.valueOf(item.optString("environment"))
                }.getOrNull() ?: return@mapNotNull null
                val path = item.optString("path")
                val base = item.optString("base")
                if (!WorkspaceLifecycle.isManaged(base, path)) return@mapNotNull null
                WorkspaceRecord(
                    environment = environment,
                    guestAlias = item.optString("guestAlias", "debian"),
                    base = base,
                    path = path,
                    verifiedAt = item.optLong("verifiedAt"),
                )
            }.distinctBy { it.path }.sortedByDescending { it.verifiedAt }.take(MAX_RECORDS)
        }.getOrDefault(emptyList())
    }

    fun upsert(record: WorkspaceRecord) {
        if (!WorkspaceLifecycle.isManaged(record.base, record.path)) return
        val next = (list().filterNot { it.path == record.path } + record)
            .sortedByDescending { it.verifiedAt }
            .take(MAX_RECORDS)
        save(next)
    }

    fun remove(path: String) {
        save(list().filterNot { it.path == path })
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun save(records: List<WorkspaceRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("environment", record.environment.name)
                put("guestAlias", record.guestAlias)
                put("base", record.base)
                put("path", record.path)
                put("verifiedAt", record.verifiedAt)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "records"
        const val MAX_RECORDS = 50
    }
}

/** Pure lexical and command builders for the managed workspace lifecycle. */
object WorkspaceLifecycle {
    private const val MAX_DELETE_PATH = 1_024
    val ACTIVE_OBJECTIVE_STATUSES = setOf("planning", "running", "paused", "needs_attention", "verifying")

    fun hasActiveObjective(running: Boolean, statuses: Iterable<String>): Boolean =
        running || statuses.any { it in ACTIVE_OBJECTIVE_STATUSES }

    fun startupDecision(savedPath: String, check: WorkspaceCheck?): WorkspaceStartupDecision = when {
        savedPath.isBlank() -> WorkspaceStartupDecision.None
        check is WorkspaceCheck.Valid && check.normalizedPath == savedPath.trimEnd('/') ->
            WorkspaceStartupDecision.Reuse(check.normalizedPath)
        else -> WorkspaceStartupDecision.Unavailable
    }

    fun isManaged(base: String, path: String): Boolean {
        val root = base.trimEnd('/')
        val target = path.trimEnd('/')
        if (root.length <= 1 || !root.startsWith('/')) return false
        if (target.length <= root.length || !target.startsWith("$root/")) return false
        if (target.length > MAX_DELETE_PATH || target.contains("..") || target.contains("//")) return false
        if (target.any { it == '\u0000' || it.isISOControl() }) return false
        val leaf = target.removePrefix("$root/")
        return leaf.isNotBlank() && !leaf.contains('/') && leaf != "." && leaf != ".."
    }

    /**
     * Removes exactly one direct child of [base]. Every check runs before `rm`; a symlink, a
     * non-canonical path, the parent root itself, or a path outside the managed root is refused.
     */
    fun deleteScript(base: String, path: String): String {
        val root = shellQuote(base.trimEnd('/'))
        val target = shellQuote(path.trimEnd('/'))
        return """
            # NYX managed workspace deletion. No parent, model directory, or unrelated path is touched.
            export LC_ALL=C
            base=$root
            ws=$target
            fail() { printf 'NYX_WS_DELETED=0\nNYX_WS_ERROR=%s\n' "${'$'}1"; exit 4; }
            [ "${'$'}base" != "${'$'}ws" ] || fail 'refusing to delete the managed workspace root'
            case "${'$'}ws" in "${'$'}base"/*) ;; *) fail 'path is outside the managed workspace root' ;; esac
            leaf=${'$'}{ws#"${'$'}base"/}
            [ -n "${'$'}leaf" ] || fail 'empty workspace name'
            case "${'$'}leaf" in */*|.|..) fail 'path is not a direct child of the managed workspace root' ;; esac
            [ -d "${'$'}base" ] && [ ! -L "${'$'}base" ] || fail 'managed workspace root is not a real directory'
            [ -d "${'$'}ws" ] && [ ! -L "${'$'}ws" ] || fail 'workspace is not a real directory'
            real_base=${'$'}(realpath -e -- "${'$'}base" 2>/dev/null) || fail 'managed workspace root cannot be resolved'
            real_ws=${'$'}(realpath -e -- "${'$'}ws" 2>/dev/null) || fail 'workspace cannot be resolved'
            [ "${'$'}real_base" = "${'$'}base" ] || fail 'managed workspace root is not canonical'
            [ "${'$'}real_ws" = "${'$'}ws" ] || fail 'workspace is not canonical or is a symlink'
            [ "${'$'}(dirname -- "${'$'}ws")" = "${'$'}base" ] || fail 'workspace is not a direct child'
            rm -rf -- "${'$'}ws" || fail 'filesystem refused the deletion'
            [ ! -e "${'$'}ws" ] && [ ! -L "${'$'}ws" ] || fail 'workspace still exists after deletion'
            printf 'NYX_WS_DELETED=1\nNYX_WS_PATH=%s\n' "${'$'}ws"
        """.trimIndent()
    }

    fun parseDelete(stdout: String, stderr: String, exitCode: Int?, expectedPath: String): WorkspaceDeleteResult {
        val flags = stdout.lineSequence().mapNotNull { line ->
            val text = line.trim()
            val separator = text.indexOf('=')
            if (separator <= 0) null else text.substring(0, separator) to text.substring(separator + 1)
        }.toMap()
        if (flags["NYX_WS_DELETED"] == "1" && flags["NYX_WS_PATH"] == expectedPath.trimEnd('/')) {
            return WorkspaceDeleteResult.Deleted(expectedPath.trimEnd('/'))
        }
        val detail = flags["NYX_WS_ERROR"]?.take(240)
            ?: stderr.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
            ?: "workspace deletion did not complete (exit ${exitCode ?: "unknown"})"
        return WorkspaceDeleteResult.Refused("delete_failed", detail)
    }

    /** True when a concrete grant exists for another workspace but none covers [workspace]. */
    fun hasScopeMismatch(
        grants: List<CapabilityGrant>,
        environmentId: String,
        workspace: String,
        capability: String,
    ): Boolean {
        val relevant = grants.filter {
            !it.revoked && it.capability == capability &&
                (it.environmentId == "*" || it.environmentId == environmentId)
        }
        return relevant.isNotEmpty() && relevant.none { scopeCovers(it.scope, workspace) }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

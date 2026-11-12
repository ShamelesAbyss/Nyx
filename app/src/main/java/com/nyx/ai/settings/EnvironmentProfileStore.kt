package com.nyx.ai.settings

import android.content.Context
import android.util.AtomicFile
import com.nyx.ai.terminal.CapabilityState
import com.nyx.ai.terminal.EnvironmentCapabilities
import com.nyx.ai.terminal.ToolCapability
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure JSON codec for [EnvironmentCapabilities]. It touches no Android API so it can be unit tested
 * on the JVM. The schema is versioned, every field is bounded, and unknown or corrupt content is
 * rejected instead of being repaired by guesswork. Only capability evidence is encoded: no secrets
 * exist in the model, and the only absolute paths written are observed tool paths.
 */
object EnvironmentProfileCodec {
    const val SCHEMA_VERSION = 1

    /** Bound for one encoded profile; the store applies the same bound to the whole file. */
    const val MAX_TEXT_CHARS = 256 * 1024
    const val MAX_ENVIRONMENT_ID_CHARS = 128
    const val MAX_KIND_CHARS = 64
    const val MAX_SOURCE_CHARS = 32
    const val MAX_TOOLS = 64
    const val MAX_VERSION_CHARS = 80
    const val MAX_PATH_CHARS = 4_096
    const val MAX_DETAIL_CHARS = 256

    private const val DEFAULT_SOURCE = "probe"
    private val TOOL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
    private val STATE_BY_NAME: Map<String, CapabilityState> = CapabilityState.entries.associateBy { it.name }

    /** Encodes one profile as a self-describing JSON object. */
    fun encode(profile: EnvironmentCapabilities): String {
        val tools = JSONObject()
        for ((name, tool) in profile.tools) {
            if (tools.length() >= MAX_TOOLS) break
            if (!TOOL_NAME.matches(name)) continue
            val entry = JSONObject().put("state", tool.state.name)
            tool.path?.let { entry.put("path", it.take(MAX_PATH_CHARS)) }
            tool.version?.let { entry.put("version", it.take(MAX_VERSION_CHARS)) }
            tool.detail?.let { entry.put("detail", it.take(MAX_DETAIL_CHARS)) }
            tools.put(name, entry)
        }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("environmentId", profile.environmentId.take(MAX_ENVIRONMENT_ID_CHARS))
            .put("kind", profile.kind.take(MAX_KIND_CHARS))
            .put("checkedAt", profile.checkedAt)
            .put("source", profile.source.take(MAX_SOURCE_CHARS))
            .put("storageFreeBytes", profile.storageFreeBytes ?: JSONObject.NULL)
            .put("workspaceAccessible", profile.workspaceAccessible ?: JSONObject.NULL)
            .put("tools", tools)
            .toString()
    }

    /** Decodes one profile, or returns null when the text is corrupt, oversized, or the wrong schema. */
    fun decode(text: String): EnvironmentCapabilities? {
        if (text.isBlank() || text.length > MAX_TEXT_CHARS) return null
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        if (root.optInt("schemaVersion", SCHEMA_VERSION) != SCHEMA_VERSION) return null
        val environmentId = root.optionalText("environmentId")?.trim().orEmpty()
        if (environmentId.isEmpty() || environmentId.length > MAX_ENVIRONMENT_ID_CHARS) return null
        if (environmentId.any { it.isISOControl() }) return null
        val kind = root.optionalText("kind")?.trim().orEmpty()
        if (kind.isEmpty() || kind.length > MAX_KIND_CHARS) return null
        val checkedAt = root.optLong("checkedAt", -1L)
        if (checkedAt <= 0L) return null
        val storageFreeBytes = if (root.has("storageFreeBytes") && !root.isNull("storageFreeBytes")) {
            root.optLong("storageFreeBytes", -1L).takeIf { it >= 0L }
        } else {
            null
        }
        val workspaceAccessible = if (root.has("workspaceAccessible") && !root.isNull("workspaceAccessible")) {
            root.optBoolean("workspaceAccessible")
        } else {
            null
        }
        val source = root.optionalText("source")?.take(MAX_SOURCE_CHARS)?.ifBlank { null } ?: DEFAULT_SOURCE
        return EnvironmentCapabilities(
            environmentId = environmentId,
            kind = kind,
            checkedAt = checkedAt,
            tools = decodeTools(root.optJSONObject("tools")),
            storageFreeBytes = storageFreeBytes,
            workspaceAccessible = workspaceAccessible,
            source = source,
        )
    }

    private fun decodeTools(raw: JSONObject?): Map<String, ToolCapability> {
        if (raw == null) return emptyMap()
        val tools = LinkedHashMap<String, ToolCapability>()
        val names = raw.keys()
        while (names.hasNext() && tools.size < MAX_TOOLS) {
            val name = names.next()
            if (!TOOL_NAME.matches(name)) continue
            val entry = raw.optJSONObject(name) ?: continue
            val state = STATE_BY_NAME[entry.optionalText("state").orEmpty().uppercase()] ?: continue
            val path = entry.optionalText("path")?.take(MAX_PATH_CHARS)?.takeIf(::isPlausiblePath)
            if (state == CapabilityState.VERIFIED && path == null) continue
            val version = entry.optionalText("version")?.take(MAX_VERSION_CHARS)
            val detail = entry.optionalText("detail")?.take(MAX_DETAIL_CHARS)
            tools[name] = ToolCapability(name, state, path, version, detail)
        }
        return tools
    }

    private fun isPlausiblePath(path: String): Boolean =
        path.length in 2..MAX_PATH_CHARS && path.startsWith('/') && path.none { it == '\u0000' }

    /** `optString` turns an explicit JSON null into "null" on some platforms, so check first. */
    private fun JSONObject.optionalText(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}

/**
 * Stores the latest [EnvironmentCapabilities] per environment in app-private storage. Writes are
 * atomic, reads are bounded to [MAX_FILE_BYTES], and corrupt or oversized files are treated as empty
 * instead of throwing. A profile is a cache of probe evidence: losing it only forces a new probe.
 */
class EnvironmentProfileStore(context: Context, fileName: String = DEFAULT_FILE_NAME) {
    private val file: AtomicFile

    init {
        require(FILE_NAME.matches(fileName)) { "Invalid environment profile file name." }
        file = AtomicFile(File(context.filesDir, fileName))
    }

    /** Returns every stored profile keyed by environment id. Never throws on corrupt data. */
    @Synchronized
    fun load(): Map<String, EnvironmentCapabilities> {
        val text = readBoundedText() ?: return emptyMap()
        return try {
            val root = JSONObject(text)
            if (root.optInt("schemaVersion", EnvironmentProfileCodec.SCHEMA_VERSION) !=
                EnvironmentProfileCodec.SCHEMA_VERSION
            ) {
                return emptyMap()
            }
            val entries = root.optJSONArray("profiles") ?: return emptyMap()
            val profiles = LinkedHashMap<String, EnvironmentCapabilities>()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val profile = EnvironmentProfileCodec.decode(entry.toString()) ?: continue
                profiles[profile.environmentId] = profile
            }
            profiles
        } catch (e: RuntimeException) {
            // Corrupt JSON must never break the settings screen.
            emptyMap()
        }
    }

    /** Replaces the stored profile for its environment id. */
    @Synchronized
    fun save(profile: EnvironmentCapabilities) {
        requireProfile(profile)
        val profiles = LinkedHashMap(load())
        profiles[profile.environmentId] = profile
        writeBounded(profiles)
    }

    /** Removes one environment. Missing ids are ignored. */
    @Synchronized
    fun remove(environmentId: String) {
        val profiles = LinkedHashMap(load())
        if (profiles.remove(environmentId) == null) return
        if (profiles.isEmpty()) file.delete() else writeBounded(profiles)
    }

    /** Removes every stored profile. */
    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun writeBounded(profiles: MutableMap<String, EnvironmentCapabilities>) {
        var json = encodeEnvelope(profiles.values)
        if (json.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            for (profile in profiles.values.sortedBy { it.checkedAt }) {
                if (json.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) break
                profiles.remove(profile.environmentId)
                json = encodeEnvelope(profiles.values)
            }
        }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Environment profile store exceeds $MAX_FILE_BYTES bytes."
        }
        write(json)
    }

    private fun encodeEnvelope(profiles: Collection<EnvironmentCapabilities>): String {
        val array = JSONArray()
        for (profile in profiles) array.put(JSONObject(EnvironmentProfileCodec.encode(profile)))
        return JSONObject()
            .put("schemaVersion", EnvironmentProfileCodec.SCHEMA_VERSION)
            .put("profiles", array)
            .toString()
    }

    private fun readBoundedText(): String? {
        val base = file.baseFile
        if (!base.isFile || base.length() > MAX_FILE_BYTES) return null
        return try {
            String(file.readFully(), Charsets.UTF_8)
        } catch (e: IOException) {
            null
        }
    }

    private fun write(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun requireProfile(profile: EnvironmentCapabilities) {
        val id = profile.environmentId
        require(id.isNotBlank() && id.length <= EnvironmentProfileCodec.MAX_ENVIRONMENT_ID_CHARS) {
            "An environment id of at most ${EnvironmentProfileCodec.MAX_ENVIRONMENT_ID_CHARS} characters is required."
        }
        require(id.none { it.isISOControl() || it == '/' || it == '\\' }) {
            "An environment id must not contain control characters or path separators."
        }
        require(profile.kind.isNotBlank()) { "An environment kind is required." }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "nyx_environment_profiles.json"
        const val MAX_FILE_BYTES = EnvironmentProfileCodec.MAX_TEXT_CHARS
        private val FILE_NAME = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

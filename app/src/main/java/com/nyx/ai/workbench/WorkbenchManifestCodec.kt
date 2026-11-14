package com.nyx.ai.workbench

import org.json.JSONArray
import org.json.JSONObject

internal object WorkbenchManifestCodec {
    fun encode(m: WorkbenchManifest): ByteArray {
        val sources = JSONArray()
        m.sources.forEach { s -> sources.put(JSONObject().put("id", s.id).put("url", s.url).put("finalUrl", s.finalUrl)
            .put("title", s.title).put("fetchedAt", s.fetchedAt).put("contentType", s.contentType).put("bytes", s.bytes)
            .put("sha256", s.sha256).put("status", s.status).put("note", s.note)) }
        val artifacts = JSONArray()
        m.artifacts.forEach { a -> artifacts.put(JSONObject().put("id", a.id).put("fileName", a.fileName)
            .put("format", a.format.name).put("bytes", a.bytes).put("sha256", a.sha256).put("createdAt", a.createdAt)
            .put("status", a.status.name).put("validationNote", a.validationNote).put("relativePath", a.relativePath)) }
        val imports = JSONArray()
        m.imports.forEach { i -> imports.put(JSONObject().put("id", i.id).put("fileName", i.fileName)
            .put("bytes", i.bytes).put("sha256", i.sha256).put("importedAt", i.importedAt)
            .put("contentType", i.contentType).put("relativePath", i.relativePath)) }
        return JSONObject().put("version", m.version).put("objectiveId", m.objectiveId)
            .put("createdAt", m.createdAt).put("updatedAt", m.updatedAt).put("status", m.status)
            .put("sources", sources).put("artifacts", artifacts).put("imports", imports).toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): WorkbenchManifest {
        val text = ArtifactFormats.utf8(bytes)
        // Inspect original tokens before org.json can round an overflowing integer to Double.
        // Version 1 emits only integral JSON numbers, never decimal/exponent/string encodings.
        StrictJson(text) { token -> require(token.toLongOrNull() != null) { "Manifest numbers must be exact signed 64-bit integers" } }.parse()
        val o = JSONObject(text)
        require(integer(o, "version") == 1L) { "Unsupported manifest version" }
        val sources = o.getJSONArray("sources"); val artifacts = o.getJSONArray("artifacts"); val imports = o.getJSONArray("imports")
        require(sources.length() <= 60 && artifacts.length() <= 40 && imports.length() <= 20) { "Manifest count bounds exceeded" }
        return WorkbenchManifest(string(o, "objectiveId"), integer(o, "createdAt"), integer(o, "updatedAt"),
            (0 until sources.length()).map { n -> sources.getJSONObject(n).let { s -> ResearchSource(
                string(s, "url"), string(s, "finalUrl"), string(s, "title"), integer(s, "fetchedAt"),
                string(s, "contentType"), integer(s, "bytes"), string(s, "sha256"), string(s, "status"), string(s, "note"), string(s, "id")) } },
            (0 until artifacts.length()).map { n -> artifacts.getJSONObject(n).let { a -> ArtifactRecord(
                string(a, "id"), string(a, "fileName"), ArtifactFormat.valueOf(string(a, "format")), integer(a, "bytes"),
                string(a, "sha256"), integer(a, "createdAt"), ArtifactStatus.valueOf(string(a, "status")),
                string(a, "validationNote"), string(a, "relativePath")) } },
            (0 until imports.length()).map { n -> imports.getJSONObject(n).let { i -> ImportRecord(
                string(i, "id"), string(i, "fileName"), integer(i, "bytes"), string(i, "sha256"),
                integer(i, "importedAt"), string(i, "contentType"), string(i, "relativePath")) } },
            string(o, "status"), 1)
    }

    private fun integer(o: JSONObject, key: String): Long {
        val value = o.get(key)
        require(value is Int || value is Long) { "Manifest $key must be an integral JSON number" }
        return (value as Number).toLong()
    }

    private fun string(o: JSONObject, key: String): String {
        val value = o.get(key)
        require(value is String) { "Manifest $key must be a JSON string" }
        return value
    }
}

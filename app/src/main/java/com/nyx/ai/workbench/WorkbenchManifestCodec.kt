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
        StrictJson(text).parse()
        val o = JSONObject(text)
        require(o.getInt("version") == 1) { "Unsupported manifest version" }
        val sources = o.getJSONArray("sources"); val artifacts = o.getJSONArray("artifacts"); val imports = o.getJSONArray("imports")
        require(sources.length() <= 60 && artifacts.length() <= 40 && imports.length() <= 20) { "Manifest count bounds exceeded" }
        return WorkbenchManifest(o.getString("objectiveId"), o.getLong("createdAt"), o.getLong("updatedAt"),
            (0 until sources.length()).map { n -> sources.getJSONObject(n).let { s -> ResearchSource(
                s.getString("url"), s.getString("finalUrl"), s.getString("title"), s.getLong("fetchedAt"),
                s.getString("contentType"), s.getLong("bytes"), s.getString("sha256"), s.getString("status"), s.getString("note"), s.getString("id")) } },
            (0 until artifacts.length()).map { n -> artifacts.getJSONObject(n).let { a -> ArtifactRecord(
                a.getString("id"), a.getString("fileName"), ArtifactFormat.valueOf(a.getString("format")), a.getLong("bytes"),
                a.getString("sha256"), a.getLong("createdAt"), ArtifactStatus.valueOf(a.getString("status")),
                a.getString("validationNote"), a.getString("relativePath")) } },
            (0 until imports.length()).map { n -> imports.getJSONObject(n).let { i -> ImportRecord(
                i.getString("id"), i.getString("fileName"), i.getLong("bytes"), i.getString("sha256"),
                i.getLong("importedAt"), i.getString("contentType"), i.getString("relativePath")) } },
            o.getString("status"), o.getInt("version"))
    }
}

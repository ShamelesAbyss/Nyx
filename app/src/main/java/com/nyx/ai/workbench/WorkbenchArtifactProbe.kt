package com.nyx.ai.workbench

import com.nyx.ai.agent.FileProbe

/** Virtual names refer to registered artifacts only, never an arbitrary app-private path. */
class WorkbenchArtifactProbe(private val store: WorkbenchStore, private val fallback: FileProbe? = null) : FileProbe {
    private fun record(path: String): ArtifactRecord? {
        if (!path.startsWith("workbench/")) return null
        val name = path.removePrefix("workbench/")
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}").matches(name) || name.contains("..")) return null
        return store.listArtifacts().singleOrNull { it.fileName == name }
    }
    override suspend fun exists(path: String): Boolean = if (path.startsWith("workbench/"))
        record(path)?.let { store.verifyArtifact(it) } ?: false else fallback?.exists(path) ?: false
    override suspend fun read(path: String, maxChars: Int): String? = if (path.startsWith("workbench/"))
        record(path)?.takeIf { it.format !in setOf(ArtifactFormat.ZIP, ArtifactFormat.PDF) }
            ?.let { store.readArtifact(it).toString(Charsets.UTF_8).take(maxChars.coerceIn(0, 64_000)) }
        else fallback?.read(path, maxChars)
    override suspend fun sha256(path: String): String? = if (path.startsWith("workbench/"))
        record(path)?.takeIf { store.verifyArtifact(it) }?.sha256 else fallback?.sha256(path)
}

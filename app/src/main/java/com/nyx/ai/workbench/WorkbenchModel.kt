package com.nyx.ai.workbench

/** Formats are actual validated files, not claims inferred from a filename. PDF is deferred. */
enum class ArtifactFormat(val extension: String, val mimeType: String, val maxBytes: Long = 8L * 1024 * 1024) {
    MARKDOWN("md", "text/markdown"), TEXT("txt", "text/plain"), CSV("csv", "text/csv"),
    JSON("json", "application/json"), HTML("html", "text/html"),
    PDF("pdf", "application/pdf"), ZIP("zip", "application/zip");
    val mime: String get() = mimeType
}

enum class ArtifactStatus { VERIFIED, FAILED }

data class ArtifactRecord(
    val id: String, val fileName: String, val format: ArtifactFormat, val bytes: Long,
    val sha256: String, val createdAt: Long, val status: ArtifactStatus = ArtifactStatus.VERIFIED,
    val validationNote: String = "", val relativePath: String
)

data class ResearchSource(
    val url: String, val finalUrl: String = url, val title: String = "",
    val fetchedAt: Long = System.currentTimeMillis(), val contentType: String = "",
    val bytes: Long = 0, val sha256: String = "", val status: String = "FETCHED",
    val note: String = "", val id: String = ""
)

data class ImportRecord(
    val id: String, val fileName: String, val bytes: Long, val sha256: String,
    val importedAt: Long, val contentType: String, val relativePath: String
)

data class WorkbenchManifest(
    val objectiveId: String, val createdAt: Long, val updatedAt: Long,
    val sources: List<ResearchSource> = emptyList(), val artifacts: List<ArtifactRecord> = emptyList(),
    val imports: List<ImportRecord> = emptyList(), val status: String = "READY", val version: Int = 1
)

data class WorkbenchLimits(
    val maxArtifacts: Int = 40, val maxArtifactBytes: Long = 8L * 1024 * 1024,
    val maxTotalBytes: Long = 32L * 1024 * 1024, val maxSources: Int = 60,
    val maxSourceBytes: Long = 512L * 1024, val maxImports: Int = 20,
    val maxImportBytes: Long = 8L * 1024 * 1024, val maxMetadataBytes: Long = 256L * 1024
) {
    init {
        require(maxArtifacts in 1..40 && maxSources in 1..60 && maxImports in 1..20)
        require(maxArtifactBytes in 1..8L * 1024 * 1024 && maxTotalBytes in 1..32L * 1024 * 1024)
        require(maxSourceBytes in 1..512L * 1024 && maxImportBytes in 1..8L * 1024 * 1024)
        require(maxMetadataBytes in 1..256L * 1024)
    }
}

data class ValidationResult(val valid: Boolean, val note: String)

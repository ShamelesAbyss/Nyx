# Context and offline skills foundation

The context and manifest packages are deterministic, local and non-executing. In the final v0.6 integration, ObjectiveExecutionEngine validates fixed builtin procedures against workspace authorization, actual project readiness and host tools, restricts their tool subset and records skill_scope events. The source-repair lifecycle runs through the existing terminal adapter. Third-party manifests have no import/execution UI or executable plugin registration; parsed data alone never gains authority.

## Existing API compatibility

The `ContextStore(File)` constructor and existing `write`, `read`, `list`, `deleteObjective`, and `totalBytes` signatures are unchanged. Existing Kotlin `ContextRecord`, `ContextRequest`, and `BuiltContext` constructor calls remain source compatible: new fields are trailing and have defaults. Existing `ContextEngineTest.kt` remains unchanged. Legacy sidecars without the new fields still load.

`read(record, maxChars)` now streams UTF-8 characters, allocating at most a 4,096-character transfer buffer and caller-budgeted output. It never loads the whole file before truncating it. A decoder can read a small bounded amount ahead; the limit is UTF-16 characters, matching the old Kotlin `String.take` behavior, not raw bytes or tokens. Nonpositive budgets return an empty string. Invalid, missing, and out-of-root paths still read as empty.

Sidecar reads also stream and reject metadata exceeding 65,536 characters. Writes reject oversized sidecars before publishing content. Traversal for listing, byte accounting, and deletion does not follow directory symlinks outside the store. Existing atomic content/sidecar publication is preserved; publishing two files is not a transactional multi-file database operation.

## Durable evidence, summaries, and checkpoints

All types below are in `com.nyx.ai.context`.

```kotlin
data class ContextEvidenceReference(
    val recordId: String,
    val filePath: String,
    val sha256: String,
)

// Existing eight fields are unchanged, followed by:
// evidence: List<ContextEvidenceReference> = emptyList()
// sourceReferences: List<ProjectSourceReference> = emptyList()
// contentSha256: String? = null

data class ProjectSourceReference(
    val scopeId: String,
    val relativePath: String,
    val sha256: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
)
```

Exact added `ContextStore` entry points:

```kotlin
fun writeObservation(
    objectiveId: String, taskId: String?, title: String, text: String,
    source: ProjectSourceReference,
): ContextRecord

fun compact(
    objectiveId: String, taskId: String?, title: String, summary: String,
    recordIds: List<String>, budgetChars: Int = 2_000,
): ContextRecord

fun checkpoint(
    objectiveId: String, taskId: String?, summary: String,
    recordIds: List<String> = emptyList(), budgetChars: Int = 2_000,
): ContextRecord

fun latestCheckpoint(objectiveId: String, taskId: String? = null): ContextRecord?
fun hasEvidence(reference: ContextEvidenceReference): Boolean
fun readEvidence(reference: ContextEvidenceReference, maxChars: Int = 8_000): String
fun isUsable(
    record: ContextRecord,
    sourceValidator: ((ProjectSourceReference) -> Boolean)? = null,
): Boolean
```

Compaction is deterministic, caller-supplied text, not model-generated summarization. It writes a new `summary` record capped by `budgetChars`; checkpoint writes a `checkpoint` record with the same durability mechanism. Original records remain on disk, with full-content SHA-256 references persisted in the new sidecar. Nested compaction retains transitive evidence references and project source identities. Up to 64 selected record IDs and 128 transitive references are accepted. Evidence must belong to the same objective and be readable and unchanged; otherwise compaction fails before publishing a new summary. `deleteObjective` remains the intentional deletion API for both originals and summaries.

Every newly written record stores its own content SHA-256. `isUsable` rejects changed record bytes, missing or changed evidence, and stale project sources. Legacy records lack a content identity and retain their legacy behavior; historical records cannot be retroactively attributed to a project without a source reference.

`readEvidence` explicitly accesses historical stored evidence by its digest. A source-file change invalidates an observation for **current** retrieval but does not erase the historical original copy. A changed original evidence file fails its digest check and is not returned by `readEvidence`.

Checkpoints record bounded resumption guidance and evidence, not scheduler or service state. They can be reopened with a new `ContextStore` instance. Task-specific lookup uses `latestCheckpoint(objectiveId, taskId)`; omitting `taskId` finds the latest checkpoint across the objective. Records with equal creation times retain the existing deterministic ID tie-break rather than a strict insertion sequence.

## Deterministic scoped project index

```kotlin
class ProjectIndex(
    root: File,
    relativePaths: Set<String> = setOf("."),
    excludedDirectories: Set<String> =
        setOf(".git", ".gradle", "build", "node_modules", ".idea"),
) {
    val scopeId: String
    fun build(limits: ProjectIndexLimits = ProjectIndexLimits()): ProjectIndexSnapshot
    fun refresh(
        previous: ProjectIndexSnapshot,
        limits: ProjectIndexLimits = ProjectIndexLimits(),
    ): ProjectIndexRefresh
    fun isCurrent(source: ProjectSourceReference): Boolean
    fun retrieve(
        snapshot: ProjectIndexSnapshot, query: String,
        budgetChars: Int = 4_000, maxFiles: Int = 6, maxFileChars: Int = 1_500,
    ): ProjectRetrieval
}

data class ProjectIndexLimits(
    val maxFiles: Int = 1_000,
    val maxFileBytes: Long = 1_048_576,
    val maxTotalBytes: Long = 16_777_216,
    val maxVisitedEntries: Int = 10_000,
    val maxDepth: Int = 64,
)

data class ProjectIndexSnapshot(
    val scopeId: String,
    val sources: List<ProjectSourceReference>,
    val complete: Boolean,
    val skippedPaths: List<String> = emptyList(),
)

data class ProjectIndexRefresh(
    val snapshot: ProjectIndexSnapshot,
    val changedPaths: List<String>,
    val removedPaths: List<String>,
    val invalidatedSources: List<ProjectSourceReference>,
)

data class ProjectRetrieval(
    val text: String,
    val sources: List<ProjectSourceReference>,
    val usedChars: Int,
    val truncated: Boolean,
    val invalidatedPaths: List<String> = emptyList(),
)
```

The scope ID hashes a length-framed canonical root, sorted explicit relative paths, and sorted exclusion names. Traversal sorts names and output sorts relative paths; locale-independent path-keyword overlap ranks retrieval, with relative path as the tie-break. The index is path-ranked retrieval, not a full-text database or embedding engine. Snapshots contain no clock-based index timestamp and repeat deterministically for unchanged filesystem metadata and contents.

Absolute paths, traversal, control characters in selected paths, and symlink ancestors are rejected or omitted. Directory enumeration and traversal depth are bounded. If a directory exceeds the remaining entry budget, the entire directory is omitted rather than using a nondeterministic filesystem-order prefix. Intentional exclusions are not treated as errors. Oversized files, unreadable files, traversal exhaustion, and files changing during hashing mark the snapshot incomplete. Only full-file hashes are published; a truncated prefix is never labeled as a complete file's SHA-256.

`refresh` compares content hashes, not only size or mtime. Changed, deleted, and now-unindexed paths invalidate prior references. `removedPaths` means absent from the new bounded snapshot, which can include files excluded by tighter limits, not only physical deletion. New files appear in the new snapshot. `modifiedAt` is informational filesystem mtime; unchanged bytes with a new mtime are still current.

`isCurrent` re-hashes the entire file even if its size and mtime are unchanged. `retrieve` validates before and after a bounded content read, skips stale sources, and counts headers, hashes, mtimes, and separators against its exact character budget. Hashing streams use a fixed buffer; output character budgets do not eliminate the I/O needed to establish a full-content identity.

The index only reads the supplied local `File` root. For a Termux-only workspace inaccessible to the app filesystem, use the existing file/terminal evidence interfaces; this package does not invent a second bridge. Snapshots can be rebuilt after restart; persisted observations keep their source references. Before treating an observation as current, supply a validator for its actual scope. Ordinary filesystem freshness checks are not an atomic filesystem snapshot and do not claim protection against adversarial simultaneous symlink replacement or concurrent writes after the final check.

## Context assembly integration

`ContextRequest` adds trailing defaults:

```kotlin
val sourceValidator: ((ProjectSourceReference) -> Boolean)? = null
val projectContext: ProjectRetrieval? = null
```

`BuiltContext` adds `invalidatedRecordIds: List<String> = emptyList()`.

`ContextEngine.build(request, store)` retains its strict final character budget, goal reminder, dependency prioritization, relevant-history limits, and failure history. It omits unusable records and reports invalidated IDs. Source-linked records fail closed when no validator is supplied. The newest usable checkpoint is considered after dependency results. Relevant compacted summaries rank before their represented originals; once a summary/checkpoint is included, redundant referenced records are skipped. Up to eight evidence IDs are displayed with an additional-reference count; all references remain durable in the sidecar.

Prebuilt project retrieval is revalidated at assembly time and omitted if any source is stale, no validator is supplied, the source list is empty, or the validator fails. Project context also counts against the existing total budget.

Example for an app-readable project root:

```kotlin
val index = ProjectIndex(projectRoot, relativePaths = setOf("src", "README.md"))
val snapshot = index.build()
val projectEvidence = index.retrieve(snapshot, taskTitle, budgetChars = 2_000)
val built = ContextEngine.build(
    ContextRequest(
        objectiveId, goal, taskTitle, instructions, verification,
        budgetChars = 12_000,
        sourceValidator = index::isCurrent,
        projectContext = projectEvidence,
    ),
    store,
)
val checkpoint = store.checkpoint(
    objectiveId, taskId, "Verified findings and next safe step",
    recordIds = built.includedRecordIds, budgetChars = 1_500,
)
```

For multiple project scopes, dispatch `sourceValidator` by `scopeId`; never treat an unrelated snapshot or matching mtime as sufficient freshness evidence. Capture observations only after actually inspecting the referenced content; `writeObservation` is a persistence API, not an automatic verification that arbitrary caller text correctly describes a file.

## Typed offline skills

All skill types are in `com.nyx.ai.skills`. `SkillManifest` contains:

```kotlin
val id: String
val version: String
val description: String
val tools: Set<String>
val requiredPermissions: Set<SkillPermission>
val preconditions: Set<SkillPrecondition>
val constraints: SkillConstraints = SkillConstraints()
val failureHandling: SkillFailureHandling = SkillFailureHandling()
val compatibility: SkillCompatibility = SkillCompatibility()
val instructions: List<String> = emptyList()
```

`SkillPermission`: `WORKSPACE_READ`, `WORKSPACE_WRITE`, `TERMINAL_EXECUTE`, `BUILD_EXECUTE`, `TEST_EXECUTE`, `GIT_READ`.

`SkillPrecondition`: `WORKSPACE_SELECTED`, `TERMINAL_AVAILABLE`, `PROJECT_DETECTED`, `GIT_REPOSITORY`.

`SkillConstraints` defaults: `workspaceOnly=true`, `allowNetwork=false`, `allowDestructive=false`, `allowAutoCommit=false`, `requireWriteApproval=true`, `maxSteps=12`, `maxOutputChars=8000`. Validation refuses to relax the first five safety restrictions or disable write approval; step/output bounds are 1–30 and 1–32,000.

`SkillFailureHandling` defaults to `STOP_AND_REPORT`, zero retries, and preserved evidence. `RETRY_THEN_REPORT` allows exactly one explicitly handled retry; unlimited retry or evidence discard is invalid. `SkillCompatibility` defaults to schema `manifestVersion=1`, `minHostVersion="1.0.0"`, and `maxHostMajor=1`; numeric major/minor/patch checks reject incompatible schemas or hosts.

Exact public entry points:

```kotlin
SkillManifestParser.parse(text: String): SkillParseResult
SkillValidator.validateUntrusted(
    manifest: SkillManifest, context: SkillValidationContext,
): SkillValidationResult
BuiltinSkillRegistry.all(): List<SkillManifest>
BuiltinSkillRegistry.find(id: String): SkillManifest?
BuiltinSkillRegistry.validate(
    id: String, context: SkillValidationContext,
): SkillValidationResult
```

`SkillValidationContext(allowedTools, grantedPermissions, satisfiedPreconditions, hostVersion="1.0.0")` is supplied by trusted host controls. Parse and validation results contain structured `issues` and `isValid`. Successful validation returns a `ValidatedSkill` with a defensively copied manifest, `origin`, `allowedTools`, and `requiredPermissions`.

The JSON parser caps input at 32,000 characters, requires field types and required structured blocks, rejects unknown fields and enums, duplicate array entries, and trailing data. There is no `trusted`, tool-registration, or permission-grant field. Parsing only produces untrusted data and is **not authorization**.

Imported manifests have a fixed read-only ceiling: `workspace_read`, `file_read`, `file_list`, `file_search`, and `project_detect`, with only `WORKSPACE_READ`. Even if the host possesses broader permissions, an imported manifest cannot request shell, write, build, test, new tools, or broader permissions. It cannot claim `builtin.*` identity. Missing required tool permissions, workspace preconditions, or host grants fail closed.

## Explicit builtin registry and parent controls

The only trusted registry has five fixed IDs and no dynamic registration API:

| Constant | ID | Existing tools |
| --- | --- | --- |
| `WORKSPACE_INSPECTION` | `builtin.workspace-inspection` | `file_list`, `file_read`, `project_detect` |
| `SOURCE_REPAIR` | `builtin.source-repair` | `file_read`, `file_search`, `file_write` |
| `BUILD_TEST` | `builtin.build-test` | `project_detect`, `build_run`, `test_run` |
| `GIT_INSPECTION` | `builtin.git-inspection` | `repo_status`, `repo_diff`, `file_read` |
| `LOCAL_FILESYSTEM_RESEARCH` | `builtin.local-filesystem-research` | `file_list`, `file_search`, `file_read` |

Select a builtin by ID and call `BuiltinSkillRegistry.validate(id, hostContext)`. Trust cannot be obtained by passing a modified manifest back to the registry. Returned declarations are fresh copies; validation snapshots protect granted tool subsets from later mutation. Builtins still require all host tool grants, permissions, and satisfied preconditions. Build/test requires explicit terminal, build, test, workspace-write, and workspace-read grants. Git inspection requires `GIT_READ` and never instructs automatic staging, commits, history changes, fetches, or pushes.

Parent integration should present modest selection/inspection actions and surface validation issues. Do not union manifest tools into the provider allowlist. At invocation, restrict tools to the validated subset and re-check the existing host policy, explicit workspace, and per-command/write approvals. Preserve execution failures and exit evidence; apply declared retry/output/step limits in the existing execution controller.

This is a foundation, **not a new skill execution engine**. Constraints do not physically force project scripts offline. Existing adapters enforce authorization; fixed repo_status accepts no model command/options and returns local status/history, while repo_diff uses recorded source checkpoints. Raw model terminal_run is denied. Build scripts remain trusted code under Termux authority, not OS-sandboxed code. Validation never bypasses protected-command restrictions or user approval; no automatic commit action is provided.

## Verification

Standalone JVM compilation uses the already-cached Kotlin 2.0.21 compiler, existing real `org.json`, and JUnit 4 dependencies; no Gradle command or configuration edit is required. Tests run with a 96 MiB heap and include a bounded read from a 128 MiB sparse file, existing context round trips, malformed/oversized and legacy sidecars, durable/transitive evidence, checkpoints, deterministic scope/hash/mtime data, same-size/same-mtime content changes, traversal/symlink/depth/byte limits, strict retrieval/assembly budgets, and valid/invalid/escalating/untrusted/builtin manifests.

Source and test files:

- `app/src/main/java/com/nyx/ai/context/ContextStore.kt` (modified)
- `app/src/main/java/com/nyx/ai/context/ContextEngine.kt` (modified)
- `app/src/main/java/com/nyx/ai/context/ContextFiles.kt` (new internal streaming helper)
- `app/src/main/java/com/nyx/ai/context/ProjectIndex.kt` (new)
- `app/src/main/java/com/nyx/ai/skills/SkillManifest.kt` (new)
- `app/src/main/java/com/nyx/ai/skills/SkillValidator.kt` (new)
- `app/src/main/java/com/nyx/ai/skills/SkillManifestParser.kt` (new)
- `app/src/main/java/com/nyx/ai/skills/BuiltinSkillRegistry.kt` (new)
- `app/src/test/java/com/nyx/ai/context/ContextFoundationTest.kt` (new)
- `app/src/test/java/com/nyx/ai/skills/SkillFoundationTest.kt` (new)
- `docs/CONTEXT_AND_SKILLS.md` (new)

Final local verification: warning-free Kotlin compilation with `-Werror`, followed by **45 passing JVM tests** (`ContextEngineTest`, `ContextFoundationTest`, `SkillFoundationTest`) under `-Xmx96m`. No Gradle invocation was used. Temporary compiled classes were removed.

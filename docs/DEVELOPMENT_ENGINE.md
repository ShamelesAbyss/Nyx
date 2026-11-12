# Development engine (v0.6)

The development worker is a finite, evidence-driven **repair loop**, not a shell-enabled model agent. It discovers the approved remote workspace and installed toolchain, indexes bounded UTF-8 source files with SHA-256 hashes, executes a baseline build and test, requests a typed patch for failures, backs up all originals, atomically replaces each file, executes the build and test again, and repeats within hard limits. It returns observations rather than treating a model's success claim as proof.

## Integration contract

The public types are in `com.nyx.ai.development`. Engine/service/UI integration is deliberately outside this module.

```kotlin
val tools = TerminalAgentTools(
    bridge = bridge,
    workspaceRoot = approvedAbsoluteNormalizedRoot,
    environment = selectedTerminalEnvironment,
    timeoutSeconds = 180,
    useTimeoutBinary = true,
    workspaceApproved = true, // Only after task-specific trusted-project opt-in.
)
val development = DevelopmentOrchestrator(TerminalDevelopmentEnvironment(tools))
val report: DevelopmentReport = development.run(
    goal = task.goal,
    provider = selectedProvider, // Existing com.nyx.ai.network.InferenceProvider.
    model = selectedModel,
    options = inferenceOptions, // Existing InferenceOptions, not a new provider API.
    budget = DevelopmentBudget(
        maxIterations = 3,
        maxInferenceCalls = 4,
        maxElapsedMillis = 300_000,
    ),
    onEvent = { event: DevelopmentEvent -> /* persist/display phase, iteration, message */ },
)
```

`run(goal, provider, model, options, budget, onEvent)` is suspending. The options, budget and callback have defaults. A blank goal/model is rejected; goal length is capped at 8,000 characters. One orchestrator instance serializes its runs. The engine should serialize access to the same `TerminalAgentTools` instance, including tool execution and `drainEvidence()`: draining is destructive, so two workers must not share its evidence stream. No changes to provider, networking, VM, service or UI APIs are required by this implementation.

**Migration requirement:** the old `TerminalAgentTools(bridge, root, environment)` constructor remains source-compatible, but now defaults to `workspaceApproved = false`. All terminal IPC, including inspection and reads, is denied until the host passes task-specific approval. Resolve `~/...` remotely in the parent before construction, use an absolute normalized root without a trailing slash, and pass the user's selected Termux/PRoot environment. Never accept approval, roots or environments from model JSON.

### Report and events

`DevelopmentReport` contains:

| Field | Meaning |
| --- | --- |
| `passed` | Both observed build and test returned real exit code 0 with successful process infrastructure. |
| `reason` | Precise stop reason, such as `verification_passed`, `baseline_passed`, `environment_unavailable`, `invalid_patch`, `duplicate_patch`, `repeated_source_state`, `patch_not_applied`, or a budget limit. |
| `discovery` | Observed workspace, project type, fixed commands, markers, missing tools and readiness. |
| `commands` | Actual fixed command strings, nullable exit codes, stdout/stderr, process-infrastructure status and wall-clock timestamps. A null exit code cannot pass. |
| `files` | Distinct paths with known committed edits. Prepared/uncertain entries remain separately in checkpoints. |
| `diff` | Bounded observed before/after text, with original/current hashes. It is a whole-file comparison, not a git patch intended for application. |
| `iterations` | Baseline number 0 plus repair iterations with commands, files, checkpoint ID and verification reason. |
| `inferenceCalls`, `elapsedMillis` | Actual call count and monotonic elapsed time. |
| `checkpoints` | Original snapshots, remote backup paths, proposed after hashes and `committed` flags. |
| `evidence` | Original bounded `ToolExecutionEvidence` from terminal operations, separate from model prose and phase summaries. |
| `modelResponses` | Model output retained separately; an oversized response is truncated to its configured cap and rejected. |
| `humanReviewRequired` | True for incomplete/uncertain edits, failed post-edit verification, stale rollback or timeout involving edits. |

The phase callback receives `DevelopmentEvent(phase, iteration, message)`. Phases include `discover`, `index`, `build`, `test`, `diagnose`, `checkpoint`, `verify` and `complete`. Callback messages are summaries, never substituted for original command evidence. Persist the returned report/evidence in the host's existing task-history mechanism. Remote backup files persist independently under `.nyx-checkpoints`; the module does not choose an Android database or task-history schema.

`baseline_passed` means the project already passed its detected checks and **no repair was attempted**. It does not prove that a free-form feature request was implemented. This worker repairs failing projects; the host should not translate a passing baseline into a claim that a requested feature was delivered. Likewise, passing tests are not proof of complete semantic correctness, meaningful test coverage, or approval to publish.

## Testable environment boundary

`DevelopmentEnvironment` exposes only:

```kotlin
suspend fun discover(): DevelopmentDiscovery
suspend fun index(maxFiles: Int, maxFileBytes: Int): List<SourceSnapshot>
suspend fun run(kind: ProjectRunKind): DevelopmentCommand
suspend fun apply(patch: DevelopmentPatch): PatchApplication
suspend fun rollback(checkpoint: DevelopmentCheckpoint): PatchApplication
suspend fun diff(checkpoints: List<DevelopmentCheckpoint>, maxChars: Int): String
fun drainEvidence(): List<ToolExecutionEvidence>
```

`ProjectRunKind` has only `BUILD` and `TEST`. `TerminalDevelopmentEnvironment(TerminalAgentTools)` delegates to safe terminal hooks. Tests can substitute an in-memory environment and the existing provider interface. `TerminalToolExecutor` is a test seam for actual command execution; production construction should use `TerminalBridge`, not expose this seam as a model tool.

## Typed patch protocol

The model receives no shell tools. Its entire completed response must be strict JSON:

```json
{
  "files": [
    {
      "path": "src/answer.py",
      "expectedSha256": "observed-lowercase-64-character-sha256",
      "content": "complete replacement text\n"
    }
  ]
}
```

The hash in this illustrative JSON must be replaced by a real 64-character lowercase hash. `MISSING` is the only alternate hash value and permits creating a new file only when the target truly does not exist and its parent already exists. There is no delete, rename, shell, commit, dependency-install or publication action. Unknown keys, duplicate JSON keys, duplicate paths, trailing data, unquoted JSON keys, markdown fences, tool calls, incomplete replies, malformed Unicode, binary/NUL content and budget violations are rejected before applying a patch.

Paths must be relative and normalized. Traversal, absolute paths, `~`, empty/dot components, `.git`/other VCS metadata, secret/credential paths, `.env` variants, SSH/cloud key folders, `local.properties`, keystores, dependency trees, generated build directories and `.nyx-checkpoints` are denied. File contents and logs are explicitly described to the model as untrusted data. Only file/path/hash/content fields can affect edits; model text is never interpreted as a shell command.

Before mutation, every original is read with consistency checks and matched against its expected hash. Every original is backed up and its backup hash verified before the first replacement. The target hash is checked again immediately before the controlled rename. Replacement bytes are written to a restrictive same-directory `mktemp` file using base64 payloads no longer than 4,000 characters per IPC command, hashed, then moved onto the target. Existing file permission bits are preserved; new files are created with restrictive permissions. No whole large file is put in one IPC argument. Existing remote reads use bounded 4,096-byte chunks rather than unbounded `cat` before IPC. UTF-8 and read-before/read-after hashes are verified.

Each file replacement is atomic; an entire multi-file patch is **not** a filesystem transaction. A partial failure stops the loop and returns committed/uncertain checkpoint entries and human-review status. Prepared temp files may remain after cancellation/failure for manual inspection; there is no automatic deletion sweep.

## Terminal capabilities and discovery

`terminal_run` always returns denied, even for apparently harmless shell strings. `build_run` and `test_run` reject command/timeout overrides. `project_run` accepts only `kind: "build"` or `"test"`. Read-only hooks are `project_inspect`/`project_detect`, `file_list`, `file_read`, `source_search`/`file_search` and `repo_diff`. `file_write` requires an expected hash and goes through the same checkpointed write path. `repo_diff` compares NYX checkpoints only; it never executes git, commits, pushes or publishes.

Before each project invocation, discovery rechecks remote tool availability. Fixed recipes are:

| Project | Build | Test | Important prerequisites |
| --- | --- | --- | --- |
| JVM Gradle | `./gradlew --offline --no-daemon assemble` or discovered absolute `gradle` | same runner with `test` | `java`, `javac`, executable wrapper and wrapper JAR, or installed Gradle |
| Android Gradle | same runner with `assembleDebug` | same runner with `test` | JVM prerequisites plus SDK environment with `platforms` and `build-tools` directories |
| Node | discovered absolute `npm --offline run build` | `npm --offline test` | `node`, `npm` |
| Python | discovered absolute `python3 -m compileall -q .` | `python3 -m pytest -q` | Python and an isolated read-only pytest-availability probe |
| Rust | discovered absolute `cargo build --offline` | `cargo test --offline` | `cargo`, `rustc` |
| Go | `GOPROXY=off GOSUMDB=off` + discovered `go build ./...` | same environment with `go test ./...` | `go` |
| Make | discovered absolute `make` | same runner with `test` | `make`, `cc` |

Canonicalization/chunk-write utilities and `timeout` must also be available. Unsupported/unknown projects, missing compilers/JVM/SDK utilities, unavailable IPC and absent pytest stop before inference or build attempts. No toolchain installation, privilege escalation, distribution setup, fallback shell search or repeated installation loop is performed. Infrastructure failures with no recognized process result stop rather than being diagnosed as source bugs.

Android detection inspects bounded conventional root/app Gradle files for `com.android.`. Alias-only plugins, custom layouts, SDK configured solely in `local.properties`, special Gradle task names, additional compilers/libraries, incompatible tool versions and missing offline caches may need explicit host configuration or future fixed recipes. Dependency/cache failures reported by a real build can still be indistinguishable from source failures; iterations remain bounded and no installer is run.

## Limits and cancellation

Defaults are three repair iterations, four inference calls, five minutes elapsed, four files per patch, 12 total file replacements, 64,000 UTF-8 bytes per file, 256,000 total replacement bytes, 64 indexed files, 48,000 context characters, 300,000 response characters and 32,000 diff characters. Repeated file edits count again toward the edit budget. Configurable ceilings include at most six inference calls and ten minutes elapsed. Invalid limits throw before work starts. Duplicate proposed file-content patches, repeated indexed source states and no-op patches stop without another edit.

The coroutine elapsed deadline covers discovery, indexing, model inference, edits and verification. Actual terminal project commands request a hard `timeout`, capped at ten minutes per invocation; the existing bridge controls dispatch/result behavior. External `CancellationException` is always rethrown. Only this loop's own elapsed timeout becomes an explicit failed report. As documented by `TerminalBridge`, cancelling a wait cannot guarantee that Termux's underlying child has stopped; timeout/cancellation near a rename can leave an uncertain checkpoint. Do not start another worker or retry automatically until the host has accounted for that uncertainty.

## Rollback and security boundary

Rollback is explicit: `TerminalDevelopmentEnvironment.rollback(checkpoint)` or `tools.rollbackCheckpoint(checkpoint)`. It accepts only a checkpoint known to the current tool instance, rejects uncertain checkpoints and new-file deletion, preflights every current file against its recorded after hash, then checks again before each atomic restoration. Concurrent human edits cause a refusal and human-review status. There is no automatic rollback, arbitrary git commit, history rewrite, push or publishing. After process restart, persisted backup paths support human review; this version does not deserialize/re-authorize old checkpoints for automatic restoration.

Confinement uses **remote** `realpath -e` checks, verifies that the approved root is its own canonical path, verifies canonical parents and existing file paths, and rejects symlink roots/files/parents escaping or aliasing the workspace. Lexical path validation and shell quoting supplement those checks; command-filter regexes are not the security boundary. Fully wrapped command argv is bounded to the terminal protocol's 8,192-character limit and encoded write chunks are at most 4,000 characters. Extremely long roots/file paths can therefore be refused rather than falling back to an oversized IPC payload.

**Trusted project approval is essential.** Gradle wrappers/build scripts, npm scripts, Python imports/tests, Cargo build scripts, Go tooling and Makefiles can execute arbitrary code with Termux/PRoot user permissions, read outside the workspace or access the network. Offline flags are best effort and do not prevent custom scripts or Gradle wrapper downloads. These tools are not an OS sandbox or network firewall. Kernel-level `openat`/`O_NOFOLLOW` confinement is not available through shell IPC, so malicious concurrent directory renames can race realpath/hash/rename checks. Use only a trusted, non-adversarial approved project; do not run untrusted repository scripts automatically. No raw user or model shell command is silently marked approved by this provider.

## Verification performed

Focused direct Kotlin/JVM compilation and JUnit execution were used without a Gradle run. The test suite covers strict JSON and Unicode, budgets, traversal/secret paths, missing Java, repeated repairs, duplicate patches, infrastructure errors, evidence separation, elapsed timeout, cancellation propagation, disabled shell/command overrides, explicit workspace opt-in, remote symlink confinement, bounded chunk writes, actual backups and stale-safe rollback.

The optional Linux host fixture creates a trusted temporary Makefile and Python source. Baseline build returns 0, baseline test returns 2; a deterministic fake provider supplies one exact-hash patch; the real repeated build/test return 0 and 0. The report contains these actual command exits, the original backup, the observed `41 -> 42` diff and `verification_passed`. It needs no paid LLM, Gradle or Android device. Host fixture availability is assumption-gated for platforms lacking Bash/Make/CC.

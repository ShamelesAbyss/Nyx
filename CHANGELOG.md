# Changelog

## 0.9.0 — Project Bedrock (published)
Bedrock adds a default-off, original-project-bound development → selected-file flow through the existing engine, a structured terminal catalog with controlled environment/link guards, exact-request capability evidence and checkpoint-stable continuation IDs, mandatory process/artifact checks, conservative persistent-host token fencing, and stricter store/network/credential boundaries. The final clean build passed **1,182 JVM tests across 132 suites**, zero failures/errors/skips; terminal and production non-UI combined host acceptance also passed. Android/Room/Binder/provider and real-device execution remain unverified. See [release notes](RELEASE_NOTES_v0.9.0.md), [implementation/evidence report](V090_IMPLEMENTATION_REPORT.md) and [security dispositions](BEDROCK_SECURITY_AUDIT.md).


VersionCode 23 / versionName 0.9.0, package com.nyx.ai. Adds one prepared, full-SHA-pinned GitHub Actions verification job, a checked-in build/packaging driver and independent artifact gates for wrapper integrity, literal identity/SDK, debug-signer continuity, ARM64 JNI packaging, every native ELF/ZIP alignment, shipped notices, exact current-source archive bytes and checksum coverage. Build-side regressions use explicitly synthetic APK/ELF/parser fixtures; no real-model or Android execution is inferred from them. Root NYX.apk and NYX-Source.zip were obsolete v0.2.0 bundles and are retired from this working tree, with Git history and historical distribution preserved. Engineering/funding/build guidance now separates the candidate from public v0.8.9. Apache-2.0, the existing toolchain and debug certificate remain unchanged. The clean build and independent APK/source/checksum gates passed. Hosted CI, physical-device acceptance and real-provider qualification remain unverified. Published as v0.9.0; no AAB or Play publication.

## 0.8.9 — Project Workbench (published)

VersionCode 22 / versionName 0.8.9. Adds an app-private objective artifact store with atomic manifests, shared storage quotas, safe paths, verified checksums and conservative recovery. URL-driven research retains readable sources/provenance under existing network policy; selected TXT/MD/CSV/JSON documents import through the Android picker. Existing objective tools create and validate Markdown/text/CSV/JSON/static HTML/ZIP files; completion requires actual artifacts and successful-task digest witnesses. Research-backed requests also require retained source evidence. Workbench provides reopening, inert previews, SAF saving and narrow artifact-only FileProvider sharing. No Room migration, new broad filesystem/network authority, PDF or browser automation. Published on GitHub as v0.8.9; no AAB or Play publication.

## 0.8.8 — Project Insight (published)

versionCode 21 / versionName 0.8.8, com.nyx.ai. Adds a bounded capability profile per provider/model/context/thread identity (24-hour cache, identity invalidation), a deterministic direct start for short text-only objectives, incremental planning with at most two initial validated steps and one continuation after verified work (six continuations maximum), and guided one-line decisions limited to inference. Ollama `thinking` and OpenAI-compatible `reasoning_content` deltas are counted as activity, never as answer or plan text; structured planning disables thinking only after a reasoning stream was observed for that model. No new permissions or tool authority. Physical-device verification of strategy selection and reasoning behavior remains pending.

## 0.8.7 — Project Breakthrough (published)

versionCode 20 / versionName 0.8.7, com.nyx.ai. Repairs root/descendant IME inset ownership and bounded draft editing; adds generous selected execution patience with request/tool ceilings and conservative cumulative segment accounting; reduces capability-sensitive planning workloads; adds durable isolated terminal-operation admission, per-task evidence/provider forks, dependency launch reservations and workspace conflict locks. Terminal/Mission Control expose per-operation state/details and guarded uncertainty review. Existing v0.8.6 Kotlin development, permissions, resource/recovery safeguards and signing identity are retained. 909 JVM tests in92 classes pass; clean ARM64 build succeeds. Device keyboard/qwen fixes remain unconfirmed. No AAB or Play publication. See [release notes](RELEASE_NOTES_v0.8.7.md) and [report](V087_IMPLEMENTATION_REPORT.md).

## 0.8.6 — Project Ignition (unpublished candidate)

Package com.nyx.ai, versionCode 19 / versionName 0.8.6. Adds compact validated small-model plans, request-specific output bounds, strict single-object extraction and one repair, stream-aware stall/first-text/hard deadlines, optional provider telemetry, manual model diagnostics, standalone Kotlin/Java/shell development and task-local dependency preparation. Empty authorized workspaces can create projects and must pass real discovery/build/behavioral tests; native-function fallback respects model-call budgets. Existing permissions, checkpoints, Room data, model storage, PRoot ownership and Endurance guards are preserved. No llama.cpp changes. Clean build: 833 JVM tests across 82 classes, zero failures/errors/skips. No physical-device or real-model validation; no commit/push/tag/release. See [release notes](RELEASE_NOTES_v0.8.6.md) and [implementation report](V086_IMPLEMENTATION_REPORT.md).

## 0.8.5 — Project Sovereign (published)

Project Sovereign makes the autonomous execution path more durable and more conservative about what it claims. It adds adaptive reasoning-depth and model/tool/step budgets derived from task kind, complexity, and the selected performance mode; a bounded durable working state that survives context compaction and process restarts; task-scoped tool authority for inference, filesystem, verification, terminal, and development work; a false-success guard for tasks that require tool evidence; bounded structured-action repair; repeated-failure and consecutive-failure stops; and a two-hour checkpointed objective watchdog.

The planner now sees the tools actually enabled for the run instead of guessing, and retries receive the previous failure evidence with an explicit instruction to change approach. Existing workspace authorization, command policy, dependency gating, provider snapshots, checkpoints, recovery, and no-root/no-arbitrary-shell boundaries are unchanged. No llama.cpp source was modified.

Published as [GitHub release v0.8.5](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.5). Package metadata is versionCode 18/versionName 0.8.5 for `com.nyx.ai`; versionCode 18 is used because the previous release already consumed code 17, preserving the in-place upgrade path with the established debug certificate. The clean build passed **731 JVM tests** (70 classes, zero failures/errors/skips), and the ARM64 debug and instrumentation APKs assembled. The APK SHA-256 is `024c5fa4ab91a7d28b19f8fe22c362766109470e72a615a82f81501a1d8aa456`. Native GGUF inference, the Sovereign loop against a real provider/model, and all runtime behavior remain physical-device validation items. See `RELEASE_NOTES_v0.8.5.md` and `V085_IMPLEMENTATION_REPORT.md`.

## 0.8.4 — Project Endurance (published)

Project Endurance adds hardware-aware operating modes and a Performance dashboard, adaptive native threading applied only at safe boundaries, opt-in bounded native calibration, exact-token-prefix KV reuse in the native session, mode-aware autonomy concurrency and stream coalescing, and Eco-only idle model release. The existing autonomy policy, workspace authorization, dependency gate, provider snapshots, checkpoints, recovery, and no-root/no-arbitrary-shell boundaries are unchanged.

Published as [GitHub release v0.8.4](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.4). Package metadata is versionCode 17/versionName 0.8.4 for `com.nyx.ai`; the established debug signer is retained. The clean build passed **713 JVM tests** (68 classes, zero failures/errors/skips), and the ARM64 debug and instrumentation APKs assembled. The APK SHA-256 is `65a415a2e57d72575fd858d428e997db7b6377e10a3c8e9b701102285e025dce`. The official release includes the APK, compiled instrumentation APK, clean source archive, and checksum manifest. The owner reports that the Performance dashboard rendered on a Pixel 10 Pro Fold; this is UI evidence only. Native GGUF inference, calibration, exact-prefix KV reuse, and battery/performance gains remain device-validation items. See `RELEASE_NOTES_v0.8.4.md` and `V084_IMPLEMENTATION_REPORT.md`.

## 0.8.3 — Project Mission Control (published)

The Autonomy screen now summarizes the actual selected/active objective, execution phase, persisted task completion count, active task, and existing objective controls. Its readiness rows are sourced from real inference, environment, workspace, and objective state; an absent probe or model is not presented as ready. Task timeline cards expand to show persisted instructions, prerequisites, verification criteria, outcomes, errors, and bounded evidence. Historical objective selection loads that objective's own task graph.

Terminal target selection now clearly distinguishes Termux Host from Debian PRoot and groups environment detection/capability evidence, workspace authorization, and manually approved commands. The existing terminal permission and exact-command approval paths are unchanged. Live Worker Console status typography and copy/share touch targets are larger while run history, redacted diagnostics, and lifecycle actions remain.

Package metadata is versionCode 16/versionName 0.8.3 for `com.nyx.ai`; the established debug signer is retained. The official GitHub release includes the debug-signed ARM64 APK, compiled instrumentation APK, source archive, and checksum manifest. JVM/build checks passed; the instrumentation APK was not executed, and this release’s UI and Termux/PRoot runtime have not been physically validated. See `RELEASE_NOTES_v0.8.3.md` and `V083_IMPLEMENTATION_REPORT.md`.

## 0.8.2 — Project Unshackle (published)

Package metadata: versionCode 15 / versionName 0.8.2, min API 26, compile/target API 37, ARM64 JNI. This release retains application ID `com.nyx.ai` and the established debug signing certificate. It is published on GitHub with the APK, instrumentation APK, source archive, and checksum manifest. Physical-device validation remains pending.

### Task-scoped dependency preparation

Dependency bootstrap no longer runs before the planner has produced tasks. The persisted task graph now passes through one pre-execution gate on both first execution and resume. Only terminal/development tasks with affirmative project/toolchain requirements select a dependency plan; shell automation, inference-only work, verification-only work, and negated toolchain instructions proceed without unrelated Android SDK, Gradle, JDK, or package checks. Guest JDK resolution runs only for project kinds that require Java, and Git is optional unless a task explicitly invokes repository operations.

Android application/APK development tasks continue to select the Android toolchain. Existing Termux/PRoot selection, environment and workspace validation, installation authorization, trusted package planning, post-install verification, task checkpoints, `needs_attention` handling, and resume semantics are retained. The task graph is saved before preparation; an approval block or failed dependency check keeps that graph available for review and retry.

### Inference and source presentation

Project-kind inference uses bounded, negation-aware keyword matching, so instructions such as “do not use Android SDK or Gradle; use shell only” do not falsely require those toolchains. Affirmative Android, Kotlin/JVM, C/C++, Node, and Python project terms remain recognized. `.gitattributes` marks only the bundled llama.cpp source tree as vendored for GitHub presentation; the vendored source and licenses are unchanged.

See `RELEASE_NOTES_v0.8.2.md` and `V082_IMPLEMENTATION_REPORT.md` for test evidence and open device checks.

## 0.8.1 — Project Clear Signal (published)

Package metadata: versionCode 14 / versionName 0.8.1, min API 26, compile/target API 37, ARM64 JNI. Debug-signed with the established certificate. Published as GitHub release v0.8.1 with the APK, instrumentation APK, source archive, and checksum manifest. See `RELEASE_NOTES_v0.8.1.md`.

### Run-scoped Live Worker Console

The console now defaults to the current objective and its current execution run instead of merging every activity in the bounded store. Each engine and service event carries a run identity, and the dialog provides an explicit history selector for earlier runs and objectives. A legacy event without a run identity remains available as a separate legacy run. Selecting a run resets the detail view but preserves the run-scoped event set; a new event only auto-scrolls when the user is already near the bottom.

### Operations, duration, and raw diagnostics

Related task, tool, verification, planning, and recovery events are grouped into operation cards. A card shows the observed status, event count, and elapsed time calculated from real event timestamps. Tapping a card opens a full-height chronological breakdown with command input, output, and status; an optional raw-event mode exposes the complete event stream. Terminal ANSI/OSC sequences are stripped before display and export, and credential-shaped values remain redacted. A confirmation-gated Clear History action preserves the selected/current and active runs and never deletes Room objective records or task evidence.

### Bounded planning and recovery

Planning has a hard 120-second per-attempt deadline and emits progress events while waiting. A completed response that fails strict JSON or schema validation gets exactly one structured-output repair with the validator reason. Transport timeouts, interruption, empty responses, context exhaustion, and provider errors are classified separately; a failed planning phase records a sanitized diagnostic and leaves the objective in `needs_attention` instead of silently restarting. Existing tasks, checkpoints, and dependency evidence are preserved, and resuming still requires explicit review.

### Hardened model-directed shell policy

`ModelShellSyntax` rejects output/input redirection, pipes, command chaining, newlines, command substitution, variable expansion, and indirect execution (`sh -c`, `eval`, `xargs`, `find -exec`, and similar) before command classification. Quoted metacharacters remain ordinary arguments, and fixed build/test commands and approved dependency installation are unchanged. The terminal trust boundary remains the existing policy gate, not an OS sandbox.

### Honest limits

No physical Android device was available for this build. Actual console rendering and scrolling, history selection, clear-history behavior, provider planning timeout/repair, and real-device shell behavior remain physical-device checks. The planning deadline is a client-side bound; provider cancellation behavior depends on the provider. No AAB or Google Play publication was performed, and the APK remains a debug-signed development build. 695 JVM tests pass with zero failures, errors, or skips.

## 0.8.0 — Project Autonomous Bootstrap (published)

Package metadata: versionCode 13 / versionName 0.8.0, min API 26, compile/target API 37, ARM64 JNI. Published as GitHub release v0.8.0 with the APK, instrumentation APK, source archive, and checksum manifest. See `RELEASE_NOTES_v0.8.0.md`.

### Verified environment before objective execution

The objective foreground service now re-discovers Termux/PRoot state on every start and reconciles it with the objective's stored environment snapshot. A current verified guest clears a stale historical error; an unavailable or diverted environment records a current diagnostic and stops before any task runs. `DependencyBootstrap` probes the guest, resolves the Debian JDK candidate from APT evidence, builds the existing trusted plan, installs only approved candidates, re-probes every required executable, and then continues the original objective with its task graph and checkpoints unchanged.

`GuestJdkResolver` now recognizes both traditional `*.list` repositories and Debian Deb822 `*.sources` stanzas. A Deb822 stanza counts only when `Types` contains `deb`, `Enabled` is not `no`, and `URIs` and `Suites` are present. This fixes the Debian 13 Trixie false `APT sources: 0` diagnostic while still requiring a real `apt-cache policy` candidate before selecting a JDK.

### Full Autonomy scope and audit

Installation is authorized through the existing `INSTALL_PACKAGE` policy and workspace-scoped routine grants. Full Autonomy can therefore reuse a previously granted scoped installation without a redundant prompt; Manual and Smart retain their approval requirements. Each step is recorded as a dependency event and through the existing terminal command history, including stdout, stderr, and exit code.

### Policy-gated model commands

`terminal_run` is no longer unconditionally denied. A model command must pass `ModelCommandPolicy`: the working directory must be the approved workspace or a safe subdirectory, the command must be non-empty and bounded, credential/private-data paths are denied, and destructive commands require explicit human handling. `AutonomyModelCommandGate` then applies `RUN_READONLY_COMMAND` or `RUN_MUTATING_COMMAND` through the shared policy. The command still runs in the real Termux/PRoot environment; this is a policy boundary, not a security sandbox.

### Expandable Live Worker Console

The existing persisted `WorkerActivityBus` now carries bounded tool input and output alongside timestamped planning, task, tool, terminal, installation, file, verification, recovery, and failure events. The console is available from Chat and from a visible Live Activity card in Autonomy, shows the active objective, phase, active task, and status, and opens a full-height virtualized timeline. Each event can expand to readable input/output detail. Pause, Resume, and Cancel call the existing objective service controls. Copy and share use a bounded, redacted plain-text export; no hidden reasoning or credentials are added, and event history remains in the existing on-device JSONL store across navigation and process recreation.

### Workspace reuse and guarded cleanup

The saved workspace is re-probed on startup and reused when it matches the verified canonical path; NYX no longer silently needs a new numbered folder. Workspace selection, the persisted managed-workspace registry, routine policy grants, and the objective execution snapshot are synchronized, and terminal-enabled objectives refuse to start without a verified workspace. A Manage Workspaces panel in Terminal lists verified direct children of the managed root, allows selecting one, and provides Delete and Delete all test workspaces with an exact path-list confirmation. Deletion is refused for active objectives, the managed root, nested or traversal paths, symlinks, non-canonical paths, and paths outside the managed root. The parent root, Ollama models, Debian installation, and unrelated files are never targeted.

### Honest limits

No physical Android device was available for this build. Termux `RUN_COMMAND`, Android foreground-service behavior, guest APT installation, real working-directory commands, live console rendering, workspace deletion, and actual model execution remain device checks. Approval-blocked or failed dependency plans stop with a visible `needs_attention` diagnostic rather than retrying an uncertain install loop. No AAB or Google Play publication was performed; the APK remains a debug-signed development build.

## 0.7.9 — Project Persistence (published)

Package metadata: versionCode 12 / versionName 0.7.9, min API 26, compile/target API 37, ARM64 JNI. Debug-signed with the existing certificate. This is a focused runtime fix, not a feature expansion.

### Durable PRoot session for guest Ollama

`OllamaHostService` is a non-exported `dataSync` foreground service that owns guest start, stop, and restart while the NYX activity is backgrounded. It declares `FOREGROUND_SERVICE_DATA_SYNC`, keeps a user-visible notification while the managed server is active, reports Starting/Ready · Running/Recovering/Stopped/Error, and exposes View Activity, Stop Worker, and (when installed) Open Termux actions. It polls the verified manager every 20 seconds and stops supervising when the server is gone or adopted. Host-mode lifecycle remains direct and unchanged.

`OllamaGuestRuntime` probes the installed `proot-distro` for `login --detach`, `ps`, and `kill` together. When all three are present, NYX starts one detached `proot-distro login --detach` session and records its session PID plus kernel start-time token. On older releases it keeps one foreground PRoot login alive instead of pretending to detach. The server itself always runs in the foreground of that session with `exec ollama serve`, so PRoot's `--kill-on-exit` cannot silently tear it down.

### Verified ownership and cleanup

Stop sends the existing verified single-PID TERM to the guest Ollama process, then calls `proot-distro kill` for the recorded session and re-inspects `/proc/<pid>` before reporting a clean stop. A session that survives is left visible with a retryable error instead of a false STOPPED state. Refresh re-reads the session record, verifies its start token, adopts a live process, and never leaves a stale RUNNING card. An orphaned detached session exposes Stop even when the guest PID record is missing.

### Startup hardening

Startup distinguishes a dead process, a live guest API that Android cannot reach, a live process that has not opened its API, a port conflict, and READY. Duplicate starts are refused while a verified detached session is alive; the start script also refuses a second live NYX-owned PID. The existing port-conflict path still leaves the process running for inspection. Chat now starts a stopped loopback runtime and refreshes its model inventory before generating; objective creation/resume uses the same service-owned guest path and rediscovers the selected target afterward.

### Honest limits

Physical-device validation of `proot-distro ps` output, Android 14+ `dataSync` behavior, Termux `RUN_COMMAND` lifetime, and guest API reachability is still required. The foreground fallback cannot survive Android killing the NYX process; in that case the guest PID record may remain until the next verified stop or refresh. A user-started server that NYX did not launch is still never killed.

## 0.7.8 — Device Hotfix (unpublished)

Package metadata: versionCode 11 / versionName 0.7.8, min API 26, compile/target API 37, ARM64 JNI. Debug-signed with the existing certificate. No feature expansion.

### Shared verified PRoot executable

`ProotExecutableStore` now carries the host-verified `proot-distro` path across all `TerminalBridge` instances and app restarts. `NyxViewModel` shares one bridge between `TerminalRepository` and `OllamaServerManager`; discovery stores the path and a manager created earlier or later resolves it at command time. This fixes the Debian-only Ollama failure where the manager's bridge returned `proot-distro path probe did not find an available executable`.

### Real Debian JDK candidate resolution

`GuestJdkResolver` reads the guest's `/etc/os-release`, architecture, APT source count, and `apt-cache policy` candidates. Debian Trixie prefers `openjdk-21-jdk-headless` or `default-jdk-headless`; Bookworm prefers `openjdk-17-jdk-headless`. `DependencyPlanner` no longer hardcodes a guest JDK: unresolved candidates produce a diagnostic blocker instead of a blind install. Gradle is still checked separately, and installation success still requires `java`/`javac` to execute as verified guest-native tools.

## 0.7.7 — Project Liberation (published)

Package metadata: versionCode 10 / versionName 0.7.7, min API 26, compile/target API 37, ARM64 JNI. Debug-signed with the same certificate as v0.7.5; published as v0.7.7 with all four assets.

### Debian-aware managed Ollama

`OllamaServerManager` now probes the isolated Termux host and every PRoot guest that environment discovery actually verified. Detection records the executable path, origin, version, architecture, and compatibility; `/usr/local/bin/ollama` is recognized as a Debian-native installation, and warning-prefixed `ollama --version` output is parsed as installed-but-stopped rather than missing. Compatible host/guest installations are selected deterministically, with the current environment preferred. Lifecycle commands execute in the selected filesystem/process namespace, PID records are scoped per target, and `setsid` startup has a `nohup` fallback for PRoot guests. A live-but-unreachable owned server is reported distinctly; adopted or unverified processes remain unkillable. Detection and refresh automatically reload the model inventory and reconnect Chat when the server is ready.

### Full-Autonomy workspace authorization

Project creation is now a distinct `CREATE_WORKSPACE` routine action against the approved root, not an elevated write to the first project. Full mode can create multiple independent projects under the same authorized root; Manual still asks, Smart follows its routine/high-risk split, and the UI presents an explicit approval card instead of `full_elevated_grant_required`. The provisioner remains atomic, collision-safe, and never reuses an existing directory.

### Automatic objective reconnect

Before planning or resuming an objective against a loopback Ollama endpoint, NYX starts the managed server if needed, waits for readiness, refreshes the model list, and continues only after Chat reconnects. This uses the same managed lifecycle and ownership checks as the Models screen.

### Reliability and UI

The global error banner is limited to screens where the error is actionable and is cleared when navigating to unrelated sections. The managed Ollama card shows the selected host/guest, version, architecture, probed installations, and the full status set. Added JVM coverage for Debian-only selection, preferred guest selection, incompatible binaries, portable startup, detection markers, and repeated Full workspace creation.

### Verification and limits

609 JVM tests pass with zero failures/errors/skips. The debug APK and instrumentation-test APK compile. No physical Android device was available: real Debian `ollama serve` startup, PRoot IPC, Keystore, notifications, foreground-service restart, and actual model inference are not claimed as device-verified. The v0.7.7 APK, instrumentation APK, source archive, and checksum manifest are published with the v0.7.7 GitHub release.

## 0.7.5 — Project Continuum (published)

Package metadata: versionCode 9 / versionName 0.7.5, min API 26, compile/target API 37, ARM64 JNI. Debug-signed and published as v0.7.5.

### Managed Ollama lifecycle

The Models screen now owns a Termux-hosted Ollama server without asking the user to run commands. Installation discovery, start, readiness polling, stop, restart, PID/start-token ownership verification, adopted-server protection, bounded logs, RSS/uptime telemetry, and auto-refresh are implemented in `OllamaServerManager` and `OllamaProcess`. NYX never signals an adopted or recycled PID, never uses `pkill`, and refuses lifecycle work while Emergency Stop is active. The existing endpoint remains user-configurable; NYX does not silently redirect prompts.

### Arsenal — operational skills

Nine versioned built-in procedures cover coding, Git operations, project inspection, research, documentation, testing, debugging, dependency management, and artifact generation. Only the three most relevant procedures enter a task prompt; their declared tools are added to that task's allowlist. Imported JSON manifests are treated as untrusted, size-bounded, rejected for unknown/escalating tools, duplicate or reserved IDs, unsafe constraints, and incompatible schemas. Skills can be inspected, enabled/disabled, configured, removed, and audited with evidence-linked execution logs from the new Arsenal screen.

### Gateways — MCP and GitHub

Remote streamable-HTTP MCP servers and the GitHub API are available through a real gateway repository with per-server allowlists, per-run call budgets, response/timeout caps, redacted audit logs, health checks, and tool listing. Tokens are encrypted only in the Android Keystore-backed credential vault; gateway JSON stores an opaque reference, never the token. Objective execution snapshots the allowed gateway tools before a run, so a model cannot enable a new server or widen its own allowlist. The Gateways screen supports add/edit, enable/disable, health checks, tool inspection, and explicit JSON invocation; GitHub writes remain distinct tools and are still policy-gated.

### Durable project memory and symbol index

Project memory records verified outcomes, build instructions, lessons, and outcomes with content hashes and evidence references, suppresses stale/unverified claims, deduplicates near-identical entries, and builds a bounded relevant-memory prompt block. Objective tasks now receive relevant memory and persist evidence-linked verified outcomes. The source symbol index incrementally parses Kotlin/Java declarations and references with SHA-256 change detection and deterministic relevance ranking.

### Live Worker Console and workspace safety

The chat screen shows a compact live objective preview; the full console streams persisted plan, delegation, tool, skill, gateway, verification, recovery, metric, and error events. Workspace provisioning now uses atomic, collision-safe creation and never reuses an existing directory; terminal history maps cancellation to `uncertain` rather than inventing success. Routine autonomy grants are workspace-only, removed when returning to Manual, and synchronized against concurrent stop/revocation. Package authorization is scoped under the selected workspace.

### Verification and limits

598 JVM tests pass with zero failures/errors/skips. The debug APK and instrumentation-test APK compile. No physical Android device was available: Termux IPC, real Ollama process control, Keystore key use, MCP/GitHub network calls, notifications, and foreground-service execution are unit-tested/compiled but not device-executed. The symbol index is not yet automatically fed into every task prompt. Gateway tool names are namespaced per server and snapshotted; dynamic MCP tool changes require a new objective.

## 0.7.0 — Project Ascension (published)

Package metadata: versionCode 8 / versionName 0.7.0, min API 26, compile/target API 37, ARM64 JNI. Debug-signed and published as v0.7.0.

### Central autonomy policy

A single policy engine now governs terminal commands, workspace changes, package installs, network access, objectives, and delegated workers. Manual asks before consequential actions; Smart runs routine work inside explicit grants and asks for higher-risk work; Full runs routine and elevated work but still stops for publishing, APK installation, private-data transfers, system changes, and credential disclosure. Emergency Stop denies every new request, grants are persisted and revocable, and every decision is written to a bounded audit journal. Full mode never grants root or bypasses Android permissions.

### Environment intelligence and automatic provisioning

Capability probes now record executable origin, not just a path. A Termux binary found inside a Debian guest is labelled inherited and treated as unusable; `apt` and `dpkg` are probed separately from `pkg`. A deterministic dependency planner derives the required toolchain from the project kind, proposes only official-repository packages, refuses Android SDK installs inside an ARM64 guest, and blocks when no trusted source exists. The installer authorizes the whole plan before running anything, retries once on a lock or temporary network failure, then re-probes and reports tools that are still unverified. Workspaces can be created and write-verified without the user running `mkdir`, with collision-safe names and quoted paths.

### Controlled network access

`fetch_url` is available to objectives through a policy-gated provider. Public requests require HTTPS, credentials in URLs are refused, private/link-local/CGNAT addresses and loopback pivots are blocked, redirects are re-validated, DNS answers are checked, text bodies are size-capped, and fetched content enters a prompt only inside an explicit untrusted-content block. Binary content is rejected. The download implementation uses a temporary file, a hard byte cap, fsync, atomic rename, and SHA-256; the agent tool surface intentionally exposes text fetch only, not arbitrary file drops.

### Delegation and supervision

Objective tasks are assigned deterministic specialist roles (planner, research, coder, environment, tester, verifier, documenter) with role-specific instructions, and a supervisor policy decides continue, replan, pause, or fail from task state, repeated-failure signatures, and remaining budget. A composite tool provider merges terminal and network tools and rejects duplicate names. This is role-based delegation over the existing bounded workers, not one model process per role.

### Reliability

Source batch reads are limited to four files and a 96 KB payload per Termux IPC call, with individual fallback for omitted files, avoiding oversized Binder callbacks. The service refuses to start while Emergency Stop is active. Crash recovery and the existing durable objective leases remain in force.

### Verification and limits

The frozen source completed a clean debug build, 450 JVM tests with zero failures, and instrumentation-test APK compilation. No physical Android device was available, so Termux dispatch, PRoot guest execution, package installation, Keystore storage, notifications, and restart recovery are compiled and unit-tested but not device-verified. The installer applies a verified plan but does not resolve every distribution-specific package split. Full mode is powerful by design; grants default to the selected environment and workspace and should be revoked when work is finished.

## 0.6.1 — Environment truth and verified developer (published)

Package metadata: versionCode 7/versionName 0.6.1, min API 26, compile/target API 37, ARM64 JNI. This is a debug-signed development release, published as v0.6.1.

- Fixed false "Debian missing" and host/guest confusion. Environment discovery now launches `$PREFIX/bin/bash --noprofile --norc` with `BASH_ENV`/`ENV` cleared, so a Termux startup profile that ends in `proot-distro login` cannot divert the probe into a guest. A real host identity probe reports `nestedProot` with the remedy when diversion still occurs.
- Replaced the distro-list parse with a strict parser for `proot-distro list --quiet`, sectioned, and older annotated output. A non-zero exit, missing exit code, or nested-PRoot error is a failure, never an apparently empty "none installed" list.
- Added real guest verification: `/bin/sh` inside the selected distribution must print `NYX_GUEST_OK`, HOME, PWD, OS, uid, and resolved tools. A distribution is not marked available from its name alone. The foreground service refuses Debian objectives unless the selected guest passed verification.
- Added an explicit validated `distroAlias` to terminal requests and execution tools; Debian is no longer hardcoded in the command descriptor.
- Added a timestamped capability profile per environment (`VERIFIED`, `MISSING`, `INCOMPATIBLE`, `PERMISSION_DENIED`, `NOT_CHECKED`) with paths, bounded versions, free storage, and a workspace write check. Stale profiles are not presented as current proof.
- Added two-stage workspace validation (syntax then real existence/write probe), guest-path rules that reject Android `/storage` and `/sdcard` paths for Debian, and a verified path that is what gets saved and snapshotted.
- Added deterministic development preflight (provider, model, verified environment, authorized and accessible workspace, required tools, advisory memory) with stable blocker codes and actions; objective creation refuses to start until it is ready.
- Verified developer v2: deterministic goal classification, acceptance criteria, relevance-ranked context, batch source reads (12 files per IPC round trip with per-file fallback), original command evidence retained separately from model text, and typed outcomes. A healthy baseline yields `BASELINE_HEALTHY`, not a repair; a feature without changed test evidence yields `GOAL_NOT_VERIFIED`; only `REPAIR_VERIFIED` and `FEATURE_IMPLEMENTED_AND_VERIFIED` set `passed=true`.
- Added four required development fixture scenarios plus adversarial cases, and tightened two existing tests (baseline-healthy is not success; a listed Debian alias is not a verified guest). Full JVM suite: 350 tests, zero failures.
- Limits: no physical Android device run; Termux/PRoot behavior is verified by parsing and JVM tests only. "Test evidence" means a test file changed and the suite passed, not that the test is meaningful. No real-model autonomous coding run on a phone is claimed.

## 0.6.0 — Native CPU inference and verified project repair (local)

This entry describes local v0.6.0 source/build scope, not a published GitHub release. The latest published release remains v0.5.0. Package metadata is versionCode 6/versionName 0.6.0, min API 26, compile/target API 37, with ARM64-only JNI. The coordinating integrated Gradle run reported **BUILD SUCCESSFUL: 212 JVM tests, zero failures/errors/skips**, and assembled debug app/instrumentation-test APKs. Final clean source-rebuild/archive checks are separate; no Android-runtime or physical-device inference pass is claimed.

- Added genuine llama.cpp CPU inference through bundled `libnyx_llama.so`, with pinned vendored source/licenses, one shared model/context session, real token streaming, cooperative cancellation/join, safe reuse, and unload. ARM64 uses baseline NEON; actual Android Debug CMakeCache confirms `-O2 -g`. No weights, GPU/NPU backend, executable plugin downloader, or automatic model download is bundled.
- Added private SAF GGUF import/validation, persisted metadata, **Models → Select & load**, **Stop / unload**, and deletion of only the imported private copy. Native admission uses available system memory, KV/compute estimates, Android thresholds, and a configurable memory margin; context/thread settings are honored within supported bounds. Runtime status shows true last-generation tokens and last load/generation milliseconds, not a device-throughput guarantee.
- Unified chat, agents, workflows, and objectives behind strict provider selection and a global inference gate: one active request, up to 24 waiting requests, and no automatic native/server/provider fallback. Missing credentials/consent/models/permissions fail visibly. Ollama and `/v1` LAN endpoints, `.local` names, and the emulator host alias `10.0.2.2` are not described as on-device inference. Global streamed-chunk/character metrics remain separate from native tokenizer-derived counts.
- Added schema-validated strict JSON text actions for providers without function calling, including native GGUF. One invalid-response repair is allowed; a still-invalid action stops without execution. Model prose is never interpreted as raw shell, and tool names/arguments remain host-allowlisted.
- Wired engine development workers to `DevelopmentOrchestrator` with trusted workspace/environment and selected backend/model/options snapshots. **Autonomy → Terminal → Allow terminal tools for objectives → Save workspace**, then **Autonomy → Objectives → Use verified project repair loop → Plan and execute** creates a deterministic development task without model DAG classification. The checkbox requires an approved terminal workspace; environment/toolchain readiness is still checked before execution.
- Added actual detected fixed build/test baselines, fresh remote source snapshots, strict expected-SHA-256 complete-file edits, verified original backups/checkpoints, observed diffs, and retests within bounded budgets. Defaults are three repair iterations, four inference calls, and five minutes; UI ceilings are five iterations, six calls, ten minutes, and 12 file replacements. Baseline passing proves only the existing checks passed, not that a requested new feature was delivered. Failed development verification never completes the task.
- Tightened objective-only workspace tools with remote `realpath` confinement, bounded source reads/chunk writes, hash checks, and explicit trusted-project approval. Arbitrary model `terminal_run` and build/test command/timeout overrides are denied. Fixed `repo_status` accepts no command/options; `repo_diff` reports NYX checkpoint differences. Existing project build scripts are trusted executable code with Termux/PRoot authority, not an OS sandbox or network firewall. Hard child timeouts require coreutils `timeout`.
- Added five typed builtin skill manifests, validation, runtime task-tool subset restriction, and persisted `skill_scope` events. The Git builtin declares `repo_status`, `repo_diff`, and `file_read`, not raw `terminal_run`. No marketplace or executable skill/plugin system is included. Full-content source hashes, deterministic context freshness/scoped indexing, durable evidence, and compaction remain foundations; unused retrieval/compaction APIs are not claimed as an integrated full-context feature. Development independently refreshes terminal source snapshots rather than indexing Termux files with app-readable `File` APIs.
- Replaced the historical `dataSync` objective service type with honestly declared `specialUse` and a manifest subtype explanation. Play review is required and approval is not assured. Added an app-level 30-minute deadline, explicit coroutine pause/cancel, conservative `needs_review` persistence, and no automatic replay. Native cancellation joins active JNI work; a Termux child can linger until its timeout.
- Added explicit optional notification, Android 17 LAN, and Termux `RUN_COMMAND` permission buttons. Native inference requires neither Termux nor network permission. Selected PRoot availability/tools are discovered through actual `RUN_COMMAND` execution, not cross-app private-file checks.
- Updated the supported stack to AGP 9.1.1, Gradle 9.3.1, built-in Kotlin 2.2.10, Compose compiler 2.2.10, KSP 2.3.6, Room 2.8.5, NDK 28.2.13676358, and CMake 3.22.1. KSP 2.2-era/Room 2.6.1 compilation errors prompted the corrected pins; no Kotlin/new-DSL suppression remains. Successful integrated compilation and 212 JVM tests were reported separately, not inferred merely from the dependency fix.
- Verified genuine **host x86_64 JNI** with the Apache-2.0 SmolLM2-135M-Instruct-Q4_K_M: `The capital of France is Paris.` streamed in **7 true selected tokens**. ARM64 cross-compilation also passed. Host execution and cross-compilation are not Android/device performance, lifecycle, memory, or 16 KB runtime validation. See [Native verification](docs/NATIVE_INFERENCE.md#reproducible-verification) and [BUILD.md](BUILD.md) for reproduction/device procedures.
- Retained Apache-2.0 project licensing, upstream/third-party licenses, historical changelog entries, additive Room migrations, branding, chat/history, encrypted credentials, agents/workflows, bounded text import, DAG/evidence execution, and conservative recovery. Local source ZIPs include vendored llama.cpp and licenses but exclude model weights, generated build/.cxx/.kotlin outputs, caches, local configuration, and secrets.
- Not authorized or implied: v0.6.0 GitHub publication, automatic installation/self-update, commits/pushes, credential/privilege actions, or store submission. The unfinished current PriorityNine directive is not additional authorization. Not implemented/verified: unrestricted autonomy, dynamic replanning, killed-process continuation, multi-device sync, Android UI automation, production signing, and Android/physical-device validation.

See [USER_GUIDE.md](USER_GUIDE.md), [PLATFORM_NOTES.md](PLATFORM_NOTES.md), [Development engine](docs/DEVELOPMENT_ENGINE.md), [Context and skills](docs/CONTEXT_AND_SKILLS.md), and [Android compatibility](docs/ANDROID_COMPATIBILITY.md). Historical entries and their test counts/limitations below describe their own releases, not current v0.6.0 totals.

## 0.5.0 — Real execution engine

- Moved objective execution out of the ViewModel into a `dataSync` foreground service with durable leases, heartbeats, notification controls, and startup reconciliation. Closing the UI no longer cancels a running objective; process death still stops it, and interrupted tasks are never replayed automatically.
- Added dependency-aware concurrent scheduling (`ConcurrentTaskScheduler`, `ResourcePolicy`) bounded by device RAM, thermal status, battery, storage, and per-worker-kind limits. Blocking propagates transitively through stalled subgraphs.
- Added locally checked completion evidence: exit codes, expected/forbidden output, file existence, file contents, and SHA-256. A model's prose alone never marks a task complete, and any failed or denied tool call fails verification.
- Added conservative recovery: bounded retry only for read-only/verification work, permanent failure on a repeated identical failure signature, and mandatory human review for anything that may have changed external state.
- Added a real Termux bridge: environment discovery (host, PREFIX, HOME, tools, installed PRoot distros), a command-risk policy that blocks root/protected-Android operations, per-command approval for mutating commands, hard `timeout` supervision when available, and persisted stdout/stderr/exit-code history.
- Added workspace-scoped agent tools: `terminal_run`, `file_read`, `file_write`, `file_list`, `file_search`, `project_detect`, `build_run`, `test_run`, plus a `FileProbe` used by verification. Terminal access is off by default and requires an explicit toggle and workspace path.
- Added provider-neutral inference (`InferenceProvider`, `ProviderFactory`, capability cache) shared by chat, agents, and the engine, with the existing Ollama and OpenAI-compatible transports and Keystore credential path unchanged.
- Added a filesystem-backed context store with bounded, relevance-ranked context assembly and persisted context records, plus persisted task evidence rows.
- Added Room schema v3 (additive 2→3 migration): task priority/worker kind/idempotency/failure signature/evidence, objective lease columns, terminal command history, task evidence, context records, and execution leases.
- Added a Terminal panel in the Autonomy screen and 71 new JVM tests covering command policy, shell construction, evidence verification, recovery, environment parsing, scheduling, context assembly, and provider capabilities. Total JVM tests: 98, all passing.
- Not implemented in this release: native GGUF/llama.cpp inference, mid-process resumption of a killed objective, real Debian/PRoot validation on a device, multi-device sync, and any autonomous publication, credential, or privilege action.

## 0.4.0 — Durable objectives and execution foundations

- Added durable objectives, task DAGs, lifecycle statuses, attempt budgets, checkpoints, structured tool invocation/observation events, and text artifacts in Room schema v2.
- Added a non-destructive Room 1→2 migration and Android instrumentation test fixture that seeds and checks legacy chat, endpoint, agent, workflow, and execution records.
- Added strict, bounded JSON plan parsing (64 KiB, max 12 tasks, field limits, unique keys, valid dependencies, acyclic graph, deterministic stable task UUIDs). Model-provided tools, agents, and actions are ignored; the caller-selected agent and existing tool allowlist govern execution.
- Added a single-inference, deterministic dependency-aware objective runner and Autonomy screen. Completed tasks are not replayed. Interrupted active work is marked uncertain; failed/interrupted task retries require explicit user review. Outcomes and traces persist locally.
- Added a conservative resource policy foundation with serial model execution and explicit local completion checks. Verification is intentionally not presented as independent proof of semantic correctness.
- Added an isolated Termux RUN_COMMAND protocol adapter core with environment labels, request validation, argv-only command descriptors, Termux permission/readiness checks, bounded callback data, timeouts, and pure result-classification tests. It is not yet exposed in the autonomy UI or available as an agent tool; timeout/cancel stops waiting for the callback and cannot guarantee that Termux stopped the process.
- Fixed Settings → About to derive the displayed release from installed Android package metadata. Added a release-integrity checker for Gradle, APK binary manifest, delivery filenames, and release docs.
- Preserved prior branding, adaptive layout, Ollama/OpenAI-compatible clients, encrypted API keys, agents, workflows, chat history, and existing v0.3.0 package identity.

## Retained 0.3.0 and 0.2.0 capabilities

Ollama and OpenAI-compatible `/v1` inference, streaming/cancellation, bounded local agents, agent editing, sequential workflows, Keystore-protected API credentials, explicit remote endpoint consent, user-selected text import, adaptive Fold layouts, and device/thermal snapshots remain in place. No destructive Room migration is used.

## Known incomplete / unverified capabilities

NYX v0.4.0 is not a full autonomous computing platform. Objectives currently allow up to 12 validated DAG tasks and execute one agent task at a time; there is no dynamic replanning, 20-task queue, parallel tool worker, persistent filesystem-backed context store, context compression, pluggable provider interface, or live resource scheduler. Objective execution is ViewModel-owned while the process runs; it does not use a foreground service or WorkManager and cannot continue after process death. Room recovery marks active tasks for review rather than replaying them. The Termux bridge is protocol/library work only: no setup screen, connected process supervision, autonomous shell access, or validated Pixel/GrapheneOS execution. Debian Proot setup is unverified. Native GGUF inference, Android UI automation/accessibility, complete software-development workflows, and production signing are not included. Physical-device migration, Keystore, Termux, Fold, performance, and live backend tests remain outstanding. See `NYX_DEVELOPMENT_REPORT.md` and `TEST_RESULTS.md`.

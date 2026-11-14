# NYX Eclipse AI — Development Reports

## v0.8.4 — Project Endurance (published)
NYX 0.8.4 is versionCode 17/versionName 0.8.4 for `com.nyx.ai`, published as [GitHub release v0.8.4](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.4). It adds observed device intelligence, Eco/Balanced/Performance/Adaptive modes with thermal and battery safety ceilings, adaptive native threading at safe boundaries, opt-in bounded calibration, exact-token-prefix KV reuse, mode-aware autonomy concurrency, mode-aware stream coalescing, and Eco-only idle model release. The Performance dashboard exposes capabilities, live resource state, effective tuning, calibration samples, and honest accelerator status.
| Area | Status | Evidence/limit |
| :--- | :--- | :--- |
| Device intelligence and modes | Implemented, build-tested | Reads Build/ActivityManager/PowerManager/battery/display/package features and readable `/proc`/cpufreq evidence; missing data is labeled unavailable. |
| Adaptive native threading | Implemented, build-tested | Applied before load; resident sessions keep their exact thread count; Apply reloads only at an idle boundary. |
| Bounded calibration | Implemented, build-tested | Up to three thread candidates, eight tokens each, 120 s budget, gate and `finally` restore; not run on hardware. |
| Exact-prefix KV reuse | Implemented, compiled | Exact token match only, at least one token always decoded, full cache/memory invalidation on failure; no device/model runtime test. |
| Autonomy and streaming | Implemented, build-tested | `ResourcePolicy` concurrency cap and 120/60/33 ms stream coalescing; existing safety limits unchanged. |
| Verification | Build-tested | 713 JVM tests across 68 classes; ARM64 debug and instrumentation APKs assembled. Instrumentation was not run. |
| Device behavior | Partially observed | The release owner reports that the Performance dashboard rendered on a Pixel 10 Pro Fold. Native inference, calibration, Eco idle release, adaptive apply, and KV reuse still require device validation with a GGUF model. |
The published assets include the primary APK, instrumentation APK, source archive, and checksum manifest. The APK is debug-signed, not production/Play-signed.

## v0.8.3 — Project Mission Control (published)

NYX 0.8.3 is versionCode 16/versionName 0.8.3 for `com.nyx.ai`, published as [GitHub release v0.8.3](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.3). It makes existing autonomy state easier to inspect through Mission Control, task details, clearer host/guest selection, and Live Worker Console visual refinements; it does not add a new execution engine or widen permissions. The v0.8.2 task-scoped dependency repairs remain intact.

| Area | Status | Evidence/limit |
| :--- | :--- | :--- |
| Autonomy Mission Control | Implemented, build-tested | Displays persisted objective/task progress and existing controls; physical rendering was not tested. |
| Readiness and task timeline | Implemented, build-tested | Uses actual model, environment, workspace, objective, and task state; no synthetic readiness score is presented. |
| Terminal and console presentation | Implemented, build-tested | Separates Termux Host/Debian PRoot targets and improves Live Worker Console readability; authorization paths are unchanged. |
| Verification | Build-tested | 702 JVM tests passed; ARM64 debug and instrumentation APKs assembled. Instrumentation was not run. |
| Device behavior | Pending | No physical-device visual/touch/inset, fold/unfold, or Termux/PRoot execution check is claimed. |

The published assets include the primary APK, instrumentation APK, source archive, and checksum manifest. The APK is debug-signed, not production/Play-signed.

## v0.8.2 — Project Unshackle (published)

NYX 0.8.2 is versionCode 15/versionName 0.8.2, debug-signed with the established certificate, and published as the GitHub release `v0.8.2`. This focused autonomy reliability release fixes false toolchain classification and moves dependency preparation behind task planning, while preserving the persisted task graph and the same gate on resume.

| Area | Status | What changed |
| :--- | :--- | :--- |
| Project-kind inference | Implemented, JVM-tested | Nearby negation is respected while positive Android, Kotlin/JVM, C/C++, Node, and Python project needs remain recognized. |
| Dependency timing | Implemented, JVM-tested | The persisted task graph is checked immediately before executable terminal/development tasks, rather than probing against broad objective text before planning. |
| Shell-objective blockers | Implemented, JVM-tested | Shell-only, inference-only, verification-only, and negated toolchain tasks bypass unrelated development dependency checks. |
| Resume behavior | Implemented, JVM-tested | Resumed task graphs use the same dependency gate before task-state reset/continuation; a block preserves tasks/checkpoints and returns to `needs_attention`. |
| Android projects | Implemented, JVM-tested | Affirmative Android app/APK work still selects the Android toolchain and retains package authorization and verification. |
| GitHub language stats | Implemented | `.gitattributes` marks bundled llama.cpp as vendored; no llama.cpp source changes were made. |
| Verification | Build-tested | 702 JVM tests passed; ARM64 debug APK and instrumentation APK assembled. Instrumentation was not run. |

### Physical-device limits

No Android device was attached for this build. Actual Termux/PRoot package installation, dependency continuation, and resumed objective execution remain device-validation items. A green JVM suite and built APK do not establish successful physical-device autonomy. The APK is a debug-signed development build, not a Google Play release. See `TEST_RESULTS.md` and `V082_IMPLEMENTATION_REPORT.md` for precise evidence and limitations.

### Release artifacts

The official `v0.8.2` GitHub release includes the ARM64 APK, compiled instrumentation APK, clean source archive, and checksum manifest. No AAB or Play submission was produced.

## v0.8.1 — Project Clear Signal (published)

NYX 0.8.1 is versionCode 14 / versionName 0.8.1, debug-signed with the established certificate, and published as GitHub release v0.8.1. It is a focused observability and planning-reliability release, not a new autonomy claim. The established NYX identity, README layout, history, license, and prior releases are preserved.

| Directive area | Status | What changed |
| :--- | :--- | :--- |
| Run separation | Implemented, JVM-tested | `WorkerActivity` carries `runId`; the service assigns one identity per launch and passes it to engine/service events. `WorkerConsoleModel` separates objectives and runs, including legacy null-run events. |
| Current-run default | Implemented, device-untested | Chat and Autonomy filter the preview to the current objective/current run. The dialog defaults to that run and exposes earlier runs through a history selector. |
| Operation grouping | Implemented, JVM-tested | Related task/planning events are grouped into `ConsoleOperation` cards with observed status, event count, and elapsed time from real timestamps. Tapping opens a full-height chronological breakdown. |
| Scroll and layout | Implemented, device-untested | A full-screen dialog applies safe display insets. Auto-follow happens only when the user is near the bottom; switching runs starts at the newest item. |
| Raw diagnostics | Implemented, JVM-tested | Raw-event mode exposes the complete stream. ANSI CSI/OSC sequences are stripped and credential-shaped values remain redacted in display and export. |
| History clearing | Implemented, device-untested | Confirmation-gated `clearHistory` preserves the selected/current and active runs and never deletes Room objectives, tasks, evidence, or workspace files. |
| Planning deadline | Implemented, JVM-tested | Each planning attempt has a 120-second hard deadline and emits progress events every 10 seconds. Timeout is surfaced as its own category. |
| Structured-output repair | Implemented, JVM-tested | A completed response that fails strict JSON/schema validation gets exactly one repair prompt with the validator reason. Transport failures are not repaired as malformed output. |
| Honest failure state | Implemented, JVM-tested | Planning failure records a sanitized category and leaves the objective in `needs_attention`; it does not silently restart. Existing checkpoints and dependency evidence remain intact. |
| Shell syntax hardening | Implemented, JVM-tested | Model-directed commands reject redirection, pipes, chaining, substitution, variable expansion, and indirect execution before risk classification. Quoted metacharacters remain ordinary arguments. |
| Tests | Implemented | Full suite: **695 JVM tests, 0 failures, 0 errors, 0 skips**. New focused coverage includes `WorkerConsoleModelTest`, `PlanningRecoveryTest`, and expanded `ModelCommandPolicyTest`. |

### Physical-device evidence and limits

No physical Android device was available for this build. The console rendering, history selector, operation cards, scroll behavior, clear-history flow, provider planning timeout/repair, and shell-policy behavior are JVM/build verified only. The client-side deadline does not guarantee provider-side cancellation. The console is a bounded diagnostic store, not an unlimited audit archive; objective and task truth remains in Room. The APK is debug-signed and is not a Play Store artifact. No AAB or Play publication was performed.

### Verification

The final clean build, test counts, APK identity, signer, and checksums are recorded in `TEST_RESULTS.md` and `docs/V081_IMPLEMENTATION_STATUS.md`.

## v0.8.0 — Project Autonomous Bootstrap (published)

NYX 0.8.0 is versionCode 13 / versionName 0.8.0, debug-signed with the established certificate, and published as GitHub release v0.8.0. It turns verified dependency preparation into part of objective execution and adds a policy-gated terminal, an expandable Live Worker Console, and a guarded workspace lifecycle. It is not a claim of full autonomous development.

| Directive area | Status | What changed |
| :--- | :--- | :--- |
| Environment reconciliation | Implemented, JVM-tested | The objective service re-discovers the selected environment on every start and compares it with the stored snapshot. A current verified guest clears a stale error; an unavailable or diverted environment stops before task execution. |
| Dependency bootstrap | Implemented, device-fix pending re-test | `DependencyBootstrap` probes, plans, installs only approved candidates, re-probes required executables, and continues the original objective. A blocked or failed install stops with `needs_attention`. |
| Debian 13 / Deb822 | Implemented, regression-tested | `GuestJdkResolver` reads traditional `*.list` and Deb822 `*.sources` files, requires an enabled `deb` stanza with `URIs` and `Suites`, and selects a real `apt-cache policy` candidate. Trixie prefers OpenJDK 21 when available. |
| Policy-gated terminal | Implemented, JVM-tested | `terminal_run` requires a verified workspace, safe working directory, bounded timeout, credential-path denial, and `RUN_READONLY_COMMAND`/`RUN_MUTATING_COMMAND` authorization. Publishing, system changes, credentials, and destructive work remain outside ordinary model authority. |
| Objective continuation | Implemented, JVM-tested | Successful provisioning returns to the existing objective engine with its task graph and checkpoints intact. Installation success is not treated as task or objective success. |
| Live Worker Console | Implemented, device-untested | The persisted bus now carries bounded tool input/output. Chat and Autonomy open a full-height expandable timeline with objective/phase/task/status, pause/resume/cancel, copy, and redacted export. |
| Workspace lifecycle | Implemented, JVM-tested | The saved workspace is re-probed and reused on startup. A managed registry, synchronized policy scope, explicit creation, and guarded per-path or delete-all cleanup replace silent numbered-folder churn. |
| Tests | Implemented | Full suite: **679 JVM tests, 0 failures, 0 errors, 0 skips** across 64 classes. New coverage includes workspace lifecycle and export redaction. |

### Physical-device evidence and limits

The owner’s Android 17 device running Debian 13.7 Trixie reproduced the original blocker: NYX reported `APT sources: 0` while valid enabled Deb822 `deb` repositories existed in `/etc/apt/sources.list.d/debian.sources`; `apt-cache policy` showed `openjdk-21-jdk-headless` available. That evidence drove the resolver and continuation fix. The fixed APK’s successful install and objective continuation, the new console rendering, and workspace deletion have not been re-run on a device and are not claimed as device-verified. End-to-end calculator verification remains unfinished. Native GGUF, Termux IPC, Keystore, notifications, foreground-service lifetime, Room migration, and fold/unfold behavior remain device checks. The APK is debug-signed and is not a Play Store artifact.

### Verification

The final clean build, test counts, APK identity, signer, and checksums are recorded in `TEST_RESULTS.md` and `V080_LIVE_WORKSPACE_REPORT.md`.

## v0.7.9 — Project Persistence (published)

NYX 0.7.9 is versionCode 12 / versionName 0.7.9. It is a focused fix for the failure mode where a Debian-hosted Ollama server started successfully but did not stay alive, or stayed alive without a reliable owner. It is published as GitHub release v0.7.9; no physical-device claim is made.

| Directive area | Status | What changed |
| :--- | :--- | :--- |
| Android process lifetime | Implemented, device-untested | Added non-exported `OllamaHostService`, declared `dataSync` with `FOREGROUND_SERVICE_DATA_SYNC`. Guest start/stop/restart run inside this service; the notification exposes an explicit Stop action. Host-mode lifecycle stays direct. |
| PRoot session ownership | Implemented, device-untested | `OllamaGuestRuntime` probes `login --detach`, `ps`, and `kill` together. Supported installations get one detached session with a persisted session PID and kernel start-time token; unsupported installations keep one foreground PRoot login alive. |
| Server survival | Implemented | The server always runs in the foreground of the session (`exec ollama serve`), not as a detached child. This avoids PRoot `--kill-on-exit` removing the server when the login shell exits. Duplicate starts are refused while a verified session is alive. |
| Stop and cleanup | Implemented, device-untested | Stop terminates the verified guest PID, calls `proot-distro kill` for the verified session, then re-inspects `/proc/<pid>`. A surviving session produces a retryable error, not a false STOPPED. Orphaned sessions remain visible with Stop available. |
| Refresh and adoption | Implemented | Refresh re-reads the persisted session, verifies its start token, adopts a live process, and clears only an unverifiable record. A live API without a verified process is not claimed as NYX-owned. |
| Startup diagnostics | Implemented | `OllamaGuestRuntime.startupStage` distinguishes PROCESS_GONE, GUEST_API_WAITING, ANDROID_REACHABILITY_WAITING, API_WAITING, PORT_CONFLICT, and READY. The existing port-conflict path leaves the process running. |
| Chat/objective reconnect | Implemented | The shared `startManagedOllamaForInference` path routes guest startup through the service, polls discovery, and refreshes models only after readiness. Objective creation and resume use it. |
| Tests | Implemented | `OllamaGuestRuntimeTest` adds 9 focused JVM tests for session parsing, ownership matching, readiness stages, capability parsing, and safe scripts. Full suite: 632 tests, 0 failures/errors/skips. |

### Honest limits and remaining device checks

- No physical device was attached. Actual `proot-distro ps` column output, `login --detach` lifetime, Android 14+ `dataSync` behavior, Termux `RUN_COMMAND` callback lifetime, and guest API reachability remain device checks.
- The foreground fallback cannot survive Android killing the NYX process; the guest PID record may remain until a verified stop or refresh.
- A user-started server that NYX did not launch is never killed; it remains adopted read-only.
- Debug signing is retained; this is not a Play Store artifact.

### Verification

JVM tests and APK/instrumentation compilation are recorded in `TEST_RESULTS.md`. No device-executed Ollama or PRoot result is claimed.

## v0.7.7 — Project Liberation (published)

NYX 0.7.7 is versionCode 10 / versionName 0.7.7, debug-signed with the v0.7.5 certificate, and published for physical-device validation. The Debian/Ollama and repeated-workspace acceptance tests still need to be run on the owner's device.

| Directive area | Status | What changed |
| :--- | :--- | :--- |
| Debian Ollama discovery | Implemented, device-untested | The manager probes the isolated host and every verified PRoot guest. `OllamaTarget` records environment + guest alias; `OllamaDetection` now carries target, `uname -m`, and compatibility. `/usr/local/bin/ollama` is recognized through `command -v`, and warning-prefixed `ollama --version` output yields a parsed version with `COMPATIBLE=1` rather than “missing.” |
| Multi-environment selection | Implemented | `OllamaDiscovery.choose` prefers the current environment, then the host, then a compatible guest, while retaining incompatible installations for diagnostics. The Models card shows the selected target, version, architecture, and probed candidates. |
| Guest lifecycle | Implemented, device-untested | Start/stop/state scripts execute through `TerminalBridge.runShell(... DEBIAN_PROOT ..., guestAlias=...)`. PID/start-token records are scoped by environment, adopted servers remain protected, and a live-but-unreachable owned process is reported distinctly. Start uses `setsid` with a `nohup` fallback for PRoot. |
| Model reconnect | Implemented | Detection/refresh reloads the model inventory when the server is running. Creating or resuming a loopback objective starts the managed server if needed, waits for readiness, calls the shared suspend model refresh, and only then proceeds. |
| Full workspace authorization | Implemented | `CREATE_WORKSPACE` is a separate routine action against the authorized root. Full mode allows repeated project creation inside that root without a per-project elevated grant; Manual still prompts. The UI shows an explicit approval card and never presents the raw policy code as the only explanation. |
| Provisioning continuity | Partially integrated | Atomic workspace creation, dependency planning, approved installation, capability refresh, and development preflight remain wired as in v0.7.5. Automatic objective continuation after a dependency install is not a new v0.7.7 claim; the existing flow still requires the user to start the objective after setup. |
| Arsenal/MCP/memory/console | Inherited, not reworked | The v0.7.5 implementations remain: nine built-ins, per-server gateway allowlists, evidence-linked project memory, symbol index, and the persisted worker console. This release did not add new framework code in those areas. |
| UI and safety | Implemented | The managed Ollama card exposes target/status/version/architecture/probes; Stop is available for any verified owned process. The global error banner is limited to actionable screens and cleared when navigating to unrelated sections. Policy access remains synchronized, and Emergency Stop still gates lifecycle and workspace actions. |
| Release engineering | Implemented | VersionCode 10 / versionName 0.7.7; 609 JVM tests pass; debug APK and instrumentation-test APK build; signing identity matches v0.7.5. Artifacts are published with a SHA-256 manifest and release notes. |

### Honest limits and remaining device checks

- No physical device was attached. Real Debian `ollama serve` detachment, API readiness, model listing, Chat reconnect, stop/restart, and PRoot IPC remain unverified on hardware.
- The probe is limited to the host plus at most four verified guest aliases.
- Automatic dependency installation still follows the existing policy: Full/Smart may plan it, but a high-risk package action can still require explicit approval. The objective is not automatically resumed after an install.
- The v0.7.7 APK is published; physical-device acceptance remains outstanding.
- Debug signing is retained; this is not a Play Store artifact.

### Verification

`clean testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin` and `assembleDebugAndroidTest` completed successfully. **609 tests, 0 failures, 0 errors, 0 skipped.** APK package `com.nyx.ai`, versionCode 10, versionName 0.7.7, compile/target SDK 37, minSdk 26, `arm64-v8a`, certificate SHA-256 `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. Exact artifact digests are in `TEST_RESULTS.md` and the delivered checksum manifest.

## v0.7.5 — Project Continuum (published)

NYX 0.7.5 is versionCode 9 / versionName 0.7.5, debug-signed, published as v0.7.5, and intended for device testing. It keeps the v0.7.0 autonomy foundation and closes the largest operational gaps in the v0.7 directive.

| Directive area | Status | What exists |
| :--- | :--- | :--- |
| Managed Ollama lifecycle | Implemented, host-untested | `OllamaServerManager` + `OllamaProcess`: install discovery, isolated Termux-host scripts, start/readiness/stop/restart, PID **and** kernel start-token ownership, adopted-server protection, bounded log tail, RSS/uptime, auto-refresh. Models screen exposes Detect/Refresh/Start/Stop/Restart and explains that Emergency Stop blocks new lifecycle work. No `pkill`; a reused or adopted PID is never signalled. |
| Arsenal operational skills | Implemented | Nine versioned built-ins with real declared tools and offline procedures. Bounded selector injects only the three most relevant procedures per task and adds their tools to that task's allowlist. Strict untrusted import, enable/disable/configure/remove, evidence-linked JSONL execution logs, and a dedicated UI. |
| MCP + GitHub gateways | Implemented, network-untested | Durable `GatewayRepository`, streamable-HTTP MCP client, GitHub REST tools, per-server allowlists, per-run call budgets, timeouts, response caps, health checks, redacted logs, and a Gateways UI. Tokens live only in the Keystore vault; configs store opaque refs. Objective runs snapshot allowed tools; models cannot add a server or widen an allowlist mid-run. |
| Durable project memory | Implemented and integrated | Evidence-hashed memories, near-duplicate merge, stale/unverified suppression, type-aware relevance, bounded prompt block, and outcome recording. The objective engine now injects relevant memory and records verified/failed task outcomes. |
| Source symbol index | Implemented, not yet prompt-integrated | Incremental Kotlin/Java declaration/reference parser with SHA-256 change detection, bounded persistence, exact lookup, and deterministic relevance ranking. Tests cover parsing, hashing, persistence, and ranking. It is deliberately not claimed as an automatic prompt feature in this build. |
| Live Worker Console | Implemented | Persisted `WorkerActivityBus`, compact chat preview, full console dialog, and engine/terminal/gateway/Ollama events. No fabricated progress: entries correspond to persisted engine events or explicit user actions. |
| Workspace and policy hardening | Implemented | Atomic collision-safe workspace creation (never reuses an existing directory), terminal cancellation mapped to `uncertain`, workspace-scoped routine grants, grants removed when returning to Manual, synchronized stop/revocation, package authorization scoped under the workspace, and one Settings panel documenting the shared policy source and scope. |
| Release engineering | Implemented | VersionCode 9 / versionName 0.7.5; 598 JVM tests pass; debug and instrumentation APKs compile; APK metadata/signature verified; published with the annotated v0.7.5 tag and all four assets. |

### Honest limits

- No physical Android device: Termux IPC, real Ollama process control, Keystore use, MCP/GitHub calls, notifications, foreground-service restart, and thermal behavior are compiled/unit-tested only.
- Gateway dynamic tools are snapshotted per objective. A server whose tool list changes after the snapshot requires a new objective.
- The symbol index is a durable library and test-covered API, not yet automatically wired into every task prompt.
- The "managed server" is a Termux-host process supervised through `RUN_COMMAND`; NYX does not bundle Ollama, does not install it silently, and does not control an adopted server.
- Arsenal imports are data/procedures only. They cannot add executable code or claim tools outside the validator's fixed table.
- The APK remains debug-signed and is not a Play Store production artifact.

### Verification

`testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin` completed with **598 tests, 0 failures, 0 errors, 0 skipped**; APK `com.nyx.ai` versionCode 9/versionName 0.7.5; debug signature SHA-256 `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. Exact artifact digest is in `TEST_RESULTS.md`.

## v0.7.0 — Project Ascension (published)

NYX 0.7.0 turns the previous execution foundations into a governed autonomy layer. It is versionCode 8 / versionName 0.7.0, debug-signed, and published as v0.7.0.

| Directive area | Status | What exists |
| :--- | :--- | :--- |
| Central autonomy policy | Implemented | `AutonomyPolicyEngine` with Manual/Smart/Full, capability grants, scope and expiry checks, delegated-scope narrowing, risk classifier, persisted snapshot, audit journal, process-wide `AutonomyRuntime`, and Emergency Stop enforced at the terminal repository, installer, and objective service |
| Capability and environment model | Implemented | Origin-aware capability probe, `apt`/`dpkg` detection, inherited-Termux rejection inside guests, workspace package-manager derivation, timestamped profiles |
| Automatic provisioning | Implemented | Deterministic `DependencyPlanner`, trusted candidates only, `DependencyInstaller` with whole-plan authorization and re-probe, `WorkspaceProvisioner` with collision-safe creation and write verification |
| Isolated Debian tools | Implemented with blocker | Guest tools must be `GUEST_NATIVE`; Android SDK builds are explicitly blocked in an ARM64 guest instead of attempting an impossible install |
| Controlled network | Implemented | `fetch_url` objective tool, HTTPS/public-host policy, private-address and loopback-pivot refusal, redirect re-validation, guarded DNS, size caps, untrusted-content wrapping, atomic SHA-256 downloads in the network layer |
| Supervisor and delegation | Implemented as role-based | Deterministic `WorkerRoles`, role prompts, `SupervisorPolicy` with continue/replan/pause/fail, repeated-failure and budget guards; not one process or model per role |
| Autonomy UI | Implemented, compact | Settings policy card with Full consent dialog, Emergency Stop/Resume, revoke grants; Terminal panel with project creation, dependency plan, install approval, and inherited-tool labels |
| Verification | Compiled and JVM-tested | Clean build, 450 JVM tests, 0 failures, instrumentation APK compiled; no device execution |

The policy is deliberately centralized. Terminal commands that pass through `TerminalRepository` carry an optional `ActionRequest` and are denied or held before dispatch. Package installation authorizes every candidate before the first command runs, so a mid-plan denial cannot leave a partial surprise install. Workspace creation is an explicit user action and still checks policy first. Full mode creates only routine grants for the selected environment and workspace; publishing, Git pushes, APK installation, system configuration, credential use, and private-data transfers remain outside those grants.

The environment work fixes a concrete failure mode from earlier releases: a Debian guest whose `PATH` includes the Termux prefix could report `git`, `clang`, or `make` as present even though they were host binaries. The probe now reports origin, the capability state marks inherited binaries incompatible, and the planner treats them as missing. Installation candidates are generated from a fixed table (`apt` for guests, `pkg` for the host), never from model text.

The network tool exposes only text fetch to objectives. The download implementation is present and tested at the network layer but is not offered as a model tool, because arbitrary file drops into a Termux or PRoot workspace require a terminal-mediated transfer path that is not implemented yet. This is an intentional boundary, not an oversight.

Source batch reads were reduced to four files and 96 KB per IPC call with per-file fallback. That addresses the large-callback truncation observed on the device without weakening hash verification.

Remaining gaps are explicit: no multi-model worker tree or live delegation graph, no full active-agent roster UI, no device verification of Termux/PRoot/Keystore/service behavior, no distribution-specific dependency resolution beyond the planner table, and no publication authorization. The next sensible step is a physical-device pass focused on origin labels, `apt` installation, workspace creation, Emergency Stop across service restart, and network fetches from the objective engine.

## v0.6.1 continuation (previous release)

NYX 0.6.1 addresses the environment failures observed on the Pixel Fold and closes the "passing baseline equals success" gap in development objectives. Package metadata is versionCode 7 / versionName 0.6.1. The build is debug-signed and verified by compilation and JVM tests only; no physical device was available.

**Environment reliability.** Discovery now bypasses Termux startup profiles by invoking `$PREFIX/bin/bash --noprofile --norc` with `BASH_ENV` and `ENV` cleared, and verifies the result really is the Termux host via `getprop`. A command that still lands in a guest is reported as `nestedProot` with the remedy instead of being misread as a missing installation. The installed-distro list is parsed from `proot-distro list --quiet` (plus sectioned/older formats); failure, a missing exit code, and a nested-PRoot refusal are distinct from a genuinely empty list. Each distribution is verified by running `/bin/sh` inside it and requiring `NYX_GUEST_OK` plus observed HOME, PWD, OS, uid, and tool paths. Execution requests carry a validated `distroAlias`, and the service refuses Debian objectives unless that guest verified.

**Capabilities, workspace, preflight.** A timestamped per-environment capability profile records tool state, path, bounded version, free storage, and workspace writability. Workspace validation is syntax first, then a real existence/write probe; guest paths reject Android `/storage` and `/sdcard` locations. Development preflight reports stable blocker codes for provider, model, environment, workspace authorization/accessibility, required tools, and advisory memory, and `createObjective` will not start while blocked.

**Verified developer v2.** Goals are classified deterministically; acceptance criteria are derived from the goal kind. Only `REPAIR_VERIFIED` and `FEATURE_IMPLEMENTED_AND_VERIFIED` count as passed. A healthy baseline for a repair goal is `BASELINE_HEALTHY`; a feature whose suite passes but which changed no test is `GOAL_NOT_VERIFIED`; a missing toolchain is `ENVIRONMENT_UNAVAILABLE`. Original command output is retained as evidence, separate from model responses. Source context is relevance-ranked and read in batches of up to 12 files per IPC call with a per-file fallback.

**Verification.** 350 JVM tests pass with zero failures, including the four required development fixtures and adversarial path/patch cases. Two prior tests were tightened: baseline health no longer asserts success, and a listed distro no longer asserts availability. The instrumentation APK compiles. **Device execution of the Termux/PRoot flows, the foreground service, and real-model coding remains unverified and is listed as a checklist in `docs/V061_VERIFIED_DEVELOPER.md`.**

**Known limits.** Capability data is a point-in-time snapshot. "Test evidence" does not judge test quality. Preflight does not install tools or elevate privileges. The public release is v0.6.1.

## v0.6.0 — Project Singularity (previous local build)

NYX v0.6.0 continues the existing Kotlin/Compose, Room, provider, task-engine and Termux architecture. It adds genuine llama.cpp CPU inference, a bounded development repair loop and Android API 37 compatibility without replacing the app. This is a locally delivered, debug-signed development build, not a claim of production readiness or Google Play approval. The Apache-2.0 project license and vendored runtime licenses are preserved.

## Demonstrated evidence

The integrated application and instrumentation APKs assembled, and **212 JVM tests passed with zero failures, errors or skips**. The final clean-build result, APK identity and artifact checks are recorded in `TEST_RESULTS.md`. Instrumentation compilation is not device execution.

A genuine Linux x86_64 JNI session, using the application's actual Kotlin native bindings and pinned llama.cpp source, loaded an external Apache-2.0 SmolLM2-135M-Instruct Q4_K_M GGUF and streamed `The capital of France is Paris.` in seven selected non-EOG tokens. Cancellation, same-session reuse after abort, callback error propagation and repeated unload were exercised. The Android ARM64 library cross-compiled; every packaged native library and the APK passed 16 KB alignment checks. **Android-native generation, phone throughput, memory survival and foldable behavior have not been physically tested.**

The development engine ran a real Linux fixture. Original build/test exits were 0 and 2; a deterministic test provider supplied an exact-hash repair; repeated build/test exits were 0 and 0. The original backup and observed `41 -> 42` diff were retained. Command execution and edits were real, but the patch-producing model provider was a test double. This is not evidence of real-model autonomous coding completion on a phone.

## Native inference and Models UI

`nativeinference/` implements a buffered SAF importer, private model storage, bounded GGUF metadata/tensor validation, metadata sidecars, conservative admission and a process-owned runtime. C++ pins llama.cpp commit `09338b85e8b230742fa56f93a1d9f0bf006ca2d3`. Full vendor source and licenses are included for reproducibility; no weights are bundled.

Models can be imported, inspected, selected, loaded, streamed, stopped, unloaded and removed from private storage. The original selected document is not deleted. Legacy GGUF URI registrations must be re-imported. A compatible instruct/chat model with a supported default template is required. Context and threads are configurable; sessions are reused when model/context/thread settings match. Switching unloads the old session before admitting the next.

This artifact supports ARM64 CPU/NEON on API 26+, not 32-bit-only or x86 Android devices. Unsupported templates, architectures, tokenizer metadata, uncertain KV layouts, insufficient memory and oversized prompts fail explicitly. There is no GPU, NPU, Vulkan, Tensor, Qualcomm, multimodal, hybrid/recurrent or arbitrary-Jinja compatibility claim. A tiny-model smoke test does not qualify all 3B/4B/7B models or their coding ability. See `docs/NATIVE_INFERENCE.md` for reproducible verification.

## Shared provider path and actions

Chat, agents, workflows and objectives resolve through `InferenceClientFactory`. Native GGUF joins Ollama and OpenAI-compatible APIs. Resolution never silently selects another endpoint or drops an unreadable saved key. Objective events snapshot endpoint, model, options, workspace and environment; explicit reviewed resume may create a new snapshot. LAN and emulator-host endpoints are not described as on-device inference.

`InferenceFabric` permits one active model request globally and at most 24 waiting requests. Task concurrency is separate, and shared terminal tool/evidence scopes are serialized. The HTTP path retains HTTPS-only remote Bearer credentials, redirect refusal and Keystore encryption. There is no automatic fallback that sends native prompts elsewhere.

Native function calling is not advertised. `StructuredActions` uses a strict text-JSON protocol with tool allowlists, required fields, argument types and byte/character ceilings. One response-repair attempt is permitted; malformed output is denied rather than converted into shell text. HTTP function calls use their own protocol and the same tool validation. Raw model-directed shell is not available by default; policy-gated `terminal_run` is available only when an execution supplies a workspace-confined command gate.

## Development repair loop

Autonomy → Objectives offers **Use verified project repair loop**. It creates a deterministic development task rather than relying on model classification. First approve a trusted workspace/environment in Autonomy → Terminal. The worker connects discovery, bounded source indexing, baseline build/test, strict patch generation, stale-hash checks, original backups, atomic per-file replacements where practical, real retesting and observed diffs/evidence.

Build/test commands are fixed from detected project markers and available toolchains. Models cannot override commands, install dependencies, read credentials, commit or publish. Missing prerequisites stop the loop. Defaults are three iterations, four inference calls, five minutes and eight edited files. Settings exposes bounded budgets, capped at five iterations, six calls, ten minutes and twelve files, plus per-file/aggregate byte limits.

Passing baseline checks proves only that those checks already pass: it does not implement a requested new feature. Failed build/tests never count as development success. Model prose cannot prove command output. Historical failures remain in the journal; final commands establish repair success. Ordinary inference-only tasks still establish non-empty output, not semantic correctness, and their verifier states that limitation.

Trusted project scripts can execute arbitrary code under Termux's authority and may access network or other files. Workspace guards are **not an OS sandbox for hostile code**. Realpath/hash checks cannot eliminate adversarial concurrent-renaming races. Multi-file edits are not transactional; uncertain/partial writes request review. Rollback primitives refuse stale state. There is no automatic restart-time rollback or replay of uncertain mutations. See `docs/DEVELOPMENT_ENGINE.md`.

## Context and offline skills

ContextStore now supports original-evidence hashes, bounded compaction, checkpoint references, objective/task isolation, deterministic indexes and stale-source invalidation. App-readable project indexes hash complete files, including equal-size/equal-mtime changes. Original evidence remains separate from summaries. Termux source work uses fresh remote indexes/hashes rather than attempting to read another app's private directory. Legacy source-unattributed context cannot be retroactively freshness-qualified.

Five builtin skill manifests cover inspection, repair, build/test, Git inspection and filesystem research. They declare tool subsets, permissions, preconditions, constraints, failure rules and compatibility. Task procedures are validated against host grants and recorded as `skill_scope` events. The fixed Git probe performs status/history; diffs use recorded checkpoints. Untrusted manifest validation has a read-only ceiling and cannot register tools or claim builtin trust. This is a tested foundation, not an executable plugin marketplace. Manifest declarations cannot create network isolation or bypass tool policy. See `docs/CONTEXT_AND_SKILLS.md`.

## Memory and lifecycle

Diagnostics distinguish ActivityManager physical/available RAM, Runtime managed heap use/max, Android heap class, Debug native allocation and PSS, and optional system swap/reclaimable figures. Disk size, weight residency, estimated FP16 KV and graph reserve are different values. Other-process memory and Android 17's per-app native ceiling cannot be inferred precisely. Historical exit diagnostics recognize retained `MemoryLimiter:AnonSwap` descriptions with `REASON_OTHER`.

Native admission accounts conservatively for weights, KV and graph reserve plus a configurable 256–2048 MB minimum margin, available-memory reserve and Android threshold. Low-memory state refuses loading. Resource policy reduces concurrency for heat, battery and memory; service heartbeats pause severe pressure, and incomplete runs unload native state. These are safeguards, not guarantees against an OS kill.

The old `dataSync` classification is removed. User-started bounded compute declares `specialUse` with an honest manifest subtype. **Play review is required; approval is not guaranteed.** The 30-minute deadline is an application budget, not an invented platform specialUse timeout. Startup rejection, both timeout callbacks, cancellation, uncertain checkpoints and stop controls are handled. Lease-preserving transactions prevent stale engine copies overwriting heartbeat ownership. Restart requires review and never automatically repeats uncertain effects.

Termux wait cancellation does not guarantee immediate child termination. Coreutils timeout and project prerequisites must exist. Discovery supplies the observed proot-distro executable via IPC, avoiding invalid cross-app file probes. Debian permissions, external-app property, callbacks and actual execution remain device-verification items.

## API 37 and permanent engineering standards

Package `com.nyx.ai` retains its identity, versionName `0.6.0`, versionCode `6`, compile/target API `37`, min API `26`. The toolchain is AGP 9.1.1, Gradle 9.3.1, Kotlin/Compose compiler 2.2.10, KSP 2.3.6, Room 2.8.5, NDK 28.2.13676358 and CMake 3.22.1. Reproduced older KSP/Room failures were resolved without an SDK fallback or permanent source-set suppression. Room schema remains v3 with additive registered migrations.

Python and Gradle guards enforce the dated policy and reject target below its floor. Future releases must recheck official requirements and update guards together; a recorded policy cannot automatically enforce unknown future changes. `ENGINEERING_STANDARDS.md` requires compatibility tests rather than integer-only upgrades.

Edge-to-edge safe insets and adaptive Compose are retained. Optional LAN, notification and Termux permissions have contextual request buttons. Native inference requires neither Termux nor LAN access. JNI uses packaged System.loadLibrary code, not downloaded executable plugins. All four bundled native libraries have 16 KB LOAD alignment and the APK passes 16 KB ZIP checks; actual 16 KB runtime verification is still pending. Official sources and compatibility findings are in `docs/ANDROID_COMPATIBILITY.md` and `docs/BUILD_COMPATIBILITY_FIXES.md`.

## Remaining gaps and authorized boundary

Physical-device tests remain for SAF native import/load/stream/cancel/unload, Room/Keystore preservation, memory/thermal behavior, Android API26–37, GrapheneOS, fold/unfold/insets, permissions, notifications, background-start restrictions, Termux/Debian callbacks, timeouts and process recovery. The included instrumented real-model test assumption-skips if its model argument is omitted; that is not an inference pass.

Per-objective events measure request count, generated characters, elapsed time, retries, verification counts and sampled PSS peaks. Native runtime reports true last-generation tokens and load/generation duration. HTTP tokenizer-derived totals, exact per-objective token attribution, absolute peak memory, cross-model completion rates, model-switch benchmarks and practical 3B/4B/7B efficiency comparisons remain incomplete. No benchmark-backed acceleration or model ranking is advertised.

The directive ends mid-sentence under Priority Nine. This release prepares authorized source repair only: no installed self-replacement, silent APK installation, executable plugins, automated commits, repository push or public release was inferred or performed. Existing GitHub v0.5.0 remains untouched; v0.6.0 publication is separate.

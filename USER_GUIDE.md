# NYX User Guide — 0.6.1

## What changed in 0.6.1 — environment truth and preflight
- **Verified guests.** Debian (or any installed alias) is marked available only after a real command inside it succeeds. A nested-PRoot configuration is reported explicitly.
- **Capability table.** After discovery, **Refresh** shows each tool as verified, missing, incompatible, or permission-denied, with its path, version, and free storage.
- **Workspace verification.** Saving a path now runs a real existence and write check in the selected environment. Guest paths such as `/root/project` are valid for Debian; Android paths such as `/storage/emulated/0` are rejected there.
- **Preflight.** Before a development objective, tap **Run preflight**. It lists exact blockers for the provider, model, environment, workspace, tools, and memory. The objective will not start while a blocker remains.
- **Honest outcomes.** A passing baseline is not a repair. A feature objective needs a changed test and a green suite; otherwise the report says `GOAL_NOT_VERIFIED`.

## Run a native GGUF model

Native inference uses the phone's ARM64 CPU through bundled llama.cpp JNI. It needs **neither Termux nor network permission**. No model weights are bundled or downloaded automatically; choose a trusted, appropriately licensed GGUF yourself.

1. Open **Models → Use native GGUF → Import GGUF** and select a compatible instruct/chat model using Android's document picker. NYX validates the GGUF and copies it into app-private storage; it does not run directly from the original URI. Old URI-only GGUF registrations must be re-imported.
2. Inspect the model card: architecture, file size, training context, tokenizer/vocabulary, chat-template presence, and estimated KV/compute reserve. A model without a default chat template is not loadable. Import is defensive validation, not a sandbox for malicious model files.
3. In **Settings**, choose context length and CPU threads. The native minimum memory margin defaults to 512 MB and is adjustable from 256–2048 MB; the available-RAM margin and Android's low-memory threshold can require a larger reserve. Keep context within the model's training limit. Estimates are not measured resident memory or a promise against an OS kill.
4. Choose **Select & load**. Read the runtime status/error rather than assuming every valid GGUF architecture or size can load. Admission may refuse an unsupported memory estimate or insufficient available RAM. Try a smaller model/context instead of repeatedly retrying an oversized load.
5. Start chat, an agent, workflow, or objective with the selected provider/model. **Stop / unload** cancels current native work, waits for safe JNI cleanup, and releases the session.
6. Use **Delete private model copy** and confirm **Delete copy** to remove NYX's copy and metadata. Your original document is not modified. Uninstalling/clearing app data also removes the private copies.

The runtime panel reports the last generation's **true selected token count** and last load/generation **milliseconds**. Unknown/incomplete counts are not successful token totals. Global inference metrics report streamed chunks and generated characters separately; neither is a tokenizer count. These observations are not a guaranteed Android throughput figure.

Only one native session exists. Native generation is bounded, has no silent history truncation/context sliding, and uses no GPU, NPU, Tensor/TPU, multimodal, or embedding acceleration. A host JNI smoke test produced real tokens, but Android-runtime inference and physical-device behavior have not been tested here. See [Native inference](docs/NATIVE_INFERENCE.md) and the [explicit device procedure](BUILD.md#explicit-native-device-procedure).

## Connect to an optional inference server

For Ollama, configure `http://127.0.0.1:11434` and start the server yourself. The expected APIs are `GET /api/tags` and `POST /api/chat`; choose **Refresh server models** after setup. OpenAI-compatible servers use a base URL ending in `/v1`, `GET /models`, and streaming `POST /chat/completions`, with an optional Bearer key. Saving an HTTP(S) server URL in Settings explicitly selects server inference instead of native GGUF.

Remote endpoints require consent. Keys are masked, encrypted under Android Keystore, stored in app-private no-backup files, and never put in chat or endpoint URLs. Remote credential transport requires HTTPS; loopback HTTP is permitted for local development. Missing saved credentials require re-entry, not an unauthenticated fallback. Settings → About reads the installed package version.

Chat, agents, workflows, and objectives use the same strict factory and a global inference gate: **one active inference request**, with up to **24 waiting requests**. Tool workers can run concurrently, but models do not. Provider failure, missing models, revoked consent, or denied permission never silently sends a prompt to another provider.

An Ollama or `/v1` endpoint on a LAN, a `.local` name, or emulator host alias `10.0.2.2` is not labeled on-device merely because it is privately addressed. Native CPU execution and a genuine same-device loopback server are distinct from LAN/host servers.

## Optional permissions

- **Settings → Allow execution notifications** requests notification permission on Android 13+. Objective execution still uses foreground-service notification semantics; allow notifications if you want visible run/Pause/Cancel controls.
- **Settings → Allow LAN inference (Android 17)** requests optional `ACCESS_LOCAL_NETWORK` on Android 17/API 37 for a LAN server. Native GGUF and same-profile loopback do not need this permission. Android 17 cross-profile loopback is separately restricted.
- **Autonomy → Terminal → Grant Termux command permission** requests `com.termux.permission.RUN_COMMAND`. Grant it only if using the Termux bridge; native inference does not need it.

Denied permissions leave the associated optional feature unavailable. Permission-button presence is implementation, not evidence that its prompt behavior was tested on a device. See [Android compatibility](docs/ANDROID_COMPATIBILITY.md).

## Terminal and approved workspaces

NYX executes real commands through Termux's `RUN_COMMAND` service and reads real callback results, not a simulated shell. Setup is user-managed:

1. Install Termux from a trusted source.
2. Add `allow-external-apps=true` to `~/.termux/termux.properties` and restart Termux.
3. Grant NYX the Termux command permission using the explicit button or Android settings.
4. Open **Autonomy → Terminal → Detect environment**. Discovery observes actual `HOME`, `PREFIX`, shell, `proot-distro`, `timeout`, installed distributions, and tools through `RUN_COMMAND`.
5. If you want Debian, install/configure it yourself (for example, `proot-distro install debian`) and select **Debian**. NYX lists installed aliases, then runs a real `/bin/sh` command inside each one. A guest appears under **Verified guests** only after it returns the expected marker plus its HOME, OS, uid, and tool paths. If Termux is configured to auto-login to a guest, NYX reports the nested-PRoot state with the remedy instead of claiming the distribution is missing.
6. For objective tools only, explicitly enable **Allow terminal tools for objectives**, enter an absolute, normalized workspace path in the chosen environment, and click **Save workspace**. Resolve `~/...` using discovery rather than treating it as an app-private path.

The manual terminal panel classifies user-entered commands and requires explicit approval of the exact command before execution, including read-only commands. Privileged/protected-Android/destructive operations are blocked. This manual route does **not** authorize arbitrary model shell commands, and a risk classifier is not a shell sandbox.

Objective workspace hooks support bounded file inspection/search, expected-hash writes/checkpoints, project detection, and detected fixed build/test commands. Arbitrary model `terminal_run` is always denied; models cannot override build/test command strings or timeouts. Remote `realpath` checks reject symlink/path escapes, and exact hashes protect supported edits. Standalone agents and sequential workflows keep their app-private tools; enabling objectives' workspace is not a general Termux grant to those features.

**Only approve trusted projects.** Gradle wrappers, npm/Make scripts, imports/tests, and other project build hooks can execute arbitrary code with Termux/PRoot user permissions, read outside the workspace, or use the network. Supported offline flags are best effort, not an OS sandbox or firewall. Existing scripts are trusted code; do not approve an untrusted repository merely because path checks passed.

Hard process timeouts require coreutils `timeout` and the discovery/write utilities. Development refuses an unavailable required environment rather than installing tools. Other terminal waits can end without proof that a child stopped if hard timeout is unavailable. Termux/PRoot execution remains device-unverified.

## How objectives execute

Objectives run in a visible `specialUse` foreground service, with Pause/Cancel controls, leases, heartbeats, and saved Room state. This is an honest compute-service declaration with a manifest explanation; it requires Play review and does not promise store approval or unlimited background life. NYX's own run deadline is **30 minutes**, not a platform-imposed specialUse timeout.

1. Choose the provider/model and an agent whose enabled tools fit your objective. Authorize a trusted workspace only if the work needs it.
2. Open **Autonomy**, describe the objective and its verification requirements, and select **Plan and execute**.
3. With **Use verified project repair loop** unchecked, NYX requests and validates a JSON task DAG: bounded task fields, IDs, dependencies, and cycles are checked before execution. With it checked, creation instead persists a deterministic development task as described below. Model output cannot grant workspace access, permission, a different provider, or new tools.
4. Tasks run in dependency order, concurrently where the RAM/thermal/battery/storage resource policy allows. Runtime skill validation selects a bounded existing tool subset; inference stays globally serial. Review statuses, trace, actual command output, evidence, and artifacts.
5. Use **Pause** or **Cancel** when needed. Closing the UI does not itself cancel the run, but Android can still stop the process. Review uncertain tasks and use **Review & resume** only after checking whether another attempt is safe.

A task completes only when its local evidence passes: real exit code, required/forbidden output, file existence/content, or checksum. Tool tasks require declared evidence; model prose cannot substitute for proof. Inference-only output may carry weak evidence, not independent semantic verification. Failed/denied actions cannot be marked successful; mutating or uncertain work requires human review rather than automatic replay. Plans remain capped at 40 tasks; there is no dynamic replanning or mid-process resumption after a kill.

## Bounded development repair

A `development` task invokes **DevelopmentOrchestrator**, not a shell-enabled coding agent. The objective uses trusted workspace/environment and selected backend/model/options snapshots; a model cannot change those authorities. Settings provide defaults of **3 repair iterations, 4 inference calls, and 5 minutes**, with UI ceilings of **5 iterations, 6 calls, 10 minutes, and 12 file replacements**. Re-editing a file consumes another replacement. Budgets are additional bounds, not a promised workload capacity.

To start that worker explicitly:

1. Complete **Autonomy → Terminal → Detect environment**, select **Host** or **Debian**, enable **Allow terminal tools for objectives**, enter the trusted project path, and click **Save workspace**.
2. Select your provider/model and set the repair limits in **Settings → Bounded development & memory**.
3. Open **Autonomy → Objectives**, enter the failing-project repair goal, and check **Use verified project repair loop**. This checkbox is enabled only with terminal-workspace authorization; readiness and the saved root are still validated at execution.
4. Click **Plan and execute**. This path bypasses model DAG classification and persists a deterministic development task that calls the actual orchestrator. Read its phase events, actual build/test exits, changed files, and checkpoint/diff evidence.

The worker discovers installed tools/project markers, freshly reads bounded remote source snapshots, and runs detected fixed build and test commands. If the baseline fails with real process results, it may request strict JSON complete-file replacements with exact expected SHA-256 hashes. Original backups are verified before changes; each supported replacement is atomic, with checkpoint IDs and observed diffs. The worker then repeats the actual build/test and stops within its budget or on unsafe/repeated/invalid changes. Partial multi-file edits are not a transaction and can require review.

**A passing baseline means the existing checks already passed and no repair was attempted. It does not prove a newly requested feature was delivered.** A failed post-edit build/test never completes the task. Model output, command evidence, hashes/diffs, and checkpoint details are recorded separately. Missing toolchains/SDK/pytest, unsupported projects, uncertain results, or invalid patches stop visibly; there is no automatic dependency installation, commit/push, publication, or privilege escalation. Backups are under the workspace's `.nyx-checkpoints`; rollback is explicit, stale-safe, and not automatically replayed after restart. See [Development engine](docs/DEVELOPMENT_ENGINE.md).

## Agents, workflows, skills, and context

Native providers do not support function calling. Agent actions use strict JSON text, with an allowlisted tool name and schema-validated arguments, or a final-answer object. One invalid-response repair is allowed; another invalid response stops without executing an action. No raw shell is extracted from prose. Small-model JSON/action reliability is not established by a factual-answer smoke test.

Chat history persists locally; streamed replies can be stopped, copied, or regenerated. Agents can be edited but use only authorized tools. Sequential workflows pass earlier output to later agents. User-selected UTF-8 text/Markdown/CSV/JSON/log imports retain the 256 KB file and 60,000-character combined task limits. Imported task text is sent to the explicitly selected provider when the agent runs. PDF/office parsing, OCR, and a separate document library are not included.

The five builtin skill manifests cover workspace inspection, source repair, build/test, Git inspection declarations, and local filesystem research. Validation cannot invent permissions/tools. Objective runtime integration restricts the task's workspace tools to a validated subset and records a **`skill_scope`** event. The Git builtin uses **`repo_status`**, **`repo_diff`**, and **`file_read`**, not `terminal_run`: status/history is a fixed read-only probe with no command/options input, and diff compares recorded NYX checkpoints. This does not enable arbitrary terminal, commits, fetches, or pushes. Imported-manifest support is a read-only validation foundation, not an executable marketplace or plugin installer.

Context storage retains bounded evidence/history and checkpoints. Deterministic freshness, full-content source hashes, scoped app-readable project indexing, and evidence-preserving compaction are available foundations, not an automatically integrated full-project/full-conversation context feature. Development obtains fresh remote source reads independently: it does not make Termux files app-readable or invoke the app-readable index for them. See [Context and skills](docs/CONTEXT_AND_SKILLS.md).

## Cancellation, durability, and device limits

Pause/cancel cancels waits and native coroutines. Native cancellation signals the JNI abort flag and joins active work before freeing/reusing its session. Cancellation is cooperative: one upstream parsing/tokenization call can still take time. A Termux child may linger until its hard timeout, including around a file replacement. Do not start another worker or retry automatically while its effects are uncertain. Interrupted active tasks persist as **`needs_review`**; process restart does not automatically replay them.

Room's retained additive migrations preserve existing chat/endpoint/agent/workflow/objective records. Objectives, tasks, events, artifacts, command history, evidence, and context records remain local. A foreground service is not an exemption from Android memory/battery restrictions. System available RAM, Java heap, native model/KV/graph allocations, and Android's per-app limits are different quantities; estimates and point-in-time diagnostics cannot guarantee process survival.

Current source uses min API 26, compile/target API 37, and ARM64-only JNI. The coordinating integrated build succeeded with **350 JVM tests, zero failures/errors/skips**, and assembled debug app/instrumentation-test APKs. No Android-runtime/physical-device inference, Pixel/GrapheneOS/Fold, Termux/PRoot, notification/LAN prompt, lifecycle, thermal, or 16 KB runtime validation is claimed here. See [TEST_RESULTS.md](TEST_RESULTS.md) for exact results and separate final clean-source/archive checks. The published GitHub release remains v0.6.0, separate from local v0.6.1 artifacts. No autonomous installation, self-update, or publication is implied.

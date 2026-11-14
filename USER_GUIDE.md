# NYX User Guide — v0.9.0 Project Bedrock candidate

## v0.9.0 — Project Bedrock (unpublished candidate)
Bedrock adds a default-off, original-project-bound development → selected-file flow through the existing engine, a structured terminal catalog with controlled environment/link guards, exact-request capability evidence and checkpoint-stable continuation IDs, mandatory process/artifact checks, conservative persistent-host token fencing, and stricter store/network/credential boundaries. Actual Linux host terminal (99) and production non-UI combined (11) regressions passed; Android/Room/Binder/provider acceptance and the final clean whole-app build remain separate pending gates. See [release notes](RELEASE_NOTES_v0.9.0.md), [implementation/evidence report](V090_IMPLEMENTATION_REPORT.md) and [security dispositions](BEDROCK_SECURITY_AUDIT.md).


Current source is `com.nyx.ai`, versionCode 23 / versionName 0.9.0. v0.8.9 remains the public download. Historical version sections and their reported tests below are retained as history, not candidate acceptance evidence. CI is prepared but has not run on GitHub; real-provider/model, Android permission/lifecycle, upgrade, and save/share behavior require the documented device procedure. Only an APK verified against the established debug certificate is an in-place upgrade candidate; never install an ephemeral CI-signed verification APK over your data. Production signing is deferred pending an explicit migration/distribution decision. See [build and verification requirements](BUILD.md) and [CI contract](docs/CI_VERIFICATION.md).

## v0.8.9 — Project Workbench (published)

Open **Autonomy → Workbench**. Select your existing model and agent, describe a finished file, choose its simple output filename and format, and check the objective-specific private artifact approval. Optionally select one text/Markdown/CSV/JSON reference with the document picker. Exact HTTPS research URLs require their separate checkbox and the existing autonomy/network policy; every selected source must be retrieved before a research request completes. Sources are untrusted inputs, not instructions.

After work completes, select the objective to reopen its retained sources and verified files. Preview is inert selectable text (including HTML source); **Save as…** opens Android's file destination picker; **Share** gives a recipient a digest-checked, artifact-only read URI. Canceling a picker exports nothing. A retry preserves the original approval, and conflicting filenames fail rather than overwrite. PDF and web search/JavaScript browsing are unsupported; a model's file-exists claim is not evidence. See [device procedure and limits](V089_IMPLEMENTATION_REPORT.md).

## v0.8.8 — Project Insight (published)

NYX now chooses a planning workload instead of always requesting a whole task graph.

- **Direct** — a short, explicitly text-only objective (for example "Explain photosynthesis" or "Write a short poem about rain") becomes one deterministic inference task and starts without a planning call. Any mention of files, commands, builds, tests, installs, browsing or publishing disables this shortcut.
- **Incremental** — complex, tool-oriented, slow or unproven models get at most two validated steps. After every step passes local evidence, NYX asks for one more step, up to six continuations. The model cannot assign identifiers, dependencies or permissions.
- **Full** — only a complex objective with demonstrated schema output, measured throughput of at least 6 tokens/s and a 4K+ context requests one full graph.
- **Guided** — a model without demonstrated constrained JSON can return one validated `NEXT inference: …` line per step. Guided mode cannot request terminal, filesystem or development work because plain text cannot carry the local evidence those workers require.

The Models diagnostic now records the measured capability profile and shows it with the probe summary. Profiles expire after 24 hours or when provider, model, context or thread configuration changes. Reasoning output appears as progress in the Live Worker Console; it is never executed or treated as the plan.

## v0.8.7 — Project Breakthrough (published)

Use the expandable editor for long objective/chat/terminal drafts. Done/Back return text without submitting. Nonsecret drafts use saved state through configuration changes; secret fields do not. Keyboard/foldable hardware testing is pending.

Settings execution patience is distinct from energy mode. Standard30min and Patient60min retain finite first-text/inactivity/request limits. Extended120min and Custom cumulative minutes require explicit confirmation; they do not promise uninterrupted service execution. New objectives retain snapshots; ordinary chat and the short model diagnostic retain their prior limits. Keep exact command/package/workspace approvals.

Mission Control and Terminal show operation contexts, isolated bounded details, requested capacity and uncertain reservations. Capacity3 is supported when resources and conflicts allow, not mandatory; Balanced may admit2. Never release an uncertain reservation until independently confirming the operation stopped. Release does not kill/replay it or prove success. See [device procedure and limits](V087_IMPLEMENTATION_REPORT.md). Official download: [v0.8.7](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.7).

## v0.8.6 — Project Ignition (unpublished candidate)

The candidate uses short validated plans for straightforward objectives; it reports actual streamed planning progress or a bounded stall rather than repeatedly saying it is waiting. Queue admission/model-loading/prompt timing remain unknown when the provider does not expose them.

Open **Models → Check selected model** to run one explicit small diagnostic. It can load the selected model; no tools or model downloads are requested. The 30-second cancellation budget includes inventory and queue/loading. Reported token rates use provider counters only; unknown values are not estimated. Stop other work first.

For standalone Kotlin creation, request root-level source and TestMain.kt behavioral tests in the approved workspace, not an Android application. Kotlin CLI requires Java/kotlinc, not Android SDK. Explicit package approvals and guest/workspace verification remain required. Consult [V086_IMPLEMENTATION_REPORT.md](V086_IMPLEMENTATION_REPORT.md) for the Pixel validation procedure and limitations. v0.8.5 remains the latest public release.

> v0.8.5 “Project Sovereign” is the latest published release. It adds adaptive deliberation budgets, durable working state, task-scoped tools, a false-success guard, bounded self-repair, and a two-hour checkpointed objective window. The JVM suite and clean Android build are verified; the Sovereign runtime behavior has not yet been validated on a physical device or against a real provider/model.

## v0.8.5 — Project Sovereign (published)

The Autonomy screen and Live Worker Console now expose the execution profile and short decision/observation summaries for each task. These are deliberately concise: NYX does not request or display a private chain-of-thought transcript. The profile shows the selected reasoning depth, step budget, model-call budget, tool-call budget, and context budget.

Each objective task persists a compact **working state** in its checkpoint: objective, requirements, observed facts, open errors, next action, evidence pointers, and completed steps. If the app process is restarted or a task is retried, the state is restored and included in the next bounded context. The checkpoint is a recovery aid, not a guarantee that an interrupted side effect did not happen; uncertain mutating work still requires **Review & resume**.

Tools are scoped to the task kind. Inference tasks may read and use non-mutating tools but cannot run shell commands or write files. Filesystem tasks may edit files but cannot run shell or project commands. Verification tasks may inspect files and run the fixed test command, but cannot write files or run arbitrary shell. Terminal and development tasks receive the full approved capability set inside the authorized workspace. A capability that is not available on the run is never shown to the model as an available tool.

For tasks that require tool evidence, a final answer that claims completed work without a successful tool observation is rejected once. If the model repeats the claim, the result is marked unverified and objective verification fails. A clear blocker is not treated as a completion claim. Malformed structured actions receive at most two repair attempts; repeated identical failing actions and consecutive tool failures stop the task instead of consuming the entire budget.

The foreground objective watchdog now allows up to **two hours**. Thermal, memory, battery, lease, pause, cancel, and explicit-review rules are unchanged. The watchdog is an upper bound, not a promise that Android will keep the process alive for that long.

The release is available from the [official GitHub release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.5). See [RELEASE_NOTES_v0.8.5.md](RELEASE_NOTES_v0.8.5.md) and [V085_IMPLEMENTATION_REPORT.md](V085_IMPLEMENTATION_REPORT.md) for scope and verification boundaries.

## v0.8.4 — Project Endurance (published)

Open **More → Performance** to inspect the device capabilities NYX actually observed, choose Eco, Balanced, Performance, or Adaptive, and see the effective native thread count, autonomous worker cap, stream cadence, and idle-release behavior. Thermal and low-battery safety ceilings apply to every mode. Adaptive mode chooses from RAM, low-RAM state, core count, thermal status, and battery state; recovery from a derated state waits for two consecutive samples.

Adaptive threading is used before the next native model load. A model that is already resident keeps its current thread count so a chat turn cannot silently reload it. Moving the **CPU threads** slider in Settings switches to manual control; re-enable adaptive threading on the Performance screen when you want NYX to choose. **Apply to native session** reloads the model only when no generation, agent, workflow, or objective is running.

**Run calibration** is opt-in. It requires a loaded native GGUF model, a cool device, sufficient battery (or charging), and no running objective. It tests at most three thread counts, generates at most eight tokens each, and stops within two minutes. Results are stored on-device and are tied to the model, runtime version, and device; changing any of them makes the result inapplicable rather than silently reusing it. End-to-end tokens/s includes prompt processing and is not a decode-only score.

In **Eco** mode, NYX releases the resident native model after ten idle minutes when no chat generation, agent, workflow, or objective is active. The next message reloads it normally. Balanced and Performance keep the model resident.

The Performance screen reports NNAPI/Vulkan feature presence as capability evidence only. No GPU or NPU GGUF backend is bundled or claimed; the ARM64 CPU path remains the supported runtime. SoC family labels are display/thread heuristics, not accelerator proof.

The release is available from the [official GitHub release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.4). The Performance dashboard render on a Pixel 10 Pro Fold is owner-reported UI evidence, not a native inference, calibration, KV-reuse, or battery benchmark. See [release notes](RELEASE_NOTES_v0.8.4.md) and [implementation report](V084_IMPLEMENTATION_REPORT.md).

## v0.8.3 — Project Mission Control (published)

The candidate reorganizes **Autonomy** around the selected/active objective. The mission card shows the stored objective state and, when its task graph is loaded, a completion fraction based only on persisted completed tasks. The current phase and active task appear only when available from execution state. Pause and Cancel use the existing service actions. **Review & resume** opens the existing confirmation explaining why an interrupted or failed action must be inspected before a retry is approved.

The readiness panel reports actual provider/model connection or native model-load state, the selected Termux host or verified Debian/PRoot guest, the current workspace verification/authorization, and objective state. “Not checked,” “not verified,” and “tools disabled” remain explicit states; it does not synthesize a readiness percentage. Selecting an older mission loads its own persisted task graph. Expand a task to inspect prerequisites, instructions, success criteria, result/error, and bounded evidence.

The **Terminal** view uses separate Termux Host and Debian PRoot target controls, followed by the existing environment probe, verified-guest selection, capability evidence, workspace controls, and exact-command approval flow. These are presentation changes; they do not widen command authority or bypass existing checks. The Live Worker Console keeps its history and controls with clearer status text and larger copy/share targets. This release is available from the [official GitHub release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.3). Physical-device screen rendering and touch interaction remain unverified. See [release notes](RELEASE_NOTES_v0.8.3.md) and [implementation report](V083_IMPLEMENTATION_REPORT.md).

## What changed in v0.8.2 — Project Unshackle

Dependency preparation follows the persisted objective task graph. NYX first validates and stores the planned tasks, then checks the selected toolchain immediately before terminal/development tasks run. The same check happens before a reviewed objective resumes, so a missing approved dependency can be handled without discarding the existing task graph or checkpoints.

Ordinary shell automation, inference-only objectives, verification-only tasks, and explicit negations such as “do not use Gradle or the Android SDK” do not trigger unrelated toolchain installation. Affirmative Android app/APK development tasks still select the Android toolchain and go through the existing package authorization and verification path. Git is optional for local work; Debian JDK discovery is skipped for project kinds that do not need Java.

The release retains application ID `com.nyx.ai`, versionCode 15/versionName 0.8.2, the existing workspace and authorization boundaries, Termux/PRoot environment selection, task checkpoints, and conservative recovery. It does not alter bundled llama.cpp source. See [v0.8.2 release notes](RELEASE_NOTES_v0.8.2.md) and [implementation report](V082_IMPLEMENTATION_REPORT.md). Device execution remains unverified.

## What changed in 0.8.1 — Project Clear Signal

**The Live Worker Console is now run-scoped.** It opens on the selected or currently running objective and its current execution run. Earlier runs are available under **History**; they are not merged into the live view. Each run is identified by objective and run/session identity, and legacy events from earlier builds remain visible as a separate legacy run. The dialog uses the full screen with safe display insets.

**Operations replace an undifferentiated event stream.** Related task, tool, verification, planning, and recovery events are grouped into cards with observed status, event count, and elapsed time measured from real timestamps. Tap a card to see its chronological detail, including command input and output. **Raw events** switches to the complete timestamped stream. ANSI/OSC terminal sequences are stripped, credential-shaped values are redacted, and copy/share use a bounded diagnostic export.

**Scrolling respects your position.** A new event auto-scrolls only when you are already near the bottom. Switching runs starts at that run's newest item. **Clear history** asks for confirmation, preserves the selected/current and active runs, and removes only console diagnostics; objective records, task evidence, and workspace files are untouched.

**Planning is bounded and recoverable.** A planning attempt has a hard 120-second deadline and emits progress events while waiting. A completed response that fails strict JSON or schema validation gets one structured-output repair with the validator's reason. Timeouts, interruptions, empty responses, context exhaustion, and provider errors are reported as distinct failure categories. A failed plan stops in `needs_attention` with a sanitized diagnostic; it does not silently restart. Existing tasks, checkpoints, and dependency evidence remain intact, and resuming still requires explicit review.

**Model shell syntax is more strictly confined.** Model-directed `terminal_run` now rejects output/input redirection, pipes, compound commands, newlines, command substitution, unquoted variable expansion, and indirect execution such as `sh -c`, `eval`, `xargs`, or `find -exec`. Quoted metacharacters remain ordinary arguments, fixed build/test commands are unchanged, and approved dependency installation does not use this model-directed path. The real command still runs in Termux or Debian/PRoot; this is a policy boundary, not an OS sandbox.

**Limits.** No physical Android device was available for this build. The console layout, scrolling, history selection, clear-history behavior, provider planning timeout/repair, and shell behavior are JVM/build verified only. The client-side deadline does not guarantee that a provider stops generation immediately. See [docs/V081_IMPLEMENTATION_STATUS.md](docs/V081_IMPLEMENTATION_STATUS.md) for the device checklist.

## What changed in 0.8.0 — Project Autonomous Bootstrap (historical)


**Environment preparation is now part of the objective.** Before a task runs, the objective foreground service re-discovers the current Termux/PRoot state and reconciles it with the objective's stored environment. A current verified guest clears a stale historical error; an unavailable or diverted environment records a diagnostic and stops before any task executes. `DependencyBootstrap` probes the guest, builds the existing trusted dependency plan, installs only approved candidates, re-probes every required executable, and then continues the original objective with its task graph and checkpoints intact.

**Debian 13 and Deb822 repositories are understood.** Open **Autonomy → Terminal → Prepare Kotlin env** after selecting Debian. The resolver reads both traditional `*.list` files and Debian Deb822 `*.sources` stanzas. It counts a stanza only when `Types` contains `deb`, `Enabled` is not `no`, and both `URIs` and `Suites` are present. It then uses `apt-cache policy` evidence to choose a real candidate; on Debian 13 Trixie this is normally `openjdk-21-jdk-headless`. A failed or interrupted install stops with a visible `needs_attention` diagnostic rather than retrying blindly.

**Model terminal commands are policy-gated.** When terminal tools are enabled, `terminal_run` must stay inside the verified workspace or a safe subdirectory, avoid credential and private-data paths, respect a bounded timeout, and pass the read-only or mutating command policy. Publishing, APK installation, system changes, private-data transfers, credential disclosure, root escalation, and destructive commands remain outside ordinary model authority. This is a policy boundary, not a security sandbox.

**The Live Worker Console is expandable.** Open the live preview in Chat or the **Live Activity** card in Autonomy. It shows the current objective, phase, active task, status, and timestamped planning, task, tool, terminal, installation, file, verification, recovery, and failure events. Tap an event to expand bounded tool input/output. Pause, Resume, and Cancel use the existing service controls. Copy or share a bounded, redacted diagnostic log; no hidden model reasoning or credentials are included.

**Workspaces are reused and cleaned up safely.** On startup, NYX re-probes the saved workspace and reuses it when the canonical path matches. It does not silently create a numbered replacement. The Terminal screen has **Manage Workspaces**, showing verified direct children of the managed parent with exact paths and **Use**, **Delete**, and **Delete all test workspaces** actions. Deletion requires an exact path-list confirmation and refuses roots, symlinks, traversal, non-canonical paths, paths outside the managed parent, and workspaces belonging to active objectives. The parent root, Ollama models, Debian installation, and unrelated files are never targeted.

**Limits.** The fixed Debian installation and objective continuation have not been re-run on a physical device. The new console rendering, share flow, workspace deletion, and startup reuse are covered by JVM tests and guarded command generation, not device execution. End-to-end calculator verification remains unfinished.

## What changed in 0.7.9 — Project Persistence

**Guest Ollama now has a durable owner.** When the selected environment is Debian/PRoot, **Start**, **Stop**, and **Restart** run through a non-exported `dataSync` foreground service. Android shows a low-priority “NYX · Managed Ollama” notification while the managed server is active. It reports Starting, Ready, Recovering, Stopped, or Error and includes **View Activity**, **Stop Worker**, and, when Termux is installed, **Open Termux** actions. This keeps the NYX process from being frozen while it waits for a PRoot session to create the guest server. Host-mode Ollama still uses the direct lifecycle path.

**One session, one server.** NYX probes the installed `proot-distro` for `login --detach`, `ps`, and `kill`. If all three are present, it creates one detached PRoot session and records the session PID plus its kernel start-time token. If the installed version lacks any of those commands, NYX keeps one foreground PRoot login alive instead. It does not claim to have detached when it has not. The guest server itself always runs in the foreground of that session, so PRoot’s normal `--kill-on-exit` behavior cannot silently remove it.

**Stop is verified, not optimistic.** Stop first terminates the verified guest Ollama PID, then asks `proot-distro` to end the recorded session and re-checks `/proc/<pid>`. If the session survives, the card shows an error and keeps **Stop** available instead of claiming the server stopped. An orphaned detached session also shows Stop even if the guest PID record is missing. A server that NYX did not start is still adopted read-only and never killed. The notification’s **Stop Worker** action uses the same verified stop path.

**Startup states are specific.** The card distinguishes a dead process, a live guest process that has not opened its API, a guest API that Android cannot reach, a port conflict, and READY. If a verified session is already alive, Start refuses to create a second one. Check the bounded log before restarting; a port conflict is never resolved by killing an unknown process.

**Chat can start the runtime.** If the selected endpoint is loopback and Chat is disconnected, sending a message starts the managed runtime, waits for readiness, refreshes the model inventory, and then continues the original request. A failed startup leaves the message unsent and shows the bounded reason.

**Limits.** This build is device-untested. Actual `proot-distro ps` output, Android 14+ `dataSync` behavior, Termux `RUN_COMMAND` lifetime, and guest-to-Android API reachability must be validated on a physical device. If Android kills the NYX process during the foreground fallback, the guest PID record may remain until the next verified stop or refresh.

## What changed in 0.7.7 — Project Liberation

**Debian-hosted Ollama.** Open **Models** and tap **Detect**. NYX now probes both the isolated Termux host and every Debian/PRoot guest that environment discovery verified. A Debian installation at `/usr/local/bin/ollama` is recognized even when `ollama --version` begins with a “could not connect” warning; that warning means the server is stopped, not missing. The card shows the selected environment, version, architecture, and probed candidates. **Start** launches `ollama serve` in that environment through the existing PRoot bridge, waits for the API, and reloads the existing model list. **Stop** and **Restart** act only on a verified NYX-owned PID; an adopted server is never killed.

**Repeated Full-Autonomy projects.** In Full mode, **Create workspace** is authorized against the approved workspace root, not the temporary project name. You can create multiple independent projects under the same root without seeing `full_elevated_grant_required`. Manual mode still shows an approval card; Cancel creates nothing. Existing folders are never reused.

**Automatic objective reconnect.** If you create or resume an objective while the loopback Ollama server is stopped, NYX starts the managed server, waits for readiness, refreshes the model list, and continues planning only after Chat reconnects.

## What changed in 0.7.5 — Project Continuum

**Managed local Ollama.** Open **Models**. The managed-server card detects Ollama on the Termux host and in verified Debian/PRoot guests, and offers Start, Stop, Restart, Detect, and Refresh. Starting uses an isolated host command or an explicit PRoot guest login, never your interactive shell profile, so a shell that automatically enters Debian cannot hide the selected target. NYX records the PID **and** the kernel start token; it refuses to signal a reused PID or a server it did not start. If the server was already running when NYX found it, NYX adopts it read-only and leaves stopping it to you. Install Ollama in the environment you intend to use (`pkg install ollama` in Termux, or the guest’s supported package source in Debian); NYX never installs it silently and never uses root.

**Arsenal skills.** Open **Arsenal** to inspect the nine trusted procedures or import a JSON manifest. Only the three most relevant enabled procedures enter a task prompt, and only their declared tools join that task's allowlist. Imports are untrusted data: writes, terminal authority, unknown tools, duplicate/reserved IDs, and unsafe constraints are rejected. Use the procedure preview and execution log to see what was actually used.

**MCP and GitHub gateways.** Open **Gateways** to add a streamable-HTTP MCP server or a GitHub token gateway. Tokens are encrypted in Android Keystore-backed storage; the gateway record stores only an opaque reference. Each server has an allowlist and a per-run call budget. Check health and list tools before invoking one manually; GitHub writes are separate tools and remain policy-gated. Objective execution snapshots the allowed tools before a run, so a model cannot enable a server or widen its allowlist mid-task.

**Live worker console.** While an objective runs, Chat shows a compact live preview and Autonomy shows a Live Activity card. Tap either for the full-height console. It opens on the current objective and current execution run; use **History** to inspect earlier runs. Related events are grouped into operation cards with observed status and measured elapsed time. Tap an operation for its chronological detail, or switch to **Raw events** for the complete timestamped stream. Pause, Resume, Cancel, copy, and redacted export are available. New events auto-follow only when you are near the bottom. **Clear history** is confirmation-gated and preserves the current/active run plus objective records. The timeline is persisted locally and is evidence-backed, not simulated.

**Project memory.** Verified task outcomes, build instructions, and lessons are stored per workspace with evidence hashes. Relevant memory is injected into later prompts; stale or unverified claims are suppressed. Use the workspace diagnostics if you need to confirm what was recorded.

## What changed in 0.7.0 — Project Ascension

One autonomy policy now covers every consequential action. Open **Settings → Autonomy policy** and choose:

- **Manual** (default): reads and harmless local checks run; file changes, installs, network access, commits, and other consequential actions ask first.
- **Smart**: routine work inside the selected environment and workspace runs automatically; higher-risk work still asks.
- **Full**: routine and elevated work inside the granted scope runs without a prompt. Publishing, APK installation, system changes, private-data transfers, and credential disclosure still require explicit approval. Full never grants root.

Selecting **Full** shows a consent dialog. Choosing a mode creates revocable grants for the current environment and workspace only; **Revoke grants** returns NYX to Manual. **Emergency stop** blocks all new actions immediately and stays active across restarts until you resume. The policy audit journal records each decision locally.

**Environment preparation.** In **Autonomy → Terminal**, run **Detect environment**, then **Refresh** capabilities. The table now shows inherited Termux binaries inside Debian as `inherited`; they are not treated as Debian tools. **Prepare Kotlin env** builds a dependency plan from verified evidence. Manual mode shows an approval card; Smart and Full run the trusted plan automatically. Installing a package never comes from model text: the command is generated by the planner for the selected distribution.

**Workspace lifecycle.** Enter a project name and tap **Create workspace** only when you need a new folder. NYX creates it under `/root/nyx-workspaces` in Debian or `~/nyx-workspaces` in Termux, verifies it is writable, and selects it. Existing directories are never overwritten; a numeric suffix is added. On later starts the verified saved workspace is reused. **Manage Workspaces** lists managed paths and offers guarded deletion; the parent root is never deleted. In Full mode you can create multiple projects under the same root; in Manual mode an explicit approval card appears first.

**Network tools.** Objectives can call `fetch_url` for public HTTPS documentation. Manual mode refuses until you choose Smart or Full; redirects to private addresses, credentials in URLs, and non-text responses are refused. Fetched text is labelled untrusted and capped before it reaches a model. There is no arbitrary download-to-device tool in the objective surface.

**Role-based delegation.** Each objective task is assigned a specialist role (planning, research, coding, environment, testing, verification, documentation) and receives role-specific instructions. The supervisor pauses after repeated identical failures instead of retrying forever. This is not a separate process or model per role.

If an objective or terminal action is blocked after an Emergency Stop, resume autonomy first. If a package plan reports a blocker, read the blocker instead of retrying: an Android SDK build inside an ARM64 PRoot guest is not supported, and the guest needs a working `apt` before missing Debian tools can be installed.

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

For Ollama, configure `http://127.0.0.1:11434`. Use **Models → Detect → Start** to let NYX manage a host or verified Debian installation; the expected APIs are `GET /api/tags` and `POST /api/chat`. Choose **Refresh server models** after setup, or let detection reconnect automatically. OpenAI-compatible servers use a base URL ending in `/v1`, `GET /models`, and streaming `POST /chat/completions`, with an optional Bearer key. Saving an HTTP(S) server URL in Settings explicitly selects server inference instead of native GGUF.

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

Objective workspace hooks support bounded file inspection/search, expected-hash writes/checkpoints, project detection, and detected fixed build/test commands. Model `terminal_run` is policy-gated: only workspace-confined commands authorized by the active autonomy policy run, after the v0.8.1 rejection of redirection, chaining, substitution, and indirect execution; otherwise it is denied. Models cannot override build/test command strings or timeouts. Remote `realpath` checks reject symlink/path escapes, and exact hashes protect supported edits. Standalone agents and sequential workflows keep their app-private tools; enabling objectives' workspace is not a general Termux grant to those features.

**Only approve trusted projects.** Gradle wrappers, npm/Make scripts, imports/tests, and other project build hooks can execute arbitrary code with Termux/PRoot user permissions, read outside the workspace, or use the network. Supported offline flags are best effort, not an OS sandbox or firewall. Existing scripts are trusted code; do not approve an untrusted repository merely because path checks passed.

Hard process timeouts require coreutils `timeout` and the discovery/write utilities. Development refuses an unavailable required environment rather than installing tools. Other terminal waits can end without proof that a child stopped if hard timeout is unavailable. Termux/PRoot execution remains device-unverified.

## How objectives execute

Objectives run in a visible `specialUse` foreground service, with Pause/Cancel controls, leases, heartbeats, and saved Room state. This is an honest compute-service declaration with a manifest explanation; it requires Play review and does not promise store approval or unlimited background life. In the v0.8.5 candidate, NYX's own run deadline is **two hours**, not a platform-imposed specialUse timeout; thermal, memory, battery, lease, and user controls can still stop or pause the run earlier.

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

**Historical v0.6.1 record, not v0.9.0 acceptance:** that source used min API 26, compile/target API 37, and ARM64-only JNI. Its coordinating integrated build succeeded with **350 JVM tests, zero failures/errors/skips**, and assembled debug app/instrumentation-test APKs. No Android-runtime/physical-device inference, Pixel/GrapheneOS/Fold, Termux/PRoot, notification/LAN prompt, lifecycle, thermal, or 16 KB runtime validation was claimed. The then-public GitHub release was v0.6.0, separate from the local v0.6.1 artifacts. Current public downloads remain v0.8.9; the Bedrock candidate and its evidence are described at the start of this guide. No autonomous installation, self-update, or publication is implied.

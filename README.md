<div align="center">

<img src="brand/nyx_launcher.png" alt="NYX Eclipse AI" width="148" />

# NYX ECLIPSE AI

### Your Intelligence. Your Device. Your Rules.

**A free and open-source, local-first Android AI workstation** — on-device language models, autonomous objectives, real execution tools, and persistent agent workflows in one application.

[![Release](https://img.shields.io/github/v/release/ShamelesAbyss/Nyx?style=for-the-badge&label=release&color=8B5CF6&logo=github&logoColor=white)](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.7.5)
[![Android](https://img.shields.io/badge/Android-API%2037%20target-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#build-from-source)
[![min API](https://img.shields.io/badge/min%20API-26-8B5CF6?style=for-the-badge)](#requirements)

[![License](https://img.shields.io/github/license/ShamelesAbyss/Nyx?style=for-the-badge&color=8B5CF6)](LICENSE)
[![Open source](https://img.shields.io/badge/open%20source-yes-22C55E?style=for-the-badge&logo=opensourceinitiative&logoColor=white)](LICENSE)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-0EA5E9?style=for-the-badge&logo=android&logoColor=white)](#requirements)

</div>

---

> [!NOTE]
> **NYX v0.7.5 “Project Continuum” is the latest stable release.** It adds managed Termux Ollama lifecycle, Arsenal operational skills, MCP/GitHub gateways, durable project memory, a source symbol index, the Live Worker Console, and workspace/policy hardening. It is versionCode 9 / versionName 0.7.5, debug-signed, and passes 598 JVM tests. Physical-device validation of the new integrations remains pending.

## ⬇️ Download NYX v0.7.5 — Project Continuum

<div align="center">

### [**Get the latest release →**](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.7.5)

| Asset | What it is |
| :--- | :--- |
| **[NYX-v0.7.5.apk](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.7.5/NYX-v0.7.5.apk)** | The application. ARM64, debug-signed. |
| **[NYX-Source-v0.7.5.zip](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.7.5/NYX-Source-v0.7.5.zip)** | Complete source, tests, docs, licenses, and Gradle wrapper. |
| **[NYX-v0.7.5-androidTest.apk](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.7.5/NYX-v0.7.5-androidTest.apk)** | Instrumentation tests for device verification. |
| **[NYX-v0.7.5-SHA256SUMS.txt](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.7.5/NYX-v0.7.5-SHA256SUMS.txt)** | SHA-256 digests for every artifact. |

</div>

> [!IMPORTANT]
> **The APK is ARM64-only and debug-signed.** It is a development build, not a Play Store or production-signed release. Install it only on a 64-bit ARM Android device.
>
> Existing **v0.7.0** installations can update **in place** when the signing certificate matches — the v0.7.5 APK uses the same debug certificate as v0.7.0. Do not uninstall a data-bearing installation just to test an upgrade; back up anything important first.

> [!NOTE]
> **v0.7.5 “Project Continuum” adds operational continuity:** managed Termux Ollama lifecycle, nine Arsenal procedures, streamable-HTTP MCP and GitHub gateways, durable project memory, incremental Kotlin/Java source indexing, a persisted Live Worker Console, atomic workspace creation, and scoped policy grants. Physical-device execution of these new integrations is still pending.

---

## 🌒 What NYX actually is

NYX is not a chat wrapper around a hosted API. It is an Android application that can hold a long-running objective, break it into a dependency graph, run allowlisted tools on your device, and refuse to call the work finished until local evidence says it is.

The v0.7.5 build keeps the genuine **llama.cpp CPU runtime**, the bounded **inspect → repair → retest** loop, and the verified environment foundation. It adds a managed Ollama lifecycle, reusable Arsenal procedures, MCP/GitHub gateways, durable project memory, a source symbol index, a Live Worker Console, atomic workspace creation, and one scoped autonomy policy. A healthy baseline is still never reported as a completed repair, and a model's prose is still never accepted as proof.

What it is *not* yet: a fully autonomous developer, a frontier-model replacement, or a system with unrestricted control of your device. The [current status](#-current-status) section states exactly what has been verified and what has not.

---

## ✨ Capabilities

### 🧩 New in 0.7.5

- **Managed local Ollama:** detect, start, inspect, stop, and restart a Termux-hosted server with verified PID ownership; adopted servers are never killed.
- **Arsenal:** nine versioned offline procedures, relevance-bounded injection, strict untrusted imports, enable/configure/remove controls, and evidence-linked execution logs.
- **Gateways:** real streamable-HTTP MCP and GitHub tools behind per-server allowlists, per-run budgets, timeouts, redacted logs, and Keystore-only tokens.
- **Durable project memory:** evidence-hashed verified outcomes and lessons injected into later objective prompts.
- **Live Worker Console:** a persisted, evidence-backed timeline of plans, roles, tools, skills, gateways, verification, recovery, and errors.
- **Workspace hardening:** atomic collision-safe creation, no directory reuse, scoped routine grants, and cancellation mapped to `uncertain`.

### 💡 Local AI inference

- **Native GGUF CPU inference** through a bundled llama.cpp runtime and JNI — no Termux and no network required for basic inference.
- **Ollama integration** for a local server on loopback or your network.
- **OpenAI-compatible endpoints** for any `/v1` chat-completions server.
- **Provider-independent architecture**: chat, agents, workflows, and objectives all resolve through one factory, so no provider is hardcoded as the only path.
- **Local model management**: import a GGUF through the Android file picker, inspect its metadata and a qualified memory estimate, then load, stream, stop, unload, or delete the private copy.

### ⚡ Autonomous objectives

- **Persistent objective and task state** in Room, surviving activity destruction and process restarts.
- **Dependency-aware scheduling** over a validated task graph, with transitive blocking when a prerequisite fails.
- **Resource-conscious concurrency** derived from real memory, thermal state, battery, and storage.
- **Service-owned execution** in a foreground service with leases, heartbeats, and durable checkpoints.
- **Pause and cancellation** from the notification or the app, with uncertain work marked for review rather than silently replayed.

### 🧠 Autonomy modes and safety

- **Manual, Smart, and Full modes** — from ask-before-consequential-actions to routine and elevated work inside an explicit grant.
- **Scoped, revocable grants** bound to the selected environment and workspace, with delegation able to narrow scope but never widen it.
- **Emergency Stop** that denies new actions immediately and remains active across app restarts.
- **Central risk classification** for terminal commands, file changes, package installs, network access, publishing, credentials, and delegated work.
- **Local audit journal** of policy decisions, with Full mode still stopping at publishing, APK installation, system changes, private-data transfers, and credential disclosure.

### 🛠️ Real execution tools

- **Termux command bridge** over the documented `RUN_COMMAND` intent, with real stdout, stderr, and exit codes.
- **Terminal environment discovery** for the Termux host, installed tools, and available PRoot distributions.
- **Debian PRoot support** using the proot-distro executable actually discovered on the device.
- **Workspace-scoped file tools** confined to an explicitly approved directory.
- **Automatic workspace creation** under the selected host or guest, with collision-safe names and a real write check.
- **Approved dependency provisioning** from a deterministic, origin-aware plan — never from model-authored shell text.
- **Project detection** for common build systems, with missing-toolchain reporting instead of blind retries.
- **Build and test commands** fixed by detected project markers — a model cannot substitute its own shell text.

### 🔍 Evidence-driven verification

- **Exit-code validation** from actual process results.
- **File existence and content checks**, plus **SHA-256** comparison.
- **Task evidence records** persisted separately from model prose.
- **Conservative recovery**: read-only work may retry; anything that may have changed state requires explicit human review.

### 🐧 Environment intelligence

- **Isolated host discovery** — probes run through `$PREFIX/bin/bash --noprofile --norc` with `BASH_ENV`/`ENV` cleared, so a Termux startup profile that auto-enters Debian cannot masquerade as the host.
- **Strict PRoot-Distro detection** — installed aliases are parsed from real `proot-distro list` output; a failed command is never reported as "none installed".
- **Real guest verification** — a distribution is available only after a command inside it returns the expected marker, HOME, OS, uid, and tool paths.
- **Timestamped capability profiles** — per-tool verified, missing, incompatible, or permission-denied state with paths, versions, free storage, and a workspace write check.
- **Executable origin** — inherited Termux binaries inside Debian are labelled and treated as incompatible, not counted as native guest tools.
- **Trusted package plans** — `apt` for guests and `pkg` for the host, with Android SDK builds refused inside an ARM64 guest.
- **Workspace validation** — syntax plus a real existence/write probe, with explicit host-versus-guest path rules.
- **Development preflight** — stable blocker codes for provider, model, environment, workspace, tools, and memory before an objective may start.

### 🌐 Controlled network access

- **`fetch_url` objective tool** for public documentation and research.
- **HTTPS-only public requests**, with credentials in URLs refused.
- **Private-address, link-local, CGNAT, and loopback-pivot blocking**, plus guarded DNS and redirect re-validation.
- **Bounded text bodies** that enter prompts only inside an explicit untrusted-content block.
- **Atomic SHA-256 downloads** in the network layer, with partial files removed on cancellation or failure.

### 🤖 Specialist roles and supervision

- **Deterministic worker roles** — planner, research, coder, environment, tester, verifier, and documenter.
- **Supervisor decisions** — continue, replan, pause, or fail based on task state, repeated failures, and remaining budget.
- **Bounded replanning** after a failure, with a diagnostic task and a hard attempt ceiling.
- **Shared evidence trail** across delegated tasks, so a claim cannot outrun local verification.

Delegation is currently **specialized roles within the existing bounded workers** — not separate independently running model processes.

### 💻 Verified Autonomous Developer v2

- **Goal-aware acceptance** — repair, feature, diagnosis, and artifact goals carry different criteria.
- **Honest baseline handling** — a passing baseline is `BASELINE_HEALTHY`, not a repair.
- **Feature-completion verification** — a feature needs a changed test and a green suite, or it is `GOAL_NOT_VERIFIED`.
- **Relevance-ranked retrieval** — failing paths, diagnostics, recency, and size rank the context.
- **Reliable source transfer** — up to four files and 96 KB per Termux IPC call, with per-file fallback for omitted files.
- **Independent evidence** — build/test exit codes and original output, kept separate from model prose.
- **Bounded repair** — configurable iteration, inference-call, time, and file budgets, with exact-hash patches and retained diffs.

### 📱 Android-native experience

- **Kotlin and Jetpack Compose**, with no web wrapper.
- **Dark violet visual identity** with a branded adaptive launcher icon and native splash.
- **Phone and foldable layouts** with a bottom bar, an expanded-width navigation rail, and split panes for agents and workflows.
- **Local-first operation** with no account, no telemetry pipeline, and no mandatory cloud service.
- **Android API 37 target**, **minimum API 26**, edge-to-edge with correct window insets.

---

## 💜 Why NYX exists

Useful AI should not require renting intelligence by the token.

Most capable assistants live behind a subscription, a credit balance, or a cloud account, and every prompt leaves the device. That model is convenient, but it makes local computation a second-class option and puts a permanent meter on curiosity.

NYX takes the opposite position: if you own hardware capable of running a model, you should be able to use that hardware for real work, offline, without asking permission from a billing system. That means:

- **User ownership** — your device, your models, your data, your rules.
- **Open-source development** — the application and its native runtime integration are inspectable and buildable.
- **Privacy-conscious local operation** — inference can stay entirely on the device.
- **Model independence** — swap GGUF files, run Ollama, or point at a compatible server.
- **No mandatory paid API**, **no mandatory cloud account**, and **no mandatory subscription**.

Local inference is still bounded by physics. Model quality, context length, speed, and memory use depend on the device's RAM, thermal headroom, storage, and the weights you choose. A quantized 1–3B model on a phone is useful for focused tasks; it is not a frontier model, and NYX does not pretend otherwise.

---

## 🗺️ How NYX works

```mermaid
flowchart TD
    A([🎯 User objective]) --> B["NYX Android UI<br/>Compose · API 26–37"]
    B --> C{"Autonomous<br/>execution engine"}
    C --> D["Task scheduler<br/>dependency-aware · resource-aware"]
    D --> E["Inference providers"]
    E --> E1["Native GGUF<br/>llama.cpp CPU"]
    E --> E2["Ollama"]
    E --> E3["OpenAI-compatible /v1"]
    D --> F["Authorized tools"]
    F --> F1["Termux / Debian PRoot"]
    F --> F2["Workspace files"]
    F --> F3["Build &amp; test commands"]
    D --> J["Arsenal skills<br/>relevance-bounded"]
    D --> K["Gateways<br/>MCP · GitHub"]
    K --> K1["Keystore credentials<br/>allowlists · budgets"]
    J --> F
    E --> G["Evidence verifier<br/>exit codes · files · SHA-256"]
    F --> G
    G --> H[("Persistent context<br/>Room + project memory")]
    H --> C
    G --> L["Live Worker Console<br/>persisted execution evidence"]
    L --> H
    G --> I([✅ Verified objective result])
```

The engine plans one bounded task graph, runs only dependency-ready tasks within device limits, records every tool observation, and evaluates declared evidence before a task may complete. A model's claim of success is never accepted as proof.

---

## 🚀 Getting started

NYX supports two independent ways to run a model. You do not need both, and native inference does not require Termux.

### Option A — Native GGUF mode (no Termux, no network)

1. Install `NYX-v0.7.5.apk` on a 64-bit ARM Android device running Android 8.0 (API 26) or newer.
2. Obtain a **compatible instruct-tuned GGUF** file. Start small: a 0.5B–3B model quantized to `Q4_K_M` is a realistic first test. Memory use grows with parameter count and context length.
3. Open **Models → Import GGUF** and pick the file with the Android document picker. NYX copies it into app-private storage and reads its metadata.
4. Review the reported architecture, quantization, context limit, and memory estimate. An incompatible or oversized model is refused rather than attempted.
5. Select the model, choose a context size and thread count, then **Load**.
6. Open **Chat** and send a short prompt. Streaming output can be stopped at any time.

> [!NOTE]
> **Native Android inference has not yet been verified on physical hardware at publication time.** The runtime generated real streamed tokens on a Linux x86_64 host using the same JNI bindings, and the ARM64 library cross-compiles and packages correctly, but on-device GGUF loading and generation still require confirmation. See [current status](#-current-status).

### Option B — Termux, Ollama, or a compatible server

1. Install [Termux](https://termux.dev/) and your preferred local inference server, following that project's own instructions.
2. In **Settings**, set the endpoint:
   - Ollama: `http://127.0.0.1:11434`
   - OpenAI-compatible: `http://127.0.0.1:8080/v1`
   - Or open **Models → Managed Ollama** and use **Start** to launch a Termux-hosted server without leaving NYX.
3. Tap **Connect** and choose a model from the discovered list.

For the optional terminal tools, all of the following are required — granting one permission does not complete the setup:

```properties
# ~/.termux/termux.properties
allow-external-apps=true
```

Restart Termux after editing the file, then grant NYX the `com.termux.permission.RUN_COMMAND` permission in Android settings. Finally, choose an explicit workspace directory inside NYX and enable terminal tools there.

> [!WARNING]
> Terminal tools are **off by default** and apply only to objectives in the workspace you approve. Enabling them authorizes bounded source edits and execution of the project's own build/test scripts. Those scripts run with Termux's authority and can reach other files or the network — approve only projects you trust. NYX never enables root and never commits or publishes. It installs only planner-generated packages approved by policy, never arbitrary model-authored install commands.

If a LAN server is used on Android 17, the system will also ask for the local-network permission; denying it leaves internet endpoints working while LAN access stops.

---

## 📋 Requirements

| | |
| :--- | :--- |
| **OS** | Android 8.0 (API 26) or newer, up to API 37 |
| **CPU** | 64-bit ARM (`arm64-v8a`) — no x86 or 32-bit-only support |
| **Storage** | Roughly 2–4× the model file size free, plus workspace |
| **Memory** | Depends on model size and context; small models need ~1–2 GB of headroom |
| **Termux** | Optional, only for terminal tools and Debian PRoot workflows |

---

## 📊 Current status

This table separates what exists from what has actually been demonstrated. It is deliberately conservative.

| Status | Area |
| :--- | :--- |
| ✅ **Implemented and build-tested** | Native GGUF provider, model import and management, provider factory, inference admission, objective engine, scheduler, verifier, context store, Arsenal procedures, repair loop, Termux bridge, autonomy policy, dependency provisioning, network tools, specialist roles, Android 17 permission handling, managed Ollama lifecycle, MCP/GitHub gateways, project memory, source symbol index, Live Worker Console |
| ✅ **Build-tested** | Debug APK, instrumentation APK, and **598 JVM tests with zero failures, errors, or skips** on a clean build |
| 🧪 **Verified on Linux host only** | Real llama.cpp JNI generation from an Apache-2.0 SmolLM2-135M Q4_K_M GGUF (`The capital of France is Paris.`, 7 selected tokens), plus cancellation, session reuse, callback-error propagation, and safe unload |
| 🧪 **Verified on Linux host only** | Autonomous repair of a genuinely failing fixture: baseline build `0`, baseline test `2`, exact-hash edit, retest build `0` / test `0`, with backup and diff retained |
| 📱 **Confirmed on the owner's Android 17 device** | Termux environment discovery (earlier development cycle) |
| ⏳ **Awaiting physical-device verification** | v0.7.5 managed Ollama start/stop/restart and ownership checks, Arsenal imports and execution, MCP/GitHub network calls and Keystore token use, Live Worker Console, project memory, symbol indexing, atomic workspace creation, scoped cancellation, and the prior autonomy/development/native-inference flows; Termux-host discovery, PRoot guest verification, capability probes, and development preflight; Room migration preservation; notification actions; process-death recovery; fold and unfold layout |
| 🗺️ **Planned** | See the [roadmap](#-roadmap) |

Artifact checks performed on the published build: package `com.nyx.ai`, versionName `0.7.5`, versionCode `9`, target and compile SDK `37`, minimum SDK `26`, `arm64-v8a` only, and the same debug signing certificate as v0.7.0. Full detail is in [TEST_RESULTS.md](TEST_RESULTS.md).

---

## 🧭 Roadmap

These are goals, not completed capabilities.

- **Native Android inference validation and optimization** — confirm real GGUF execution across devices, then tune threads, context, and memory behavior.
- **Improved autonomous software development** — stronger multi-file repair, better diagnosis, and reliable end-to-end completion on Android.
- **Better memory and inference resource management** — more accurate residency accounting and graceful degradation under pressure.
- **More reliable interruption recovery** — clearer ownership, resumable checkpoints, and safer restart decisions.
- **Expanded verified local skills** — more built-in, manifest-validated procedures with no hidden privilege escalation.
- **Improved accessibility and device compatibility** — broader hardware coverage, larger-text support, and clearer failure messaging.
- **Reproducible production release builds** — a documented, independently verifiable signing and release process.

---

## 🧑‍💻 Development

### Build from source

Requirements: JDK 17 or newer, Android SDK with API 37 and Build Tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1, and Python 3 for the SDK policy checks.

```bash
git clone https://github.com/ShamelesAbyss/Nyx.git
cd Nyx
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=/path/to/jdk-21
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The native runtime is built for `arm64-v8a` only. Full instructions, including SDK policy guards and instrumentation commands, are in [BUILD.md](BUILD.md).

### Documentation

| Document | Contents |
| :--- | :--- |
| [BUILD.md](BUILD.md) | Toolchain, reproducible builds, tests, signing, packaging |
| [USER_GUIDE.md](USER_GUIDE.md) | Setup, models, agents, objectives, terminal, troubleshooting |
| [NYX_DEVELOPMENT_REPORT.md](NYX_DEVELOPMENT_REPORT.md) | What was built, what was verified, what remains |
| [TEST_RESULTS.md](TEST_RESULTS.md) | Exact test counts, host evidence, and unexecuted device checks |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [ENGINEERING_STANDARDS.md](ENGINEERING_STANDARDS.md) | Permanent SDK and compatibility policy |
| [PLATFORM_NOTES.md](PLATFORM_NOTES.md) | Android and Termux platform constraints |
| [docs/V061_VERIFIED_DEVELOPER.md](docs/V061_VERIFIED_DEVELOPER.md) | v0.6.1 environment, preflight, and verified-developer details |
| [docs/V070_IMPLEMENTATION_STATUS.md](docs/V070_IMPLEMENTATION_STATUS.md) | v0.7.0 policy, environment, network, and delegation implementation status |
| [docs/V075_INTEGRATION_STATUS.md](docs/V075_INTEGRATION_STATUS.md) | v0.7.5 managed Ollama, Arsenal, gateways, memory, console, and workspace integration status |
| [docs/](docs/) | Native inference, development engine, context and skills, Android compatibility |

### Contributing

Contributions are welcome from Android engineers, local-model researchers, and anyone who wants private AI to work well on real hardware.

- **Bugs and feature requests:** open an [issue](https://github.com/ShamelesAbyss/Nyx/issues) with device, Android version, model file, and reproduction steps.
- **Pull requests:** keep changes focused, preserve the existing architecture, and include tests for deterministic logic.
- **Claims:** report what you actually observed. A build that compiles is not a device validation, and a model that sounds confident is not evidence.

No formal contribution process, CLA, or governance structure is documented beyond the above; treat this as an early-stage project.

### License and attribution

NYX is released under the [Apache License 2.0](LICENSE). The bundled llama.cpp runtime retains its own upstream license and attribution, included in the source archive. Model weights are **not** distributed with NYX; obtain them under terms that permit your use.

---

<div align="center">

## Your Intelligence. Your Device. Your Rules.

**NYX Eclipse AI** · built for the hardware you already own.

<sub>v0.7.5 — Project Continuum · Apache-2.0 · ARM64 · API 26+</sub>

</div>

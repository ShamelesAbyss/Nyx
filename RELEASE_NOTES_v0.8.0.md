# 🚀 NYX v0.8.0 — Project Autonomous Bootstrap

**A major step toward meaningful local-first autonomous development on Android.**

NYX 0.8.0 connects environment preparation to the objective that needs it. Instead of stopping when a Debian tool is missing, NYX can now discover the real package source, resolve a native JDK candidate, install only approved packages, verify the executables, and continue the original objective with its task graph and checkpoints intact.

This is a development build, not a claim that NYX is a finished autonomous developer. The implementation, automated verification, and remaining physical-device checks are separated below.

---

## 🧠 Autonomous Environment Preparation

The objective foreground service now reconciles the current Termux/PRoot environment before any task runs. A current, verified guest clears a stale historical environment error; an unavailable or diverted environment records a current diagnostic and stops before execution.

The new `DependencyBootstrap` orchestrator probes the guest, builds the existing trusted dependency plan, installs only approved candidates, re-probes every required executable, and then continues the objective. A failed or interrupted install still stops with a visible `needs_attention` diagnostic rather than silently retrying an uncertain state.

## 🐧 Debian Intelligence

Debian 13 “Trixie” uses Deb822 repository stanzas in `/etc/apt/sources.list.d/debian.sources`. v0.8.0 recognizes both traditional `*.list` files and Deb822 `*.sources` files. A stanza is counted only when `Types` contains `deb`, `Enabled` is not `no`, and both `URIs` and `Suites` are present.

The resolver then uses actual `apt-cache policy` evidence to select a candidate. On the observed Trixie device, `openjdk-21-jdk-headless` was available as `21.0.13+9-1~deb13u2` while OpenJDK 17 had no candidate. The release prefers the real available JDK instead of hardcoding a version or reporting a false `APT sources: 0`.

Executable verification still requires `java` and `javac` to run as guest-native tools. Inherited Termux binaries inside Debian remain labelled and are not counted as native guest tools.

## ⚡ Policy-Gated Execution

`terminal_run` is no longer unconditionally denied. A model command must pass `ModelCommandPolicy`: it must remain inside the verified workspace or a safe subdirectory, be non-empty and bounded, avoid credential and private-data paths, and receive the correct read-only or mutating policy decision.

`AutonomyModelCommandGate` applies `RUN_READONLY_COMMAND` or `RUN_MUTATING_COMMAND` through the shared autonomy policy. Publishing, APK installation, system changes, private-data transfers, credential disclosure, root escalation, and destructive commands remain outside ordinary model authority. This is a policy boundary, not a security sandbox.

## 👁️ Live Worker Console

The existing persisted `WorkerActivityBus` now carries bounded tool input and output alongside timestamped planning, task, tool, terminal, installation, file, verification, recovery, and failure events. The console is available from Chat and from a visible Live Activity card in Autonomy.

It shows the current objective, execution phase, active task, status, and timestamped events. Each event can expand to readable input/output detail. Pause, Resume, and Cancel call the existing objective service controls. Copy and share use a bounded, redacted plain-text export; no hidden model reasoning or credentials are added. History remains in the existing on-device JSONL store across navigation and process recreation.

## 📁 Workspace Lifecycle

The saved verified workspace is re-probed and reused on startup instead of silently creating a numbered replacement. Workspace selection, the persisted managed registry, routine policy grants, and the objective execution snapshot are synchronized. Terminal-enabled objectives refuse to start without a verified workspace.

The Terminal screen now lists verified NYX-managed workspaces with exact paths and Use, Delete, and Delete all test workspaces actions. Deletion requires an exact path-list confirmation and refuses the parent root, nested or traversal paths, symlinks, non-canonical paths, paths outside the managed parent, and workspaces belonging to active objectives. The parent root, Ollama models, Debian installation, and unrelated files are never targeted.

## 🔐 Privacy & Safety

NYX remains local-first. There is no account, no telemetry pipeline, and no mandatory cloud service. Model inference can stay entirely on the device through the bundled llama.cpp CPU runtime, a local Ollama server, or another compatible endpoint.

Credentials remain in the existing Keystore-backed storage. Terminal commands are workspace-confined and policy-gated. Paths are validated before use. Emergency Stop denies new actions immediately and remains active across app restarts. No signing key, keystore, `.env` credential, private configuration, or model weight is included in the source archive.

## ✅ Verification & Real-Device Evidence

**Automated verification for this build:**

- Clean build: **BUILD SUCCESSFUL in 1m 15s**
- JVM tests: **679 passed, 0 failures, 0 errors, 0 skipped** across 64 test classes
- Debug APK and instrumentation-test APK compiled
- Package: `com.nyx.ai`, versionCode **13**, versionName **0.8.0**, minSdk **26**, compile/target **37**, `arm64-v8a`
- Signer: the same debug certificate as the earlier public NYX releases
- 16 KB ZIP alignment and release-integrity checks passed
- Source archive hygiene checked for caches, build outputs, local configuration, signing material, and model weights

**Physical-device evidence:** the owner’s Android 17 device running Debian 13.7 Trixie reproduced the original blocker. Debian reported valid enabled Deb822 `deb` repositories for `trixie`, `trixie-updates`, and `trixie-security`; `apt-cache policy` reported `openjdk-21-jdk-headless` as available. That evidence drove the resolver and continuation fix. The fixed APK’s successful on-device installation and objective continuation have **not** yet been re-run and are not claimed as device-verified.

**Earlier host evidence retained:** real llama.cpp JNI generation from a SmolLM2-135M Q4_K_M GGUF on a Linux x86_64 host, plus a verified failing-fixture repair loop. These are host checks, not Android device validation.

## ⚠️ Remaining Limitations

- The fixed Debian JDK installation and automatic objective continuation still need a physical-device run.
- The new expandable Live Worker Console rendering, pause/resume controls, and diagnostic share flow have not been exercised on a physical Android device.
- Workspace deletion and startup reuse are covered by pure tests and guarded command generation, not a real device deletion.
- End-to-end calculator verification through the full autonomous objective path remains unfinished.
- Native GGUF loading, generation, memory pressure, and thermal behavior still require validation across real ARM64 devices.
- Termux `RUN_COMMAND`, Android foreground-service lifetime, Keystore access, notifications, Room migration, and fold/unfold layouts remain device checks.
- The APK is debug-signed. It is not a Play Store or production-signed release.
- NYX does not claim parity with Codex, Claude Code, or Manus.

## 📦 Downloads & Installation

| Asset | Purpose |
| :--- | :--- |
| **[NYX-v0.8.0.apk](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.8.0/NYX-v0.8.0.apk)** | Application, ARM64, debug-signed |
| **[NYX-v0.8.0-androidTest.apk](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.8.0/NYX-v0.8.0-androidTest.apk)** | Instrumentation tests for device verification |
| **[NYX-Source-v0.8.0.zip](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.8.0/NYX-Source-v0.8.0.zip)** | Complete source, tests, docs, licenses, and Gradle wrapper |
| **[NYX-v0.8.0-SHA256SUMS.txt](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.8.0/NYX-v0.8.0-SHA256SUMS.txt)** | SHA-256 digests for every artifact |

**Requirements:** Android 8.0 (API 26) or newer, a 64-bit ARM device, and enough free storage for the model and workspace. Termux and Debian PRoot are optional and needed only for terminal tools or guest-hosted Ollama. Native GGUF mode does not require Termux or a network.

**Upgrade note:** v0.8.0 uses the same debug signing certificate as the earlier public NYX releases, so an in-place update is possible when that certificate matches. Back up any data-bearing installation before experimenting.

**Install:** download the APK, allow installation from the file manager or browser if Android asks, and open NYX. For terminal workflows, configure Termux `allow-external-apps=true`, grant `com.termux.permission.RUN_COMMAND`, and verify the workspace before enabling terminal tools.

---

**NYX Eclipse AI** · Your Intelligence. Your Device. Your Rules.

Apache-2.0 · ARM64 · API 26+ · development build

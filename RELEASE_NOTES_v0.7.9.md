# NYX v0.7.9 — Persistent Guest Runtime

> **A focused reliability release for the Debian Ollama path — and the point where NYX stops treating “started” as “still running.”**

NYX v0.7.9 is a **debug-signed, ARM64 development build**, not a production-signed Play Store release. It fixes the confirmed failure where a Debian-hosted Ollama server could start, be observed briefly, and then disappear before it ever answered an API request. It also gives that runtime an Android foreground owner and verifies cleanup instead of assuming it.

This release includes the **v0.7.8 device hotfix** that was built but not published separately: the shared, persisted PRoot executable path and real Debian JDK package resolution.

---

## 🧬 The fix in one sentence

The old launcher changed the POSIX session with `setsid`/`nohup` inside a short-lived `proot-distro login`; PRoot still supervised the tracee with `--kill-on-exit`, so the server could be torn down when that login returned. v0.7.9 launches the server in a **durable PRoot session** when the installed `proot-distro` supports `login --detach`, `ps`, and `kill`, or keeps one **foreground PRoot login alive** when it does not.

The server always runs in the foreground of its own session with `exec ollama serve`. A foreground Android service owns the lifecycle while the guest starts or serves.

---

## 🚀 What’s new

### 🧱 Durable Debian Ollama sessions

- Probes `proot-distro login --help`, `ps`, and `kill` together before choosing a launch mode.
- Uses `proot-distro login --detach <alias> -- /bin/bash -lc '<guest script>'` when supported.
- Records the session PID and its kernel start-time token in a target-scoped record.
- Falls back to one long-lived foreground PRoot login on older installations instead of pretending to detach.
- Refuses a second live NYX-owned server and refuses to signal a process whose identity no longer matches.
- Keeps the server in the foreground of the session so PRoot’s normal `--kill-on-exit` behavior cannot silently remove it.

### 📡 Android foreground ownership

- Added `OllamaHostService`, a non-exported `dataSync` foreground service.
- Owns guest **Start**, **Stop**, and **Restart** while the NYX activity is backgrounded.
- Keeps a persistent notification reporting **Starting**, **Ready · Running**, **Recovering**, **Stopped**, or **Error**.
- Adds **View Activity**, **Stop Worker**, and optional **Open Termux** actions.
- Streams manager readiness into the notification instead of leaving a generic “Preparing” message on screen.
- Stops supervising when the server is gone or when an existing user-started server has been adopted.

### 🔎 Readiness that distinguishes real failure modes

- Separates **guest-local API readiness** from **Android-to-guest reachability**.
- Captures listening-port evidence when `ss` or `netstat` is available.
- Distinguishes a dead process, guest API waiting, Android reachability waiting, API waiting, a port conflict, and **READY**.
- Uses a bounded 180-second guest grace period with progressive probes; the host path keeps its existing 45-second policy.
- Reports a live-but-unreachable owned server distinctly instead of deleting it or silently restarting it.
- Leaves a port-conflict process running for inspection rather than killing an unknown process.

### 🧹 Verified stop, recovery, and orphan handling

- Stop sends a verified single-PID TERM to the guest Ollama process.
- Then calls `proot-distro kill` for the recorded session.
- Re-inspects `/proc/<pid>` before reporting **STOPPED**.
- Keeps an unkillable session visible with a retryable error instead of a false success.
- Re-reads the session record on refresh, verifies its start token, adopts a live process, and clears only an unverifiable record.
- Exposes **Stop** for an orphaned detached session even when the guest PID record is missing.
- Never kills a server that NYX did not start.

### 💬 Chat and objective continuation

- Chat now starts a stopped loopback runtime, refreshes the model inventory, and continues the original request.
- Objective creation and resume use the same service-owned guest path and rediscover the selected target afterward.
- Model discovery reconnects when the server becomes healthy; no model files are downloaded, duplicated, or deleted.

### 🩹 v0.7.8 hotfix included

- **Shared PRoot path:** `ProotExecutableStore` carries the host-verified `proot-distro` path across every `TerminalBridge`, service instance, and app restart. `NyxViewModel` shares one bridge between the terminal repository and the Ollama manager.
- **Real Debian JDK resolution:** `GuestJdkResolver` reads the guest release, architecture, APT sources, and `apt-cache policy` candidates. Debian Trixie prefers OpenJDK 21 or the default JDK; Bookworm prefers 17. Missing candidates produce a blocker instead of a blind install.

---

## 🎨 Visual and UI changes

- The Models card shows the selected host or verified guest, architecture, compatibility, launch mode, session identity, and operation busy state.
- Lifecycle buttons are disabled while the foreground service owns an operation.
- The notification is deliberately low-importance but persistent while the managed runtime is active.
- The existing NYX visual identity, launcher art, splash, and adaptive phone/foldable layouts are unchanged.

---

## ⚡ Performance and stability

- One verified session and one verified server per target; duplicate starts are refused.
- Progressive readiness polling instead of tight restart loops.
- Bounded log tails and diagnostics; no unbounded server output is placed in the UI.
- Reuses the existing shared OkHttp connection pool and inference admission rules.
- Host-mode lifecycle remains direct and unchanged.

---

## 🎮 Controls and usability

- **Models → Managed Ollama → Start / Stop / Restart** works for the Termux host and verified Debian guests.
- **Chat:** send a message against a stopped loopback endpoint; NYX starts the runtime, reconnects models, and continues.
- **Objectives:** create or resume a loopback objective; NYX performs the same startup and readiness path.
- **Notification:** View Activity, Stop Worker, and Open Termux where Termux is installed.
- **Recovery:** a live but unready process remains inspectable; an orphaned session keeps a Stop affordance.

---

## 🐞 Bug fixes

- Fixed Debian-hosted Ollama being torn down when the short-lived PRoot login returned.
- Fixed the managed runtime having no Android foreground owner while waiting for a guest server.
- Fixed false **STOPPED** reports when a PRoot session survived.
- Fixed stale session records after a process death or target change.
- Fixed Chat failing at the provider check instead of starting a stopped loopback runtime.
- Fixed the v0.7.8 PRoot-path mismatch between the manager and terminal repository.
- Fixed the hardcoded Debian JDK package that did not exist on Trixie.

---

## 🏗️ Technical architecture

| Component | Role |
| :--- | :--- |
| `OllamaHostService` | Foreground owner for guest lifecycle, notification states, and actions |
| `OllamaGuestRuntime` | Session capability probes, safe guest scripts, launch-mode policy, start-token and listening evidence |
| `OllamaServerManager` | Target-aware discovery, ownership records, readiness, stop, restart, and recovery |
| `OllamaReadiness` | Host/guest timeouts, progressive stages, and bounded failure messages |
| `ProotExecutableStore` | Process-wide and persisted host-verified `proot-distro` path |
| `GuestJdkResolver` | Read-only Debian release/APT/JDK candidate resolution |

No root, no weakened Android security, no arbitrary model-authored shell text, and no silent APK installation.

---

## ✅ Build and test verification

- **632 JVM tests passed**, with **0 failures, 0 errors, 0 skipped**.
- Clean command: `./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`
- Debug APK and instrumentation-test APK built successfully.
- Package: `com.nyx.ai`, versionCode **12**, versionName **0.7.9**.
- Compile/target SDK **37**, minimum SDK **26**, `arm64-v8a`.
- APK signing certificate SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` — the same debug certificate as v0.7.5 and v0.7.7.
- Release integrity check passed; APK 16 KB ZIP alignment verified.

> The instrumentation APK compiles but its tests were not executed here because no physical Android device is attached.

---

## ⚠️ Known limitations

- **Physical-device validation is still pending** for actual `proot-distro ps` output, detached-session lifetime, Android 14+ `dataSync` behavior, Termux `RUN_COMMAND` lifetime, guest API reachability, notification actions, and the full Models → Start → Ready → Chat sequence.
- The foreground fallback cannot survive Android killing the NYX process; the guest PID record may remain until the next verified stop or refresh.
- Android may terminate a foreground service under its own policy; NYX does not claim indefinite background execution.
- A user-started Ollama server that NYX did not launch is adopted read-only and is never killed.
- Debian/PRoot support requires Termux, `proot-distro`, `allow-external-apps=true`, and the `com.termux.permission.RUN_COMMAND` grant.
- Native GGUF inference on physical Android hardware remains a separate, still-pending validation area.
- This is a debug-signed development build, not a Play Store or production release.

---

## 📥 Download and installation

1. Download `NYX-v0.7.9.apk`.
2. Install it on a 64-bit ARM Android device running Android 8.0 (API 26) or newer.
3. Existing v0.7.5/v0.7.7 installations can update in place when the signing certificate matches. Back up important data first.
4. Open **Models → Managed Ollama**, select the host or a verified Debian guest, and tap **Start**.
5. For Chat, select a loopback Ollama endpoint; NYX starts the runtime and refreshes the model list when needed.

**Assets**

- `NYX-v0.7.9.apk` — debug-signed ARM64 application
- `NYX-Source-v0.7.9.zip` — complete source, tests, documentation, licenses, and Gradle wrapper
- `NYX-v0.7.9-androidTest.apk` — instrumentation-test APK
- `NYX-v0.7.9-SHA256SUMS.txt` — SHA-256 manifest

---

## 🔐 License

NYX is released under the Apache License 2.0. The bundled llama.cpp runtime retains its upstream license and attribution. Model weights are not distributed with NYX.

**Your Intelligence. Your Device. Your Rules.**

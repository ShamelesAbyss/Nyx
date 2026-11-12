# NYX v0.7.7 — Project Liberation

> **A release dedicated to making NYX’s autonomous infrastructure work naturally with real Android development environments.**

NYX v0.7.7 is a **debug-signed, ARM64 development build**, not a production-signed Play Store release. It fixes the three failures confirmed during physical-device testing: Debian-only Ollama was reported as missing, repeated Full-Autonomy project creation was denied after the first project, and managed-server ownership was not scoped to the environment that produced it.

## 🧠 Unified Ollama Management

NYX now discovers and manages Ollama across the **Termux host and verified Debian/PRoot guests**.

- Probes the isolated host and every guest that environment discovery actually verified.
- Recognizes `/usr/local/bin/ollama` as a Debian-native installation.
- Records executable path, origin, version, architecture, and runtime compatibility.
- Treats a warning-prefixed `ollama --version` as **installed but stopped**, not missing.
- Selects a compatible installation deterministically, preferring the current environment.
- Starts, stops, restarts, refreshes, and polls readiness through the existing Termux/PRoot bridge.
- Reloads the existing model inventory and reconnects Chat without downloading, deleting, or duplicating models.
- Automatically starts a stopped loopback server before planning or resuming an objective.

## 🐧 Debian PRoot Improvements

- Lifecycle commands execute in the selected host or guest, not merely the Termux host.
- PID and kernel start-token records are **environment-scoped**, so a host PID cannot be used to stop a guest process.
- Adopted or unverified servers are never signalled.
- `setsid` startup has a `nohup` fallback for PRoot environments that reject session creation.
- A live-but-unreachable owned process is reported distinctly from a stopped installation.

## ⚡ Full Autonomy Improvements

Project creation is now a distinct routine action against the **approved workspace root**, not the temporary project name.

- Full mode can create multiple independent projects under the same authorized root.
- Manual mode still presents an explicit approval card.
- Existing directories are never reused, overwritten, or adopted.
- Traversal and collision protections remain in place.
- Per-project scopes are issued only after successful creation and verification.

## 🧰 Autonomous Workstation

These capabilities already existed and are preserved — they are **not** new v0.7.7 features:

- **Arsenal** operational skills with relevance-bounded injection and declared tools.
- **MCP and GitHub gateways** with per-server allowlists, budgets, timeouts, redacted logs, and Keystore-only tokens.
- **Persistent project memory** backed by evidence hashes.
- **Source symbol indexing** for Kotlin/Java projects.
- **Live Worker Console** with a persisted, observable execution timeline.
- **Manual, Smart, and Full autonomy modes** with scoped grants and Emergency Stop.

## 🔐 Safety & Reliability

- Environment-scoped process ownership and adopted-server protection.
- Workspace creation separated from per-project write grants.
- Policy access remains synchronized and revocable.
- Emergency Stop still blocks new lifecycle and workspace actions.
- Credentials remain encrypted in Android Keystore-backed storage; gateway records store only opaque references.
- Filesystem boundaries, scoped tool permissions, and auditable operations are preserved.

## ✅ Verification

- **609 JVM tests**, zero failures, zero errors, zero skipped.
- Debug APK and instrumentation-test APK build successfully.
- Package: `com.nyx.ai`
- Version: `0.7.7` / versionCode `10`
- Compile and target SDK: `37`; minimum SDK: `26`
- ABI: `arm64-v8a`
- Debug certificate SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`
- Signing continuity with v0.7.5 is preserved, so an in-place upgrade is possible when the same debug certificate is trusted.

## ⚠️ Known Limitations

**No physical Android device was attached to the build environment.** The following are implemented and unit-tested, but still require device validation:

- Real Debian `ollama serve` startup, detachment, API readiness, model enumeration, and inference.
- PRoot `RUN_COMMAND` IPC and guest lifecycle controls.
- Chat/objective reconnection against a real Debian-hosted server.
- Android Keystore use, notifications, foreground-service lifecycle, process-death recovery, and Room migration.
- MCP/GitHub network calls, Arsenal execution, symbol-index prompt use, and Live Worker Console delivery.
- Fold/unfold layout and thermal/memory behavior.

Automatic objective continuation after a dependency installation remains incomplete. Guest probing is bounded to four verified aliases. This is a development artifact, not a production-signed release.

## 📦 Assets

| Asset | SHA-256 |
| :--- | :--- |
| `NYX-v0.7.7.apk` | `e8e6e7d1fbe3aa8d680322adaa2894540c33133916aea6a3f552721940668e5f` |
| `NYX-v0.7.7-androidTest.apk` | `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` |

The source-archive digest is recorded in the attached `NYX-v0.7.7-SHA256SUMS.txt`; it cannot be embedded in the archive without changing the archive’s own hash.

# Platform notes used for NYX 0.6.0

These notes describe the current local source/build scope, not a published release or proof of Android-runtime testing. Historical 0.4.0/0.5.0 behavior remains recorded in [CHANGELOG.md](CHANGELOG.md).

## Android and supported toolchain

Package `com.nyx.ai` uses versionCode 6/versionName 0.6.0, min API 26, compile/target API 37, and native ABI **`arm64-v8a` only**. Build pins are AGP 9.1.1, Gradle 9.3.1, AGP built-in Kotlin 2.2.10, Compose compiler 2.2.10, KSP **2.3.6**, Room **2.8.5**, Build Tools 36.0.0, NDK 28.2.13676358, and CMake 3.22.1. The older KSP 2.2-era/Room 2.6.1 pair produced compilation errors and was replaced in integration testing. No obsolete Android Kotlin plugin/DSL suppression remains.

The installed API 37 platform package is `platforms;android-37.0`; the unsuffixed package lookup failure was a naming issue, not SDK unavailability. Local SDK-policy guards run before builds, check resolved configuration/variants, and fail closed against unsupported downgrades. They use checked-in dated policy rather than a live policy API. See [BUILD.md](BUILD.md) for authoritative current dependency pins and [Android compatibility](docs/ANDROID_COMPATIBILITY.md) for the recorded official-source references; earlier KSP-floor/dependency narrative in that reference is not the final integrated pin set.

## Native CPU inference, memory, and packaging

The APK loads its bundled JNI library with `System.loadLibrary`; it does not download executable native/DEX/JAR plugins. User-imported GGUF and skill manifests are data, not executable plugins. Native execution is llama.cpp CPU inference, with baseline ARM64 NEON and Android Debug **`-O2 -g`** confirmed in the actual CMakeCache; no GPU/NPU backend, Tensor/TPU acceleration, model weight bundle, or automatic model download is included.

SAF import validates and makes a private copy. One process-wide model/context session is shared; loading/deletion/unloading are serialized and cancelled native work is joined before the session is reused/freed. Chat/agents/workflows/objectives use the same strict factory and one global active-inference gate with up to 24 waiting requests. No provider fallback is automatic. Runtime status distinguishes true last-generation selected tokens and load/generation milliseconds from global streamed chunks/characters. Neither host timings nor chunk counts establish Android tokenizer throughput.

Admission uses available **system** RAM, Android's low-memory threshold, the configured 512 MB default minimum margin (256–2048 MB UI range), available-RAM reserve, weight size, estimated KV, and compute reserve. This is not the Android app's enforced memory cap or a guarantee that mmap weights are free. Android 17 per-app memory limits and device-specific thermal/process behavior still require runtime testing; a foreground service is not an exemption. API-gated exit diagnostics must distinguish real recorded reasons from inferred causes. See [native memory limits](docs/NATIVE_INFERENCE.md#memory-and-limits) and [Android diagnostics](docs/ANDROID_COMPATIBILITY.md#memory-admission-and-post-exit-diagnostics).

The pinned NDK/linker and uncompressed AGP packaging target 16 KB alignment. Standalone ARM64 cross-compilation verified the JNI ELF alignment/linkage; inspect every actual APK library, including `libc++_shared.so`, and run the zipalign check in [BUILD.md](BUILD.md#native-packaging-and-16-kb-checks). Compilation, ELF alignment, ZIP alignment, and real 16 KB runtime correctness are separate claims.

## Termux RUN_COMMAND and PRoot

- Official protocol: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- NYX uses an explicit `com.termux.app.RunCommandService` intent, `com.termux.permission.RUN_COMMAND`, package visibility, and a caller-supplied result `PendingIntent`. The user enables external apps in Termux and grants NYX permission using the explicit button/settings.
- The callback carries bounded stdout/stderr and distinguishes real process exit from infrastructure failure. The bridge uses argv, not an untrusted concatenated model command. Every manually entered command requires exact user approval, including read-only commands; protected/privileged/destructive operations remain blocked. Risk classification is not a shell sandbox.
- Discovery executes in the actual selected host/PRoot environment through `RUN_COMMAND`. NYX does not directly read Termux-private paths using Android `File` checks. Debian uses `proot-distro login debian -- ...` (https://github.com/termux/proot-distro); an installed, actually discovered path/toolchain is required.
- Objective workspace access is off by default and explicitly scoped. Standalone agents/workflows use app-private tools. Remote `realpath` confinement, expected hashes, bounded reads/writes, original backups, and checkpoints constrain supported edits; arbitrary model `terminal_run` and command/timeout overrides are denied.
- Fixed build/test scripts still execute trusted project code with Termux/PRoot authority. They can access resources beyond NYX's workspace checks; offline flags do not make an OS sandbox or network firewall. Approve only trusted, non-adversarial projects.
- Hard process deadlines require coreutils `timeout`; development also requires discovery/canonicalization/write utilities. Cancellation stops NYX waiting but a child may continue until timeout. Uncertain effects require review, not an automatic retry. Termux/PRoot has not been exercised on a physical device here.

After **Autonomy → Terminal → Allow terminal tools for objectives → Save workspace**, **Autonomy → Objectives → Use verified project repair loop → Plan and execute** bypasses model DAG classification and creates a deterministic development task. The actual orchestrator then validates the saved environment/workspace and executes detected baseline/repair/retest commands within the UI's iteration/call/time/file bounds. Passing baseline checks do not establish delivery of a new feature.

## Foreground execution and recovery

The objective service changed from historical `dataSync` to **`specialUse`**, with `FOREGROUND_SERVICE_SPECIAL_USE` and a manifest subtype explanation describing user-requested local AI/project work. Google Play must review the use case; the declaration does not guarantee approval or waive foreground-start/background/lifecycle restrictions. Official guidance: https://developer.android.com/develop/background-work/services/fgs/service-types

NYX uses a visible foreground notification, explicit user-started execution, Pause/Cancel controls, leases/heartbeats, and an **app-level two-hour deadline** in the v0.8.5 candidate. Do not describe that deadline as an Android specialUse limit or apply dataSync/mediaProcessing's separate six-hour quotas to all foreground services. Timeout guidance: https://developer.android.com/develop/background-work/services/fgs/timeout

Pause/cancel interrupts native coroutines cooperatively and joins JNI work; Termux waits may stop before the external child does. On interruption/process death, uncertain tasks persist as `needs_review`; there is no automatic replay or mid-process continuation. Closing the UI alone does not cancel a service-owned objective. Persisted Room/context/checkpoint state supports review, not a claim of unlimited unattended execution.

## Optional permissions and context boundaries

Explicit UI buttons request `POST_NOTIFICATIONS` on API 33+, Termux `RUN_COMMAND`, and `ACCESS_LOCAL_NETWORK` on Android 17/API 37. LAN permission is optional and applies to an explicitly configured LAN inference server, not native GGUF. Same-profile loopback does not require the LAN grant; Android 17 cross-profile loopback is separately restricted. Denial does not trigger another-provider fallback. See [Android local-network guidance](docs/ANDROID_COMPATIBILITY.md#android-17-local-network-access).

Ollama and `/v1` endpoints on a LAN, `.local` hostnames, and `10.0.2.2` emulator-host addresses are not on-device execution. Private addressing alone is not proof of same-device loopback or native execution.

Five builtin offline skill manifests are validated, objective task tool scopes are restricted to their existing validated subset, and `skill_scope` events persist that scope; there is no executable plugin marketplace. The Git builtin uses fixed `repo_status` (no command/options), checkpoint `repo_diff`, and `file_read`, not `terminal_run`. Full-content source hashes, deterministic freshness/compaction, and scoped indexing are context-library foundations. The app-readable index is not used to read Termux-private workspaces; development obtains independent fresh remote snapshots. Do not claim all foundation retrieval/compaction APIs are integrated into every task or conversation.

## Verification boundary and local delivery

A genuine host **x86_64 JNI** test used the Apache-2.0 SmolLM2-135M-Instruct-Q4_K_M and streamed `The capital of France is Paris.` in **7 true selected tokens**. ARM64 cross-compilation passed. The coordinating integrated Gradle build reported success with **212 JVM tests, zero failures/errors/skips**, and assembled debug app/instrumentation-test APKs. None is an Android-runtime/physical-device pass. Final clean-source/archive checks are separate; Android inference, permissions, foreground lifecycle, thermal/memory pressure, migration, Fold/GrapheneOS, Termux/PRoot, and 16 KB runtime behavior are unverified here.

Use the [native reproduction/device procedure](docs/NATIVE_INFERENCE.md#reproducible-verification), [development security boundary](docs/DEVELOPMENT_ENGINE.md#rollback-and-security-boundary), and [context/skills scope](docs/CONTEXT_AND_SKILLS.md). Local source ZIPs include vendored llama.cpp and all licenses, but exclude weights, build/.cxx/.kotlin outputs, caches, local configuration, and secrets. Local v0.6.0 artifacts are separate from the latest published v0.5.0 release; no GitHub publication, self-update, automatic installation, or store approval is authorized or implied.

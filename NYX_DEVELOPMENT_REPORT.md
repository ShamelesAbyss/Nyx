# NYX Eclipse AI v0.6.0 — Project Singularity

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

Native function calling is not advertised. `StructuredActions` uses a strict text-JSON protocol with tool allowlists, required fields, argument types and byte/character ceilings. One response-repair attempt is permitted; malformed output is denied rather than converted into shell text. HTTP function calls use their own protocol and the same tool validation. Raw model-directed shell execution is disabled.

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

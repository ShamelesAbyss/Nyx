# NYX v0.8.8 — Project Insight 🌑🧠

**Different models. Different capabilities. One intelligent Android execution engine.**

NYX has always refused to pretend that every model is equally capable. Project Insight turns that principle into runtime behavior: NYX now observes what a specific model on a specific device actually demonstrated, then chooses the smallest planning workload that can still be verified.

This is not a new assistant and not a new cloud service. It is the same private, local-first Android workstation — with a sharper sense of what the model in front of it can really do. No new permissions, no new tools, no new authority. Just less wasted planning, honest progress while a model thinks, and a clear record of why a strategy was chosen.

## 🧠 Capability-Aware Models

The Models diagnostic now records a bounded capability profile for the exact provider, model, context length and thread configuration:

- whether a constrained JSON schema sample passed;
- whether provider token statistics are available;
- whether a separate reasoning stream appeared;
- observed first-text latency and measured generation throughput where the provider reports it.

Profiles are cached locally for 24 hours, keyed to that identity, and invalidated when the provider, model, context or thread count changes. A prompt-only JSON success is stored as **unverified**, not as schema support. Nothing is inferred from a model's name, parameter count or reputation.

The architecture is model-agnostic. Individual models are not. A small quantized model may chat well and still plan poorly; a model without constrained output may never be safe for tool work. NYX records that difference instead of assuming it away.

## ⚡ Four Planning Strategies

| Strategy | When it applies | What happens |
| :--- | :--- | :--- |
| **Direct** | Short, explicitly text-only objective with no file, command, build, test, install, browse or publish intent | One deterministic inference task. No planning model call. |
| **Incremental** | Tool-oriented, complex, slow or unproven models | At most two validated steps first; one continuation after their evidence passes, up to six. |
| **Full** | Complex goal, demonstrated schema output, measured throughput of at least 6 tokens/s and a 4K+ context | One complete validated task graph, as before. |
| **Guided** | The model has not demonstrated constrained JSON | One validated `NEXT inference: …` decision per step. Inference only. |

Incremental mode never lets the model assign task IDs, dependencies, permissions or retry limits. NYX assigns them, validates every step against the same plan contract, and requests the next step only after the previous work has passed local evidence. A malformed decision, an unavailable worker, a tool step without a check, or prose where a decision was required ends in review — never in a fabricated task or an automatic success claim.

Guided mode is deliberately narrow. Plain text cannot carry the local evidence a terminal, filesystem or development task requires, so those workers are unavailable in guided mode. A model that cannot produce constrained output can still contribute analysis and guidance; it cannot drive privileged work.

## 💭 Reasoning Awareness

Ollama streams reasoning in a separate `thinking` field, and OpenAI-compatible servers may stream `reasoning_content` deltas. NYX previously ignored both, so a thinking model could look silent while it generated thousands of reasoning characters.

Now reasoning is:

- counted and reported as activity in the planning lifecycle and Live Worker Console;
- **never** appended to the answer;
- **never** treated as plan text or passed to tool instructions;
- still subject to first-text, inactivity, active-generation and hard limits.

Once a reasoning stream has actually been observed for a model, structured planning requests that model with thinking disabled. Chat behavior is unchanged. This is evidence from a real request, not a model-name allowlist.

## 🤖 Autonomous Execution

The execution engine is unchanged in authority and stricter in what it accepts as progress:

- task-scoped tools and least-authority capability selection;
- dependency-aware scheduling with workspace read/write leases;
- durable checkpoints, process ownership and recovery across restarts;
- local evidence verification before any task is complete;
- Termux and Debian PRoot execution with concurrent independent operations;
- the Live Worker Console, now including strategy, continuation and reasoning events.

A planning strategy is a workload decision. It never grants permission, bypasses a workspace guard, or authorizes a shell command.

## 🔋 Mobile Performance

Project Endurance remains integrated: Eco, Balanced, Performance and Adaptive modes, hardware-aware concurrency, thermal and battery ceilings, opt-in bounded calibration, exact-prefix KV reuse, and Eco idle release. Quick, Standard, Patient, Extended and Custom execution profiles remain separate from energy mode. No battery or speed improvement is claimed for this release.

## 🛡️ Reliability & Privacy

- No new Android permission, exported component, service or network destination.
- No change to the bundled llama.cpp source or native dependencies.
- No new telemetry; capability profiles stay in app-private storage.
- Unchanged debug signing certificate, so in-place upgrade keeps user data.
- Existing objectives, workspaces, models and credentials are not deleted.

## 🧪 Testing & Compatibility

- Clean Android build: `clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`, **BUILD SUCCESSFUL in 1m 36s**.
- **934 JVM tests across 96 classes; 0 failures, 0 errors, 1 environment-dependent skip.**
- Instrumentation APK compiled, not executed: no Android device is attached to the build host.
- Package `com.nyx.ai`, versionCode `21`, versionName `0.8.8`, minSdk `26`, compile/target `37`.
- `arm64-v8a` only.
- Unchanged signer SHA-256 `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`.
- 16 KB ZIP alignment passed.
- No AAB was generated and nothing was published to Google Play.

## 📋 Known Limitations

- Capability learning and strategy selection have **not** been validated against a real Ollama model on physical Android hardware.
- The observed slow-planning issue is **not confirmed resolved**; this release reduces unnecessary planning calls but does not benchmark a real device.
- Guided mode is inference-only and cannot perform terminal or file operations.
- Direct mode confirms a non-empty output, not semantic correctness.
- Capability thresholds are heuristics, not guarantees. A 24-hour cache can go stale after a model or server update; re-run the diagnostic.
- No hardware performance or battery improvement has been quantitatively established.
- The APK is debug-signed. It is a development build, not a production Play Store artifact.

## Install

Install `NYX-v0.8.8.apk` on a 64-bit ARM Android device running Android 8.0 (API 26) or newer. Installations signed with the established debug certificate can update in place; back up important data first. Model weights are not distributed with NYX.

Read the [implementation report](V088_IMPLEMENTATION_REPORT.md) for architecture, invariants and the device-verification procedure.

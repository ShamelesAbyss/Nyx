# NYX v0.8.8 — Project Insight Implementation Report

## Scope

This release changes planning workload selection and planning observability. It does not add tools, permissions, providers, background services or network destinations. It preserves the v0.8.7 execution policies, workspace leases, checkpointing, recovery, evidence verification, signing identity and user data.

## Problem

NYX asked every objective for a complete validated JSON task graph before executing anything. On a phone running a small or slow local model this produced two failures:

1. **Long silence before useful work.** A complex goal required a large structured response; any delay or validation failure blocked all progress.
2. **Invisible reasoning.** Ollama streams reasoning in `message.thinking`, and OpenAI-compatible servers may use `reasoning_content`. The client ignored both, so a thinking model could generate for minutes while NYX reported no text at all. A user could not distinguish "working" from "stalled".

## Implementation

### 1. Capability model and cache

`network/ModelCapability.kt` adds:

- `ModelIdentity` — provider id, provider kind, model, context length and CPU threads. Any change invalidates the profile.
- `ModelCapabilityProfile` — tri-state (`SUPPORTED`, `UNSUPPORTED`, `UNKNOWN`) for schema output, native tools, validated text actions, reasoning stream and provider statistics; observed first-text latency and measured throughput.
- `ModelCapabilityPolicy` — pure mapping from the bounded diagnostic to a profile, throughput calculation, observation learning, staleness and strategy selection.
- `ModelCapabilityStore` — bounded (32-entry), fingerprint-keyed, synchronized cache persisted to app-private JSON, with a 24-hour TTL.
- `ModelCapabilities` — process-wide store initialized once with app-private storage.

Capabilities are learned only from provider-observable behavior. A model name never implies a capability. A prompt-only JSON sample is recorded as `UNKNOWN`, because it does not prove constrained decoding.

### 2. Strategy selection

`PlanningStrategy` is one of `DIRECT`, `INCREMENTAL`, `FULL`, `GUIDED`.

| Rule | Result |
| :--- | :--- |
| Short (≤ 400 chars, ≤ 2 non-blank lines), explicit text goal, no resource verb or path | `DIRECT` |
| Schema output or validated text actions unsupported | `GUIDED` |
| Complex goal + tools + schema supported + ≥ 6 tok/s + ≥ 4096 context | `FULL` |
| Everything else | `INCREMENTAL` |

`DIRECT` builds a single inference task with non-empty-output evidence and no model planning call. It is intentionally narrow: any mention of files, commands, builds, tests, installation, browsing or publication disqualifies it.

`INCREMENTAL` caps the initial profile at two steps and 384 output tokens. `GUIDED` uses a 160-token plain-text prompt and is inference-only. `FULL` retains the previous full-graph behavior.

### 3. Incremental continuation

`autonomy/IncrementalPlanning.kt` owns the next-step prompt and strict parsing:

- JSON: exactly `{"next":{"do":"…","worker":"…"}}` or `{"done":true}`.
- Guided: exactly `NEXT <worker>: <instruction>` or `DONE`.
- A next step is wrapped in the existing strict plan schema and validated by `ObjectivePlanValidator` and `CompactPlanningPolicy.validateWorkers`. Unavailable workers, missing tool evidence and prose are rejected.
- NYX assigns the task id, ordinal, dependency on the last completed task, retry limits and evidence encoding.

`ObjectiveExecutionEngine` requests a continuation only after every existing task has reached `completed` with passing evidence. It allows at most six continuations. A continuation failure produces `needs_attention`, never a fabricated task or a success claim. Reaching the bound also produces `needs_attention`, because NYX cannot prove the objective is complete.

### 4. Reasoning stream

`OllamaClient` now reads `message.thinking` (Ollama) and `delta.reasoning_content` / `delta.reasoning` (OpenAI-compatible):

- reasoning characters are counted separately in `ChatResult.thinkingCharacters`;
- `InferenceOptions.thinkingObserver` receives deltas for progress only;
- reasoning is never appended to `content` and never passed to `onToken`;
- `InferenceOptions.think` sends Ollama's `think` parameter only when explicitly set.

`PlanningLifecyclePolicy` adds a `REASONING` stage and a separate `reasoningCharacters` counter. Reasoning resets inactivity and suppresses a false "no first text" timeout, but it does not satisfy first-text, output or validation requirements. Stalls are reported only when both text and reasoning stop.

Once a request observes reasoning for a model, the capability store records `reasoningStream = SUPPORTED`. Subsequent structured planning requests set `think = false` for that identity. Chat requests are unchanged.

### 5. UI and diagnostics

The Models diagnostic now appends the measured capability summary to its existing result and persists the profile. The Live Worker Console receives new events: `planning_strategy`, `planning_direct`, `planning_continuation`, `planning_expanded`, `planning_continuation_done` and `planning_reasoning_observed`. Planning progress reports reasoning characters separately from plan characters.

## Safety invariants preserved

- No new Android permission, exported component, service or network destination.
- Model output still cannot grant authority, choose an arbitrary working directory or execute an unvalidated shell command.
- Tool steps require local evidence; inference output existence is not factual verification.
- Every task still passes the workspace guard, command policy, autonomy grants and evidence verifier.
- Existing objectives, snapshots and Room schema are unchanged; no migration is required.
- The signing certificate is unchanged, so in-place upgrade is possible.

## Verification

| Check | Result |
| :--- | :--- |
| Clean build | `clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`, BUILD SUCCESSFUL in 1m 36s |
| JVM tests | 934 tests, 96 classes, 0 failures, 0 errors, 1 skipped |
| Skip reason | Host Kotlin compiler fixture requires a real `kotlinc`; not an app dependency |
| Instrumentation | Compiled only; no Android device attached |
| Package | `com.nyx.ai`, versionCode 21, versionName 0.8.8, minSdk 26, compile/target 37 |
| ABI | `arm64-v8a` only |
| APK SHA-256 | `e43046bfec8341b6f0ce57df349c8e772797208f261b408279010230ddc0f4c6` |
| Android test APK SHA-256 | `31b13d01dfa7058f4eeea6bb7b2b55604234f1f23fbc417aa2f8a7751b0e4aaf` |
| Signer SHA-256 | `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` |
| 16 KB alignment | Passed (`zipalign -c -P 16 -v 4`) |
| AAB / Play | Not generated, not published |

New tests cover capability mapping, throughput, cache invalidation and persistence, strategy selection, direct plans, JSON and guided continuation parsing, unavailable workers, missing tool evidence, prose rejection, reasoning-versus-text accounting, both reasoning wire formats and conditional `think` control.

## Device verification procedure

1. Install `NYX-v0.8.8.apk` over a build signed with the same certificate.
2. Select an Ollama model and run **Check selected model**. Confirm the capability summary appears and states schema output as yes, no or unverified.
3. Create a short text-only objective such as "Explain photosynthesis in one paragraph". Confirm the event stream shows `planning_direct`, one task starts, and no planning request is made.
4. Create a tool objective. Confirm `planning_strategy` reports `incremental`, the first plan has at most two steps, and `planning_continuation` appears only after evidence passes.
5. Use a thinking model and confirm the console reports reasoning characters while waiting, that reasoning text never appears in the plan, and that later structured planning reports thinking disabled.
6. Confirm an unavailable-worker or malformed continuation ends in review rather than execution.
7. Verify pause, cancel, process-death resume and workspace protection still behave as in v0.8.7.

## Limitations

- None of the above device steps has been executed on physical hardware in this build environment.
- Throughput thresholds are heuristics based on a short diagnostic, not a workload benchmark.
- Guided mode is inference-only by design.
- The 24-hour cache can be stale after a server or model update; re-run the diagnostic.
- Direct mode proves a non-empty answer, not a correct one.

## Publication state

Prepared as the v0.8.8 release candidate with local artifacts. Publication to GitHub is a separate, explicitly authorized step.

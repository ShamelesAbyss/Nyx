# NYX v0.8.5 — Project Sovereign Implementation Report

**Status: published as [GitHub release v0.8.5](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.5).**
Baseline: v0.8.4, versionCode 17, versionName 0.8.4. Candidate: versionCode 18, versionName 0.8.5, package `com.nyx.ai`.

## 1. Scope

This cycle repairs the durability and truthfulness of the existing autonomous execution path. It adds adaptive deliberation budgets, a bounded durable working state, task-scoped tool authority, a false-success guard, tolerant extraction of one structured action, repeated/consecutive failure stops, and a two-hour checkpointed objective watchdog. It preserves the existing planner, scheduler, verifier, workspace authorization, dependency gate, command policy, checkpoints, recovery, provider snapshots, and no-root/no-arbitrary-shell boundaries.

No model weights, signing material, credentials, user data, or AAB were added or produced. The bundled llama.cpp source was not modified.

## 2. Implementation map

| Area | File | Change |
| :--- | :--- | :--- |
| Adaptive policy | `app/src/main/java/com/nyx/ai/autonomy/SovereignPolicy.kt` | New pure `ReasoningDepth`, `SovereignBudget`, observation classification, completion-claim assessment, retry guidance, execution profile, and planner capability line. No Android imports. |
| Durable state | `app/src/main/java/com/nyx/ai/autonomy/SovereignWorkingState.kt` | New bounded state with facts, requirements, errors, next action, evidence, completed steps, JSON round-trip, checkpoint extraction, and prompt rendering. |
| Tool scope | `app/src/main/java/com/nyx/ai/autonomy/TaskCapabilityScope.kt` | New least-authority scope for inference, filesystem, verification, terminal, and development tasks, intersected with actually available tools. |
| Structured actions | `app/src/main/java/com/nyx/ai/agent/StructuredActions.kt` | Optional short `summary`; new `parseTolerant` that extracts exactly one complete JSON object from fences/surrounding text and still applies the strict schema, allowlist, and unknown-field checks. |
| Agent runner | `app/src/main/java/com/nyx/ai/agent/AgentRunner.kt` | Optional `SovereignRunConfig`; adaptive model/tool/step budgets; decision and observation summaries; bounded action repair; consecutive/repeated failure stops; false-success rejection; working-state persistence. |
| Objective engine | `app/src/main/java/com/nyx/ai/autonomy/ObjectiveExecutionEngine.kt` | Derives depth/budget from task and performance mode; scopes tools; persists/restores working state in `checkpointJson`; includes state and retry evidence in context; treats false-success risk as a failed tool observation; records `execution_profile`, `tool_scope`, `decision_summary`, and `completion_guard` events; planner sees actual tools. |
| Objective service | `app/src/main/java/com/nyx/ai/service/ObjectiveExecutionService.kt` | Watchdog extended from 30 minutes to two hours; existing lease, thermal, memory, battery, pause, cancel, and review paths unchanged. |
| Tests | `app/src/test/java/com/nyx/ai/autonomy/SovereignPolicyTest.kt`, `SovereignStateScopeTest.kt`, `app/src/test/java/com/nyx/ai/agent/StructuredActionsTest.kt` | New policy, state/scope, checkpoint, and tolerant-parser regressions. |
| Version | `app/build.gradle.kts` | `versionCode 18`, `versionName 0.8.5`. VersionCode 18 is required because 0.8.4 already used 17. |

## 3. Behavior and safety invariants

### Adaptive deliberation

`SovereignPolicy.depthFor` selects LIGHT, STANDARD, or DEEP from worker kind, task complexity, and observed constraints. `budgetFor` returns hard ceilings for model calls, tool calls, steps, context characters, repair attempts, and consecutive failures. Eco and low-memory/thermal/battery constraints reduce the budget; Performance mode may raise it within fixed caps. The model cannot widen its own budget.

`AgentRunner` enforces the limits before each model or tool call. A task that reaches a budget stops with an honest "no completion is assumed" result rather than looping indefinitely. The protocol asks for short decision and observation summaries, not hidden reasoning.

### Durable working state

The engine stores a `sovereignState` document inside the existing task `checkpointJson` at running, verifying, completed, failed, and needs-review phases. The state is bounded to 12 facts, 8 requirements, 8 errors, 8 evidence pointers, and 12 completed steps, with each field capped at 600 characters. It is restored after a retry or process restart and included in bounded context. No credentials or full tool transcripts are stored.

### Task-scoped tools

`TaskCapabilityScope` starts from the configured tools, intersects them with tools actually available on the run, and then applies the task-kind policy. Inference cannot write files or run shell/project commands. Filesystem cannot run shell/project commands. Verification may run only the fixed `test_run` command plus read-only tools. Terminal and development receive the full approved set. The existing command policy remains the final gate for any model-directed shell command.

### False-success guard

For non-inference tasks with at least one available tool, `AgentRunner` requires a successful tool observation before accepting a completion claim. The assessment is deterministic: it looks for completion language, excludes honest blockers, and checks recorded evidence/exit codes. A first unsupported claim is rejected with a request for evidence or a blocker. A repeated claim returns an unverified result, and the engine adds a synthetic tool failure so verification cannot pass.

### Bounded repair and failure stops

Strict parsing is attempted first. If it fails, `parseTolerant` may extract exactly one balanced JSON object from a Markdown fence or surrounding prose. Multiple objects, shell prose, unknown fields, unauthorized tools, and schema violations remain invalid. At most two repairs are requested. The runner stops after three consecutive failed/denied tool observations, and also stops when the same failing action signature repeats after two prior failures.

### Long-running execution

The objective service watchdog is now `2 * 60 * 60_000L`. The existing lease heartbeat, thermal/memory/battery gates, pause/cancel controls, and explicit review of uncertain mutating work are unchanged. The watchdog is an upper bound, not a guarantee of process survival.

## 4. Verification

Executed from `/home/ubuntu/nyx` on 2026-11-14 UTC:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

- Result: `BUILD SUCCESSFUL in 1m 21s`.
- JVM tests: **731**, failures **0**, errors **0**, skipped **0**, across **70 classes**.
- New focused tests: 9 `SovereignPolicyTest`, 6 `SovereignStateScopeTest`, 3 tolerant-parser cases.
- ARM64 debug APK: package `com.nyx.ai`, versionCode 18, versionName 0.8.5, minSdk 26, compile/target SDK 37, `arm64-v8a`.
- Instrumentation APK compiled but was not executed; no Android device or `adb` was available.
- APK SHA-256: `024c5fa4ab91a7d28b19f8fe22c362766109470e72a615a82f81501a1d8aa456`.
- Instrumentation APK SHA-256: `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`.
- Source archive SHA-256: recorded in the sidecar `NYX-v0.8.5-SHA256SUMS.txt` manifest.
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`.
- 16 KB ZIP alignment: `passed (`zipalign -c -P 16 -v 4`)`.

## 5. Device-validation checklist

The following remain unverified and must be exercised on a real ARM64 Android device with a trusted GGUF or configured provider:

1. Install over v0.8.4 with the established certificate; confirm app data, chats, models, and workspaces remain.
2. Run a simple inference objective and confirm `execution_profile` and `decision_summary` events appear in the Live Worker Console.
3. Run a terminal task that produces a file; confirm the false-success guard does not block a genuinely evidenced result.
4. Run a task whose model claims success without using a tool; confirm the first claim is rejected and a repeated claim fails verification.
5. Force-stop NYX during a running objective; relaunch and confirm the persisted working state is restored with the task checkpoint.
6. Run a verification task; confirm write and arbitrary-shell tools are absent, while the fixed test command remains available.
7. Run a development task; confirm the full approved tool set remains available and existing workspace authorization still applies.
8. Leave a long objective running past 30 minutes but under two hours; confirm the service, lease, thermal, memory, battery, pause, and cancel behavior.
9. Exercise malformed/fenced action responses if the provider supports text actions; confirm bounded repair and no shell inference from prose.

## 6. Limitations

- No physical-device validation of the Sovereign loop, task-scoped tools, false-success guard, durable recovery, or long-run behavior was performed in this cycle.
- No real provider/model was used for the new loop; tests are JVM policy/state/parser tests plus compilation.
- "Replanning" in this release means bounded plan repair plus changed-approach retry guidance, not an unrestricted planner loop.
- The two-hour watchdog is a local upper bound; Android may stop the process earlier.
- The APK is debug-signed and ARM64-only. It is not a Play Store artifact.
- No AAB was generated. The APK, instrumentation APK, source archive, and checksum manifest are published on GitHub.

# NYX 0.5.0 Development Handoff

## Release identity

versionName `0.5.0`, versionCode `5`, application ID `com.nyx.ai`, min API 26, target/compile API 35. Existing chat, Ollama/OpenAI-compatible inference, encrypted credentials, agents, workflows, dark-violet branding, and Fold-adaptive layouts are preserved. The remote GitHub repository was not modified in this pass.

## What actually changed

**Execution ownership moved to a service.** `ObjectiveExecutionService` is a `dataSync` foreground service with a visible notification and Pause/Cancel actions. It acquires a durable lease, renews it with a heartbeat, constructs the inference session from Room plus the Keystore vault, and runs `ObjectiveExecutionEngine`. `NyxViewModel` now only starts/pauses/cancels and observes Room plus `ExecutionMonitor`. This closes the v0.4 gap where closing the Activity's ViewModel could abandon work.

**Concurrent, dependency-aware scheduling.** `ConcurrentTaskScheduler` selects ready tasks by priority and per-worker-kind capacity; `ResourcePolicy` derives those capacities from real RAM, thermal status, battery, and storage. Blocking propagates transitively, so a task behind a failed prerequisite is reported as blocked instead of appearing stalled. Deadlock detection only fires on genuine pending dependency cycles.

**Verification replaced self-reporting.** `TaskVerifier` evaluates exit codes, required/forbidden output, file existence, file contents, and SHA-256 through a `FileProbe`. Any failed or denied tool call fails the task. When a plan declares no evidence, the result is explicitly labelled as weak proof rather than success. `RecoveryPolicy` retries only read-only verification work, fails permanently on a repeated identical failure signature, and sends anything state-changing to human review.

**Terminal bridge is now real and bounded.** `TerminalBridge` wraps the existing `TermuxIpcAdapter` in a suspend, cancellable API; `EnvironmentDiscovery` probes the actual Termux host, `PREFIX`, `HOME`, tools, and installed PRoot distros; `CommandPolicy` classifies and blocks root/protected-Android/disk operations; `ShellCommandBuilder` builds argv-only requests with quoted working directories and optional coreutils `timeout`; `TerminalRepository` persists every attempt with real stdout, stderr, exit code, and approval state. `TerminalAgentTools` exposes workspace-scoped file and build/test tools and implements `FileProbe`.

**Provider-neutral inference.** `InferenceProvider`, `OllamaInferenceProvider`, `OpenAiCompatibleInferenceProvider`, `ProviderFactory`, and `ProviderCapabilityCache` unify transports. `AgentRunner` now accepts either the legacy client or a provider plus extra tools, so existing chat/agent call sites keep working.

**Context and evidence persistence.** `ContextStore` writes confined, atomic, UTF-8 context records with metadata; `ContextEngine` assembles bounded, relevance-ranked context including dependency outputs and failure history. Room schema v3 adds terminal history, task evidence, context records, leases, and task worker/priority/evidence/failure fields through an additive 2→3 migration.

## Priority status

| # | Directive | Status |
|---|---|---|
| 1 | Service-owned durable execution | Implemented; device kill/restart behavior unverified |
| 2 | Dependency-aware parallelism + resource limits | Implemented and unit-tested |
| 3 | Evidence-based verification | Implemented and unit-tested |
| 4 | Persistent context store | Implemented and unit-tested |
| 5 | Provider abstraction | Implemented and unit-tested |
| 6 | Real Termux/Debian bridge | Implemented in UI and tools; device-unverified |
| 7 | Development workflow (edit/build/test/repair) | Partial: tools exist (`project_detect`, `build_run`, `test_run`, file edit) and the planner can emit `development` tasks; no dedicated multi-iteration repair orchestrator |
| 8 | Bounded recovery | Implemented: retry budgets, signature de-duplication, human review for side effects |
| 9 | Terminal UI + audit history | Implemented |
| 10 | Control center | Extended with Terminal panel, phase, worker kind, evidence-aware copy; no separate approval queue screen |

## Test evidence

`./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest` passes. 98 JVM tests, 0 failures. Debug APK assembled; instrumentation APK compiled. Full counts and device-only gaps are in `TEST_RESULTS.md`.

## Known limitations, stated plainly

- Native GGUF/llama.cpp inference is still absent; NYX requires an external Ollama or OpenAI-compatible endpoint.
- A killed process stops execution. On restart, interrupted tasks are marked `needs_review`; NYX does not resume mid-task.
- Debian/PRoot execution is implemented but has not been validated on hardware.
- `timeout` guarantees child termination only when coreutils `timeout` exists in the selected environment.
- Cancelling a command without `timeout` stops NYX waiting; the Termux process may continue.
- No autonomous publication, credential access, privilege escalation, or Android UI automation.
- No production signing; the APK is debug-signed.

## Next step

On the owner's device: run `connectedDebugAndroidTest` for the Room 1→3 migration and Keystore suites, then exercise the Terminal panel against the installed Termux build (`allow-external-apps=true`, RUN_COMMAND granted), confirm real exit codes, and test a small objective with terminal tools enabled in a scratch workspace before pointing NYX at a real project.

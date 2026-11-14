# NYX v0.8.6 — Project Ignition

Status: unpublished candidate. Package `com.nyx.ai`, versionCode 19, versionName 0.8.6. No commit, push, tag or release is authorized or performed for this candidate.

## Device report and source-supported causes

The owner reported a Pixel 10 Pro Fold running local Ollama `qwen2.5-coder:3b`: a Kotlin command-line calculator objective stalled during planning beyond 120 seconds, persisted no steps, and ran no tools. Three observed characters do not establish whether the model was loading, queued, evaluating the prompt, disconnected or struggling with the schema. This failure was not reproduced on hardware here.

The v0.8.5 source imposed the same verbose full task-graph response on straightforward and complex objectives, forwarded ordinary unbounded generation options into planning, ignored streamed planning callbacks, and applied a flat 120-second deadline. These are concrete workload and observability defects, not proof of the unique device root cause. Kotlin command-line work also lacked its own standalone toolchain/project execution path.

## Planning workload and validation

`CompactPlanningPolicy` selects a compact one-to-three sequential-step response for simple goals, capped at 512 output tokens. More complex goals retain the existing full graph with a 1024-token cap. The compact response carries instructions, worker kind and a small evidence check; NYX generates stable task identities, sequential dependencies and verification descriptions and sends the expanded graph through the existing strict validator. Full graphs remain accepted. Tool-dependent steps without locally observable evidence are rejected.

Strict single-object extraction handles fences and surrounding prose but rejects duplicate keys, truncation, multiple JSON values, mixed schemas and oversized responses. Completed invalid responses get exactly one repair. Empty, transport-failed, cancelled, incomplete or tool-calling planning responses do not silently become tasks. The entire plan must validate before persistence/execution; existing dependency, authorization, resource and task-scope gates remain in place on fresh runs and resumption. Goals over 8000 characters fail explicitly rather than silently planning a prefix.

This is compact initial planning, not unrestricted incremental replanning. A model-generated plan can still be incorrect despite valid syntax and evidence definitions. Inference-only output evidence proves nonempty output, not semantic accuracy.

## Observable inference lifecycle

Planning now observes request-local text callbacks. It distinguishes unknown admission/waiting, first text and active text streaming; useful progress includes characters, elapsed time and time since the last chunk. It has a 120-second first-text bound, 60-second stall bound, 240-second active-generation bound and 300-second hard request limit. Late chunks cannot hide deadlines already breached. Slow continuing text may proceed beyond the former blanket 120 seconds; silent streams terminate with a stall explanation.

The current provider interface has no request-scoped queue/admission callbacks. Fabric queued/active counts are displayed only as aggregate counts and are not attributed to this objective. Model loading and prompt evaluation timing remain unknown during a request. The lifecycle policy supports admission-relative/queue deadlines when explicit hooks are available, but those hooks are not fabricated. Final Ollama load/generation counters are optional retrospective telemetry. Cancellation is cooperative; JNI worker cleanup can outlast the requested cancellation deadline.

## Provider compatibility and manual diagnostic

Nullable `maxOutputTokens` and `responseSchema` options preserve ordinary chat defaults. Ollama uses its documented top-level object-valued `format` and nested `options.num_predict` [1] [2]. Compatible `/v1` endpoints receive explicit `max_tokens`, never guessed Ollama fields or `response_format`. Native GGUF maps the bound onto its existing token limit, capped at 4096, preserving context, threads and seed; it does not claim schema enforcement. Some compatible servers may reject `max_tokens`. Ollama cloud structured output is currently unsupported [2].

`ChatResult.stats` carries actual provider counts/durations when supplied. Missing data is unknown; text chunks are never mislabeled as tokens. Truncated streams no longer masquerade as completed responses. HTTP inventory/frame sizes and diagnostic text are bounded, and slow inventory-body reads are cancellable.

**Models → Check selected model** explicitly runs one inventory request and one tiny no-tools JSON request, with a 32-token cap and 30-second cancellation budget. It checks listing separately from generation access and sample format compatibility; measures first-text latency from submission, total time and provider-reported token throughput when available. Queue/load are included in first-text latency, not separately measured. A short sample is neither a workload benchmark nor proof of general schema enforcement. Nothing runs automatically on startup, pulls models or changes settings. Active chat, agent, workflow, objective, calibration and native loading block diagnostic launch; diagnostic state guards conflicting launches and idle release.

## Kotlin/JVM and authorized execution

Task-local dependency scope now prepares independently required toolchains rather than merging unrelated descriptions. `KOTLIN_CLI` requires shell, Java and kotlinc without Android SDK or Gradle; `JAVA_JVM` requires Java/javac. Existing Android/Gradle workflows remain. Actual Android development-target evidence is required rather than treating a mention of an Android phone as the project type. Packages still use trusted candidates, guest-native origin checks and installation approval gates.

Standalone discovery supports bounded root-level Kotlin, Java and shell sources with fixed build/test commands. Kotlin uses `TestMain.kt`, Java `TestMain.java`, or shell `test.sh` as behavioral test entry points. Empty authorized workspace creation can request exact `MISSING`-hash patches without inventing a baseline build system. Creation still must pass patch confinement, checkpoints, fresh discovery and real build/tests before success. Existing parent-directory confinement is retained; arbitrary layouts/frameworks require a supported build system. Missing post-creation tools stop with checkpoint/diff and review requirement; no hidden install or rollback is introduced.

The real Linux-host fixture creates a calculator, compiles it and executes behavioral assertions, including a deliberately failing assertion. This tests deterministic orchestration with a fixture proposal, not autonomous generation by qwen or Android PRoot execution.

## Agent-loop and recovery regressions

New tests call the actual `AgentRunner.run` with deterministic fake providers/tools: unsupported native function-calling fallback, text actions, bounded malformed-action repair, tool allowlisting, nonzero command evidence overriding optimistic output, consecutive/repeated failure stops, unsupported completion rejection and recovery through successful observed evidence. A fallback boundary found during inspection is fixed: native-function rejection cannot consume a second model call after the caller's model-call budget is exhausted.

The completion guard establishes presence of successful observations; the task verifier still checks declared evidence. Neither guard proves arbitrary natural-language correctness. Existing Room schema/data, checkpoints, uncertain-side-effect review, workspace permissions, process ownership, resource guards and Endurance modes are preserved. No native llama.cpp files were modified.

## Final build verification

Final facts are recorded in [TEST_RESULTS.md](TEST_RESULTS.md) after the clean combined build. Instrumentation compiles but is not executed; no physical device is connected. APK package, version, unchanged signer and 16 KB alignment are verified on the artifact. The source archive omits caches, signing material, private configuration and model weights. No AAB is produced.

## Pixel validation procedure

1. Back up important state and install the candidate over v0.8.5 without uninstalling. Confirm package/version and signer compatibility.
2. Keep existing Termux external-command permission and Debian PRoot setup; select the verified guest and an explicitly approved workspace. Do not rewrite Debian repositories. Confirm Java/kotlinc availability or approve the displayed trusted dependency installation.
3. Select `qwen2.5-coder:3b` on the loopback Ollama endpoint. Run **Models → Check selected model** once; retain its format result, first-text latency and actual counters. A 30-second timeout is evidence about this short check, not proof the model is defective.
4. Create a development objective: “Create a standalone Kotlin command-line calculator in this approved workspace. Include TestMain.kt behavioral assertions for addition, subtraction, multiplication, division and divide-by-zero. Build and run the tests. Do not create an Android app.”
5. Capture the run-scoped Live Worker Console. Confirm a complete validated compact plan before any tools; no Android SDK requirement; guest-native executables and workspace confinement; generated files, build/test exit codes and declared evidence before completion.
6. Intentionally introduce a failing assertion on a separate backed-up fixture; confirm nonzero status is observed and bounded correction or a genuine blocker is reported rather than false success.
7. Test pause/restart and explicit review for uncertain mutations. Check previously completed work is not blindly repeated and checkpoints remain associated with the objective. Test a silent provider separately from a slow streaming provider, and user cancellation.
8. Repeat under Eco/Adaptive and thermal or low-memory conditions without bypassing safeguards. Record actual end-to-end timings and battery measurements if making comparisons. No completion-rate, speed or battery improvement is measured in this sandbox.

## Remaining limits

The original Pixel failure is not confirmed fixed on-device. No real qwen/Ollama/native model exercised the entire new objective loop here. Provider HTTP behavior is tested with MockWebServer and lifecycle with deterministic clocks. Compose diagnostic wiring compiles but has no hardware UI test. Root-level standalone layouts are intentionally limited; capabilities that lack explicit telemetry remain unknown. Native cooperative cancellation is not a realtime guarantee. Full incremental plan expansion and generalized environment/process-lifecycle redesign were not added.

## References

[1]: https://docs.ollama.com/api/chat "Ollama chat API"
[2]: https://docs.ollama.com/capabilities/structured-outputs "Ollama structured outputs"
[3]: https://docs.ollama.com/modelfile "Ollama generation parameters"

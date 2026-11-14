# NYX v0.8.6 — Project Ignition

**Unpublished candidate. No push, tag or GitHub release has been performed.**

Project Ignition reduces the up-front planning burden on small models while preserving the authorization and evidence gates that decide whether an objective may act and complete. It repairs source-supported workload and observability defects discovered after the owner's Pixel calculator-planning failure; it does not claim that failure has been reproduced or fixed on hardware.

## Implemented changes

Simple objectives use a compact one-to-three-step plan with a 512-output-token limit; complex objectives retain full task graphs with a 1024-token limit. Compact responses are expanded deterministically and pass the existing validator before persistence or execution. Strict extraction handles one complete JSON object and bounded repair, not prose-as-command or partial task execution.

Planning now tracks actual streamed text and distinguishes progressing generation from a silent stall. Finite first-text, stall, active-generation and hard deadlines replace the blanket response deadline. Queue admission, model loading and prompt evaluation remain unknown where the provider offers no request-scoped telemetry; shared Fabric counts are explicitly aggregate observations.

Provider-specific options request Ollama JSON-schema output and `num_predict`, compatible HTTP `max_tokens`, or the supported native token cap. Ordinary chat defaults remain unchanged. Truncated responses no longer count as complete. Optional counters are provider-reported, not guessed from characters.

**Models → Check selected model** is a manual, no-tools 32-token diagnostic with a 30-second cancellation budget. It checks reachability, model listing versus generation accessibility, tiny JSON compatibility, first-text latency and token throughput when actual provider counters/durations exist. It never benchmarks on startup, pulls a model or silently retries.

Kotlin command-line and Java projects now have standalone toolchain/discovery/build/test paths without unnecessary Android infrastructure. Dependency preparation follows individual executable tasks. Authorized empty workspace creation uses strict missing-file hashes and must discover, compile and behaviorally test the created project before success. Termux/PRoot guest origins, installation permissions, confinement and interruption review remain intact.

Agent-loop regressions cover structured-action fallback, malformed repair, failed command observations, repeated/consecutive failures, unsupported completion and evidence-backed recovery. Native-function fallback now respects the model-call ceiling before issuing an additional request.

## Verification and distribution

Package `com.nyx.ai`, versionCode **19**, versionName **0.8.6**, minimum API 26, compile/target SDK 37, ARM64-only debug APK. The established signing identity and Room data are retained. Exact clean-build test totals and digests are in [TEST_RESULTS.md](TEST_RESULTS.md); download checksums are in the delivered `NYX-v0.8.6-SHA256SUMS.txt`.

A deterministic Linux-host fixture creates a Kotlin calculator, compiles it and runs behavioral assertions, including a deliberately failing assertion. This is not an Android/PRoot or real-model demonstration. Instrumentation compiled but was not executed. Bundled llama.cpp is unchanged. No AAB, Play submission, commit, push, tag or release is produced.

## Limitations

No physical Android device or real qwen model was used for end-to-end validation. The original Pixel failure still needs the procedure in [V086_IMPLEMENTATION_REPORT.md](V086_IMPLEMENTATION_REPORT.md). A syntactically valid plan or one successful diagnostic sample does not prove task correctness or general schema enforcement. Standalone project layouts are bounded, native cancellation is cooperative, generic compatible endpoints may reject token-limit fields, and Ollama cloud schema output is unsupported. No measured speed, energy or completion-rate gain is claimed. Unrestricted incremental replanning and broad process-lifecycle redesign were not added.

# NYX 0.7.5 Test Results

## Final automated build

Executed on 2026-11-12 UTC from `/home/ubuntu/nyx`:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
./gradlew --no-daemon testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin
```

Result: **BUILD SUCCESSFUL**.

- **598 JVM tests** executed: **0 failures, 0 errors, 0 skipped**.
- New suites include `ArsenalRepositoryTest`, `GatewayRepositoryTest`, `McpClientTest`, `GitHubGatewayTest`, `ProjectMemoryTest`, `SymbolIndexTest`, `OllamaProcessTest`, `OllamaLifecyclePolicyTest`, `CommandPolicyTest`, `TerminalResultPolicyTest`, `RoutineGrantPolicyTest`, and the workspace/verification/worker tests.
- Debug APK assembled: `app/build/outputs/apk/debug/app-debug.apk`.
- Android instrumentation-test APK compiled: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- SDK policy check passed: compileSdk/targetSdk 37, minSdk 26.
- The suite found and drove a real fix: NUL-separated `/proc/<pid>/cmdline` is now normalized before ownership classification.

## APK identity

| Property | Verified value |
| :--- | :--- |
| Package | `com.nyx.ai` |
| Version | `versionName 0.7.5`, `versionCode 9` |
| Compile / target SDK | 37 / 37 |
| Minimum SDK | 26 |
| Native ABI | `arm64-v8a` only |
| Debug APK | 33 MB, SHA-256 `91f8e718455224930ed08ebb2bb16e659bd76899b561b33e2ea08c2a2c9601f1` |
| APK signing | Android debug certificate, SHA-256 `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` |
| Instrumentation APK | 581 KB, compiled only |

The signing certificate matches the previously delivered debug APK, so an in-place update is possible over that build. This is not a Play Store release key.

## What the automated tests establish

- Kotlin compilation of the complete app, including Arsenal, Gateways, managed Ollama, project memory, symbol index, worker console, and navigation.
- Arsenal: nine built-ins validate as trusted; untrusted imports are size-bounded and reject unknown/escalating tools, reserved/duplicate IDs, unsafe constraints, and incompatible schemas; enable/configure/remove/log behavior is covered.
- Gateways: configuration validation, allowlists, per-run budgets, timeouts, credential references (never secrets) in config, MCP parsing, GitHub request construction, health degradation, and redacted logs.
- Project memory: evidence requirements, stale/unverified suppression, deduplication, bounded prompt rendering, and deterministic relevance.
- Symbol index: Kotlin/Java declaration and reference extraction, incremental hashing, persistence, and deterministic ranking.
- Ollama: installation/start/stop/restart parsing, PID plus start-token ownership, recycled-PID refusal, adopted-server protection, and bounded log normalization.
- Existing regressions: autonomy policy, workspace atomic creation, scoped routine grants, terminal result mapping, verification, recovery, Room entities, native inference configuration, and credential transport.

These tests do **not** prove Termux dispatch, PRoot execution, real Ollama process control, Android Keystore behavior, MCP/GitHub network calls, foreground-service lifecycle, notification actions, or restart recovery on a real phone.

## Not executed (no physical Android device)

- Installing/launching the APK on a phone or foldable.
- Real Termux `RUN_COMMAND` dispatch and PRoot guest verification.
- Real Ollama installation/start/stop/restart against a Termux process; PID-reuse defense is unit-tested only.
- Real MCP server or GitHub API calls, credential resolution, and token storage on hardware.
- Keystore encryption/decryption and Room migration instrumentation tests on device.
- Foreground-service notification, process-death recovery, thermal pause, and notification permission behavior.
- Any performance, battery, or memory measurement of a real inference run.

These are **validation gaps**, not claims of failure. No physical device was available in this environment.

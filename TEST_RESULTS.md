# NYX 0.6.0 Test Results

## Final clean automated build

Executed on 2026-11-12 UTC from the frozen application source:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: **BUILD SUCCESSFUL in 56 seconds**, 81 actionable tasks, 79 executed and two up-to-date. The application APK and instrumentation APK were both assembled. **212 JVM tests ran: zero failures, zero errors, zero skipped.** No emulator or physical Android device was used.

## Executed JVM suites

The following counts come directly from Gradle's final XML reports, not test-file estimates:

```text
ConcurrentTaskSchedulerTest          9
AgentToolsTest                      2
ObjectivePlanValidatorTest          7
StructuredActionsTest               8
ResourcePolicyTest                  6
TaskGraphSchedulerTest              4
VerificationSecurityTest            3
VerificationTest                   14
ContextEngineTest                  11
ContextFoundationTest              19
DevelopmentOrchestratorTest        10
DevelopmentPatchParserTest          6
GgufMetadataParserTest             31
NativeTokenSinkTest                 4
InferenceFabricTest                 3
OllamaClientAuthTest                5
ProviderCapabilityTest              8
CredentialTransportPolicyTest       2
InstalledAppVersionTest             1
SkillFoundationTest                15
CommandPolicyTest                  18
EnvironmentProbeParserTest          5
TerminalAgentToolsSecurityTest     15
TerminalProtocolTest                6
TOTAL                            212
```

Coverage includes malformed GGUF metadata/tensor bounds, token decoding, strict text actions, shell/path denials, stale source hashes, original evidence preservation, compaction/checkpoints, skill authority ceilings, bounded exact-hash edits, repeated failures, verification, actual command supervision, inference serialization, queue cancellation, SDK labels and authenticated HTTP routing.

## Real-model native host execution

The native host verification loaded external Apache-2.0 SmolLM2-135M-Instruct Q4_K_M weights using the actual Kotlin JNI bindings and pinned C++ session. Its generated text was `The capital of France is Paris.` The native counter reported **seven selected non-EOG tokens, 31 characters**. It also passed abort/cancellation, session reuse, callback-error propagation and safe unload. The full host evidence is in `docs/verification/native-host.log`.

This was Linux x86_64 CPU execution, **not Android ARM64 execution or a benchmark of phone performance**. Weights are not bundled. The tiny model test does not demonstrate robust autonomous tool use or repair with a practical 3B/4B/7B model.

## Real development host fixture

The orchestrator's trusted Linux fixture ran actual Make/Python processes. Baseline build exited 0 and baseline test exited 2. A deterministic fake inference provider supplied an exact-hash edit. The repeated build/test exited 0 and 0; the original source backup and `41 -> 42` diff were retained. The outcome was `verification_passed`. Evidence is in `docs/verification/development-host.log` and the final JUnit XML output.

The processes, files and checks were real; the patch-producing provider was a test double. No real-model autonomous repair on Android is claimed.

## APK and native packaging

Android package metadata was read from the produced APK: package `com.nyx.ai`, versionName `0.6.0`, versionCode `6`, minSdk `26`, targetSdk/compileSdk `37`. The artifact contains `arm64-v8a` only. NDK 28.2.13676358/CMake 3.22.1 cross-compiled the runtime, with Android debug native flags `-O2 -g` and baseline ARMv8 NEON.

`apksigner verify --print-certs` passes. The debug signer is `CN=Android Debug`, SHA-256:

```text
aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55
```

This matches the delivered v0.5.0 APK, so same-package/same-signer in-place upgrade is technically compatible. Device installation and database preservation still need testing. Do not uninstall a data-bearing installation merely to test an update. Rebuilding on another machine may create a different debug certificate; signing keys are intentionally not distributed.

`zipalign -c -P 16 4` passes for the final APK. All LOAD segments in all four packaged libraries (`libnyx_llama.so`, `libc++_shared.so`, `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`) have alignment `0x4000`. These are artifact checks, not proof of runtime compatibility on a 16 KB device.

Final APK SHA-256:

```text
7e6fbb71c96ef865dcc951dc468c6c47273f1e0613cd5b20344e7e24a9424a58
```

The source archive and instrumentation APK checksums are supplied in the separate `NYX-v0.6.0-SHA256SUMS.txt`, avoiding a self-referential source-ZIP checksum inside its own archive. The source ZIP contains the wrapper, actual code, vendor source/licenses, tests and updated documents; generated caches, model weights, signing material and local SDK configuration are excluded.

## SDK and release guards

The final clean build executed both Gradle and Python SDK guards successfully: compile/target API 37 and unchanged minimum 26. The SDK-policy self-test accepted one valid configuration and rejected eight invalid configurations. Actual release filenames, current documentation headers, Gradle metadata and binary manifest are checked with `tools/check_release_integrity.py` during final packaging.

Build warnings are non-blocking: installed SDK tools advertise a newer XML version than a consuming parser understands; vendored llama's version probe reports no Git metadata in the source snapshot; Compose Divider is deprecated; two Migration override parameter names differ from the updated parent signature. KSP and Room incompatibility failures were fixed with KSP 2.3.6/Room 2.8.5; no API fallback or permanent Kotlin source-set suppression remains.

## Compiled but not executed on Android

Keystore encryption/tamper/deletion, additive Room migration preservation, private GGUF import/removal and real-model load/stream/cancel/reuse/unload instrumentation tests compiled. The real-model test requires a supplied readable modelPath and assumption-skips without it. Neither an omitted model argument nor successful instrumentation compilation is counted as native execution.

Physical checks remain for installing/upgrading, SAF storage, notifications and action controls, native JNI loading on API26–37, real heat/memory/battery pressure, Android 17 limiter exits, GrapheneOS networking/security, LAN permission denial/revocation, Termux RUN_COMMAND plus allow-external-apps and callbacks, Debian timeout/cancellation, foreground-service restrictions, process death/lease reconciliation, and phone/foldable resize/insets.

Use the procedures in `docs/NATIVE_INFERENCE.md`, `docs/DEVELOPMENT_ENGINE.md`, `docs/ANDROID_COMPATIBILITY.md` and `USER_GUIDE.md`. Record exact hardware/OS/page size/model/hash/context/threads plus observed output and exit evidence. No Android-device integration, completion-rate benchmark, production signing or Play approval is inferred from these tests.

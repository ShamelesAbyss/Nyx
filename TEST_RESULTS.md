# NYX 0.6.1 Test Results

## Final clean automated build

Executed on 2026-11-12 UTC from the frozen application source:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: **BUILD SUCCESSFUL in 56 seconds**. The debug application APK and the instrumentation-test APK were both assembled.

## JVM tests

**350 tests ran: 0 failures, 0 errors, 0 skipped.** Counts were read from the Gradle XML result files, not from console summaries.

Coverage added or strengthened in this release:

| Area | Tests |
| :--- | :--- |
| PRoot distro-list formats, failure-versus-empty, nested-PRoot refusal | `DistroListingTest` |
| Guest probe marker/facts/tools and host-versus-guest identity | `DistroListingTest` |
| Isolated host request (`--noprofile --norc`, `BASH_ENV`/`ENV` cleared) and alias rejection | `DistroListingTest` |
| Capability states, paths, versions, storage, workspace writability | `EnvironmentCapabilitiesTest` |
| Workspace syntax, real-probe parsing, guest/Android path rules | `WorkspaceManagerTest` |
| Preflight blockers and readiness | `PreflightTest` |
| Goal classification and acceptance criteria/outcomes | `DevelopmentGoalTest`, `AcceptanceTest` |
| Relevance ranking and bounded context selection | `SourceRelevanceTest` |
| Batch source reading, markers, truncation, hash checks | `SourceBatchReaderTest` |
| Four required development fixtures plus unsafe path and missing toolchain | `VerifiedDevelopmentFixturesTest` |

Two existing tests were tightened rather than weakened:

- `alreadyPassingBaselineIsPreciselyReportedNotClaimedAsRepair` now requires `passed=false`, reason `baseline_healthy`, and outcome `BASELINE_HEALTHY`.
- `profile reports debian availability` now requires `debianAvailable=false` for a merely listed alias and `true` only with `verifiedDistros`.

## APK identity

| Field | Value |
| :--- | :--- |
| Package | `com.nyx.ai` |
| versionName | `0.6.1` |
| versionCode | `7` |
| compileSdk / targetSdk | `37` / `37` |
| minSdk | `26` |
| Native ABI | `arm64-v8a` |
| Debug signer SHA-256 | `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` |
| Application APK size | 33 MB |
| Instrumentation APK | 581 KB |

The signer digest matches the previously delivered debug builds, so an in-place update is possible when the installed copy uses the same certificate.

## What is actually verified

- Kotlin compilation of the complete application, including the new environment, workspace, preflight, capability, and verified-developer modules.
- All 350 JVM tests, including the four required development scenarios.
- Debug and instrumentation APK assembly and signing.
- Package metadata and ABI as reported by `aapt2`.

## What is not verified

No emulator or physical Android device was used. The following remain device-only checks and are not claimed as passing:

1. Termux `RUN_COMMAND` round trip with the owner's real Termux profile, including the isolated host shell.
2. PRoot guest verification on the device and correct reporting of a nested-PRoot configuration.
3. Debian workspace creation, write probe, and Android-path rejection on a real guest.
4. Capability versions and permission states for real tools.
5. Foreground-service execution, notification actions, pause/cancel, and process-death recovery.
6. Native GGUF load/generation on ARM64 hardware.
7. Fold/unfold layout and insets on the Pixel Fold.
8. Real-model autonomous development on a phone; the fixture provider is deterministic, not a model.

A debug-signed APK is a development artifact, not a production or Play Store release.

# NYX 0.7.7 Test Results

## Final automated build

Executed on 2026-11-12 UTC from `/home/ubuntu/nyx`:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
./gradlew --no-daemon clean testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin
```

The instrumentation APK was then assembled with:

```bash
./gradlew --no-daemon assembleDebugAndroidTest
```

Result: **BUILD SUCCESSFUL**.

- **609 JVM tests** executed: **0 failures, 0 errors, 0 skipped**.
- New v0.7.7 coverage includes `OllamaDiscoveryTest` (guest-only selection, preferred guest, host fallback, incompatible binary, missing installation, target identity), `OllamaProcessTest` (guest-origin/architecture/compatibility parsing, portable `nohup` startup, detection markers), and `AutonomyPolicyEngineTest` (repeated routine workspace creation in Full, approval in Manual).
- SDK policy check passed: compileSdk/targetSdk 37, minSdk 26.
- Debug APK assembled: `app/build/outputs/apk/debug/app-debug.apk`.
- Instrumentation-test APK assembled: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## APK identity

| Property | Verified value |
| :--- | :--- |
| Package | `com.nyx.ai` |
| Version | `versionName 0.7.7`, `versionCode 10` |
| Compile / target SDK | 37 / 37 |
| Minimum SDK | 26 |
| Native ABI | `arm64-v8a` only |
| Debug certificate SHA-256 | `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` |
| Debug certificate DN | `C=US, O=Android, CN=Android Debug` |

The signing certificate matches the previously delivered v0.7.5 debug APK, so an in-place upgrade is possible when the same certificate is trusted. The APK is still debug-signed and is not a production artifact.

## Artifact digests

| Artifact | SHA-256 |
| :--- | :--- |
| `NYX-v0.7.7.apk` | `e8e6e7d1fbe3aa8d680322adaa2894540c33133916aea6a3f552721940668e5f` |
| `NYX-v0.7.7-androidTest.apk` | `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` |

The source archive and checksum manifest are produced by the packaging script after the documentation is finalized; their digests are recorded in the delivered `NYX-v0.7.7-SHA256SUMS.txt`.

## What is actually tested

- JVM-only policy tests prove that `CREATE_WORKSPACE` is routine, that Full mode can create another project inside the authorized root without an elevated per-project grant, and that Manual mode still requires approval.
- JVM-only discovery tests prove deterministic host/guest selection from synthetic detection results, including Debian-only installations and incompatible binaries.
- JVM-only process tests prove the detection parser preserves target, architecture, and compatibility, and that the portable startup path uses `nohup` with duplicate protection.
- Existing 609-test suite covers command policy, ownership verification, lifecycle state transitions, workspace provisioning, terminal result mapping, verification, memory, skills, gateways, and autonomy concurrency.

## What is not device-verified

No physical Android device was attached to this build environment. The following are compiled and unit-tested but **not claimed as real-device success**:

- Termux `RUN_COMMAND` IPC and the actual Debian PRoot guest login.
- A real Debian-only `/usr/local/bin/ollama` process: start, `setsid`/`nohup` detachment, API readiness, model listing, Chat reconnect, stop, and restart.
- Android Keystore encryption/decryption, notifications, foreground-service lifecycle, process-death recovery, and Room migration on device.
- Real MCP/GitHub network calls, Arsenal tool execution, symbol-index prompt use, and Live Worker Console delivery.
- Fold/unfold layout and thermal/memory behavior.

## Known limitations

- The v0.7.7 APK, instrumentation APK, source archive, and checksum manifest are published in the v0.7.7 GitHub release.
- Guest detection probes at most four verified PRoot aliases to keep IPC bounded.
- The managed Ollama lifecycle does not install Ollama automatically; installation remains a user/provisioning decision.
- A model's prose is never accepted as evidence; verification still depends on real command/file outcomes.
- The global error banner is now limited to Chat, Models, Autonomy, and Settings; errors raised elsewhere may be cleared when navigating away.

# NYX Test Results

## 0.8.4 — Project Endurance (published build)

Published as [GitHub release v0.8.4](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.4).

Executed on 2026-11-14 UTC from `/home/ubuntu/nyx`:
```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```
Result: `BUILD SUCCESSFUL in 1m 20s`.
- JVM tests: **713**, failures **0**, errors **0**, skipped **0** across **68 classes**.
- New `PerformancePolicyTest`: **11 tests** covering SoC classification, fixed/adaptive modes, thermal/battery ceilings, hysteresis, calibration validity/invalidation, candidate bounds, calibration gating, best-sample selection, and the autonomy concurrency cap.
- The ARM64 debug APK is `com.nyx.ai`, versionCode **17**, versionName **0.8.4**, minSdk 26, compile/target SDK 37, `arm64-v8a` only.
- The instrumentation APK compiled but was not run. No `adb` binary or connected Android device was available in this build environment. The release owner reports that the Performance dashboard rendered on a Pixel 10 Pro Fold; this is UI evidence only, not a native inference, calibration, KV-reuse, or battery benchmark.
- Published APK SHA-256: `65a415a2e57d72575fd858d428e997db7b6377e10a3c8e9b701102285e025dce`.
- Instrumentation APK SHA-256: `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`.
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`, unchanged from the established NYX identity.
- APK 16 KB ZIP alignment check passed (`zipalign -c -P 16 -v 4`). Existing Compose `Divider` deprecation and Room migration parameter-name warnings remain.
- The native C++ exact-prefix KV reuse compiles and links but has no device/model runtime test. The source archive and checksum manifest are published alongside the APKs; the manifest covers all three files. No AAB or Play submission was produced.

## 0.8.3 — Project Mission Control (published build)

Executed on 2026-11-13 UTC from `/home/ubuntu/nyx`:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL in 1m 29s`.

- JVM tests: **702**, failures **0**, errors **0**, skipped **0** across 67 classes.
- The ARM64 debug APK is `com.nyx.ai`, versionCode **16**, versionName **0.8.3**, minSdk 26, compile/target SDK 37, `arm64-v8a` only.
- The instrumentation APK compiled but was not run. This environment has no `adb` binary or connected Android device; UI rendering/touch behavior and Termux/PRoot execution were not tested on hardware.
- Published APK SHA-256: `bd6e00a4d3dfbd17cfd9dfc15f1b2c83b0f6e3b7b666edc51d8021766e29ea32`.
- Instrumentation APK SHA-256: `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`.
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`, matching the established v0.8.2 signing identity.
- APK 16 KB ZIP alignment check passed (`zipalign -c -P 16 -v 4`). Existing Compose `Divider` deprecation and Room migration parameter-name warnings remain.
- The APK, instrumentation APK, source ZIP, and checksum manifest are published in GitHub release v0.8.3. No AAB or Play submission was produced.

## 0.8.2 — Project Unshackle (published build)

Executed on 2026-11-13 UTC from `/home/ubuntu/nyx`:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL in 1m 32s`.

- JVM tests: **702**, failures **0**, errors **0**, skipped **0** across 67 classes.
- Focused classes: `DependencyPlannerTest` (22), `ObjectiveDependencyScopeTest` (5), and `DependencyBootstrapTest` (9). Regressions cover ordinary shell automation, negated toolchain instructions, affirmative Android development, callback bypass for shell tasks, resumed planned Kotlin work, non-JVM JDK-probe avoidance, and existing installation authorization/verification behavior.
- Debug APK identity: package `com.nyx.ai`, versionCode **15**, versionName **0.8.2**, minSdk 26, compile/target SDK 37, `arm64-v8a` only.
- Debug APK SHA-256: `7ba36f16390d96df4d622f4264b0852a23fd61216859a497a5988af0bb13c0f8`.
- Instrumentation APK SHA-256: `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` (compiled, not executed).
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`, matching the established v0.8.1 identity.
- APK 16 KB ZIP alignment check passed (`zipalign -c -P 16 -v 4`). The build emitted only the existing Compose `Divider` deprecation and Room migration parameter-name warnings.

No physical Android device was attached. Debian/Termux package installation and objective resumption were not exercised on device; the JVM suite and APK build do not establish on-device autonomy. The APK, compiled-but-not-executed instrumentation APK, source archive, and checksum manifest are published in GitHub release v0.8.2. No AAB or Play submission was produced.

## 0.8.1 — Project Clear Signal (published build)

Executed on 2026-11-12 UTC from `/home/ubuntu/nyx` for v0.8.1:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL in 35s` (final incremental rebuild after the current-run selection fix).

- JVM tests: **695**, failures **0**, errors **0**, skipped **0** across 66 classes.
- New focused coverage: `WorkerConsoleModelTest` (5), `PlanningRecoveryTest` (5), and expanded `ModelCommandPolicyTest` cases for redirection, chaining, substitution, and indirect execution.
- Pre-existing warnings remain: `Divider` deprecation in `MainActivity.kt` and Room migration parameter-name warnings. No compile errors.
- Debug APK: `com.nyx.ai`, versionCode **14**, versionName **0.8.1**, minSdk 26, compile/target 37, `arm64-v8a`.
- Debug APK SHA-256: `7a05ac2dee6b7b5c3cda54769b659b88bc06ce4af8ed004c3cd8333801949ea6`.
- Instrumentation APK SHA-256: `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`.
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` (unchanged).
- 16 KB ZIP alignment check passed (`zipalign -c -P 16 -v 4`).

No physical Android device was attached. Console rendering, history selection, operation detail, scroll behavior, clear-history preservation, provider planning timeout/repair, and real Termux/PRoot shell behavior remain device checks. The JVM tests prove the run/operation model, failure classification, repair prompt bounds, stream-safety, and model-command syntax policy; they do not replace an on-device run.

## 0.8.0 — Project Autonomous Bootstrap (published build)

Executed on 2026-11-12 UTC from `/home/ubuntu/nyx` for the published v0.8.0 build:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL in 1m 15s`.

- JVM tests: **679**, failures **0**, errors **0**, skipped **0** across 64 classes.
- New focused coverage: `DependencyBootstrapTest` (7), `ModelCommandPolicyTest` (10), `EnvironmentConsistencyTest` (5), `PackageInstallerTest` (6), `GuestJdkResolverTest` (9), `GuestJdkResolverAptSourcesTest` (5), `WorkspaceLifecycleTest` (6), `WorkerActivityExportTest` (2), plus three new `TerminalAgentToolsSecurityTest` gate cases.
- Pre-existing warnings remain: `Divider` deprecation in `MainActivity.kt` and Room migration parameter-name warnings. No compile errors.
- Debug APK: `com.nyx.ai`, versionCode **13**, versionName **0.8.0**, minSdk 26, compile/target 37, `arm64-v8a`.
- Debug APK SHA-256: `a67790fa2f13c66210e7635ce013af92ac77e61f4d6417877a6e547779b080be`.
- Instrumentation APK SHA-256: `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`.
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` (unchanged).
- 16 KB ZIP alignment check passed (`zipalign -c -P 16 -v 4`).

No physical Android device was attached. Termux `RUN_COMMAND`, Android foreground-service behavior, guest APT installation, real working-directory commands, live console rendering, workspace deletion, and actual model execution remain device checks. The JVM tests prove the new policy, planning, continuation, classification, workspace-lifecycle, and export-redaction logic; they do not replace an on-device run.

## 0.7.9 — previous published build

## Clean build

Executed on 2026-11-12 UTC from `/home/ubuntu/nyx` for the published v0.8.0 build:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL in 1m 17s`.

Only two pre-existing Kotlin warnings remain (`Divider` deprecation in `MainActivity.kt` and Room migration parameter-name warnings). No compile errors.

## JVM tests

- Total: **632**
- Failures: **0**
- Errors: **0**
- Skipped: **0**

New focused coverage:

| Class | Tests |
| --- | ---: |
| `OllamaGuestRuntimeTest` | 9 |
| `OllamaDiscoveryTest` | 6 |
| `OllamaLifecyclePolicyTest` | 30 |
| `OllamaProcessTest` | 26 |

`OllamaGuestRuntimeTest` covers `proot-distro ps` row parsing, malformed rows, new-session selection, capability markers, session start-token parsing, startup-stage classification, safe script construction, and token validation.

## APK identity

- Package: `com.nyx.ai`
- versionCode: **12**
- versionName: **0.7.9**
- minSdk: 26
- compileSdk / targetSdk: 37
- Native ABI: `arm64-v8a`
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`
- The signer digest is identical to the delivered v0.7.7 and v0.7.8 APKs, so this build installs as an update.

Artifact digests from this clean build:

| Artifact | SHA-256 |
| --- | --- |
| `NYX-v0.7.9.apk` | `48b0f1ef929f815dd5fb18380355fade65736c72039538bc131a07f2f881f3a6` |
| `NYX-v0.7.9-androidTest.apk` | `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` |

## Manifest and lifecycle checks

- Merged manifest contains `android.permission.FOREGROUND_SERVICE_DATA_SYNC`.
- `com.nyx.ai.service.OllamaHostService` is declared `android:exported="false"` with `android:foregroundServiceType="dataSync"`.
- The service is started only for a verified `DEBIAN_PROOT` guest; `TERMUX_HOST` keeps the direct lifecycle path.
- `OllamaGuestRuntime` probes `login --detach`, `ps`, and `kill` before choosing detached mode; the fallback keeps one foreground PRoot login alive.
- Stop verifies the guest PID, kills the recorded session, and re-inspects `/proc/<pid>` before reporting STOPPED.

## Not executed

No physical Android device was attached. The following remain device checks, not automated claims:

- actual `proot-distro ps` column output and detached-session lifetime;
- Termux `RUN_COMMAND` behavior while the NYX activity is backgrounded;
- Android 14+ `dataSync` foreground-service acceptance, persistent notification states, and View Activity/Stop Worker/Open Termux actions;
- guest `ollama serve` startup, model enumeration, and Android-to-guest API reachability;
- Chat's automatic loopback runtime startup and model refresh;
- Stop removing both the guest Ollama process and the PRoot session;
- the foreground fallback on a `proot-distro` release without `--detach`;
- adopted-server protection against a user-started Ollama process.

The JVM tests prove the parsers, state policy, and command construction. They do not replace an on-device run.

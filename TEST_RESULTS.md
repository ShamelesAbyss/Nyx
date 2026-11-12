# NYX 0.7.0 Test Results

## Final automated build

Executed on 2026-11-12 UTC from the frozen application source in `/home/ubuntu/nyx`:

```bash
ANDROID_HOME="$HOME/Android/Sdk" JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: **BUILD SUCCESSFUL**. The run executed **450 JVM tests in 43 classes with 0 failures, 0 errors, and 0 skipped tests**, assembled the debug application APK, and compiled the Android instrumentation-test APK. The count includes the new policy, executable-origin, dependency-planning, network-policy, network-tool, supervisor, worker-role, and workspace-provisioning suites. A separate clean JVM run after parser and redirect corrections also reported zero failures.

## Artifact verification

| Property | Verified value |
| :--- | :--- |
| Package | `com.nyx.ai` |
| Version | `versionName 0.7.0`, `versionCode 8` |
| Compile / target SDK | 37 / 37 |
| Minimum SDK | 26 |
| Native ABI | `arm64-v8a` only |
| APK signing | Android debug certificate, SHA-256 `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` |
| Upgrade compatibility | Same certificate as the delivered v0.6.1 APK; in-place update is possible when that build is installed |
| Debug APK | 33 MB, SHA-256 `ebbdb32b5fb772f632939bd7a803e50eae9d64fe48f964fdd120da4822e2aa00` |
| Instrumentation APK | 581 KB, compiled only |

## What the automated tests establish

The policy tests exercise Manual, Smart, and Full decisions, grant scope and expiry, revocation, delegated-scope narrowing, emergency stop, risk escalation, and journal recording. Environment tests establish that an inherited Termux binary is never accepted as a guest-native tool, that guest plans never contain `pkg`, that host plans never contain `apt`, that Android SDK work is blocked inside an ARM64 guest, and that a satisfied toolchain produces no install candidates. Workspace tests cover name sanitization, traversal rejection, collision suffixes, environment-specific bases, path quoting, and writability checks. Network tests cover HTTPS enforcement, private/loopback handling, credential-bearing URLs, redirect refusal, text truncation, binary rejection, download hashing, and deletion of partial downloads. Supervisor and role tests cover bounded retry, repeated-failure pauses, budget exhaustion, deterministic role assignment, and the prohibition on concurrent coder/environment self-duplication.

These tests do **not** prove Termux dispatch, PRoot execution, package installation, Android Keystore behavior, foreground-service lifecycle, notification actions, or restart recovery on a real phone. Those paths require a physical device.

## Not executed

No physical Android device or emulator was attached during this cycle. The following remain device-only checks: installing and launching the APK; granting `com.termux.permission.RUN_COMMAND`; Termux `RUN_COMMAND` callbacks; Debian guest verification; origin labels from a real guest; `apt`/`pkg` package installs; Android SDK blocker behavior; workspace creation through ROOT/PRoot; Emergency Stop through a service restart; objective pause/cancel and process-death recovery; Room 3 migration preservation; Keystore credential round-trip and tamper detection; native GGUF load/stream/cancel/unload; notification permission and actions; fold/unfold layout; and network access from the device.

The instrumentation APK was compiled but not executed. A debug-signed APK is a development artifact, not a production or Play Store release.

## Scope notes

Package installation is implemented as an approved, planner-generated sequence, not as a general package resolver; distribution-specific package splits can still leave a tool unverified, which the installer reports instead of hiding. Role delegation is prompt-level specialization over the existing bounded workers, not separate model processes. The objective network tool exposes text fetch only; the download implementation exists in the network layer but is not offered as a model tool.

# NYX v0.8.3 — Project Mission Control

**Status:** published as the latest stable GitHub release: [NYX v0.8.3 — Project Mission Control](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.3).

## Summary

This release reorganizes the existing Autonomy and Terminal screens into a clearer mission-control workflow. It binds the UI to persisted objective/task state and observed inference, environment, and workspace status; it does not add a new execution engine or grant additional device authority.

## Changes

- Added a Mission Control overview with the selected/active objective title and real status, current execution phase when present, persisted task completion count and progress, and the active task only when a task is actually running or verifying.
- Added status-aware task cards. Expand a task to inspect its persisted worker kind, attempt count, prerequisites, instructions, success criteria, outcome, failure diagnostic, and bounded evidence. Selecting an older objective loads its own stored task graph before details are shown.
- Added readiness rows sourced from actual inference/model-load state, selected Termux host or verified Debian/PRoot guest, workspace validation and terminal-tool setting, and objective state. Unchecked or unverified states remain explicit; no aggregate readiness score or synthetic “ready” value is introduced.
- Reorganized objective creation and Terminal presentation. The optional verified development preflight remains visible, and Termux Host and Debian PRoot are distinct choices. Capability evidence, workspace authorization, and manually entered command/approval feedback are grouped by purpose.
- Improved Live Worker Console readability with larger operation/event text and 40 dp copy/share targets. Run selection, lifecycle actions, history handling, and the bounded redacted export are retained.

## Preserved behavior

The package ID remains `com.nyx.ai`; the release versionCode is 16 and versionName is 0.8.3. The established debug certificate is unchanged. Existing inference, objective-service, terminal, workspace, command-approval, policy, checkpoint, recovery, and explicit review-before-resume code paths were not replaced. No data, models, credentials, workspaces, or vendored source were deleted or migrated.

## Why this helps

The screen groups information that users previously had to infer across objective cards, task details, and Terminal status. Persisted progress, a real active task, checked-versus-unchecked readiness, and each task's recorded outcome are easier to scan before acting. This is a presentation and state-binding update: it adds no new autonomous execution capability, no synthetic readiness score, and no expanded permission.

## Compatibility and distribution

- Android package: `com.nyx.ai`; minimum API 26; compile/target API 37; `arm64-v8a` only.
- Native GGUF remains an on-device CPU option and does not require Termux. Termux and Debian PRoot remain optional environment choices for existing terminal workflows; existing Ollama and OpenAI-compatible providers remain available.
- The Compose interface uses the standard app window on phones and foldables. There is no new hinge-aware or dual-pane behavior, and foldable layout has not been device-validated.
- The APK is debug-signed for development use, not production or Google Play distribution. No AAB is included.

## Release asset checksums

| Asset | SHA-256 |
| :--- | :--- |
| Primary ARM64 APK — `NYX-v0.8.3.apk` | `bd6e00a4d3dfbd17cfd9dfc15f1b2c83b0f6e3b7b666edc51d8021766e29ea32` |
| Instrumentation APK — `NYX-v0.8.3-androidTest.apk` | `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` |

The companion `NYX-v0.8.3-SHA256SUMS.txt` publishes the source-archive digest along with both APK digests. The source archive cannot contain its own final digest without changing that digest; the GitHub release body lists all release-asset hashes after packaging.

## Verification and limits

- Clean command: `./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`.
- Result: `BUILD SUCCESSFUL in 1m 29s`; 702 JVM tests, zero failures, zero errors, zero skips across 67 classes.
- The `arm64-v8a` debug APK and instrumentation APK compiled. The instrumentation suite was not run.
- APK identity, established signer SHA-256, APK SHA-256, and 16 KB ZIP alignment are recorded in `TEST_RESULTS.md`; the published checksum manifest accompanies the APKs and source archive.
- No `adb` binary or Android device was available. Mission Control and Terminal rendering, touch behavior, insets, and on-device Termux/PRoot behavior therefore remain unverified. No device screenshot or emulator preview is claimed.
- This is a debug-signed development build, not a production-signed or Google Play build. No AAB was generated. Physical-device visual interaction and Termux/PRoot execution remain unverified.

See [V083_IMPLEMENTATION_REPORT.md](V083_IMPLEMENTATION_REPORT.md) for implementation details and validation boundaries.

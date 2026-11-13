# NYX v0.8.3 — Project Mission Control

## Implementation report

**Publication status:** published as [NYX v0.8.3 — Project Mission Control](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.3).

## Purpose

Improve the existing NYX autonomy experience so a user can quickly see which objective is selected or running, what persisted work has completed, what is currently active, and which required systems have actually been checked. The update also makes the Termux Host and Debian PRoot choices more visible and improves the Live Worker Console's small-screen readability.

## UI changes

### Autonomy / Mission Control

- Added a Mission Control heading and an observed-state readiness panel for inference, execution environment, workspace/terminal-tool authorization, and objective state. Native-model readiness follows the native runtime's loaded/phase state; provider readiness follows the existing connection state. Environment values come from the current environment profile and verified guest list. Workspace status uses the existing `WorkspaceCheck` and terminal-tool flag. Unchecked, unavailable, and disabled states remain distinct; the screen does not calculate an invented overall readiness score.
- Added an active-mission card showing the objective title, persisted objective status, current service phase when running, and—only when the matching selected task graph is loaded—the true count of tasks marked completed versus persisted task total. The progress bar is based on that count. An active task is shown only for persisted `running` or `verifying` tasks.
- Kept pause and cancel wired to the existing ViewModel/service actions. Failed, interrupted, or paused missions use the existing review/resume route and retain the confirmation explaining that a retry can repeat non-idempotent work. No new automatic retry was introduced.
- Added a recent-mission selector and a task timeline. A selected mission's tasks are filtered by objective ID; when another mission's graph is not loaded, the UI asks the user to load it rather than showing another mission's data. Expanded task details are bounded and show stored prerequisites, worker/attempt information, instructions, verification criteria, outcome, error, and evidence.
- Separated the objective composer from mission status/history. The optional verified development preflight and existing goal/action bindings remain.

### Terminal / environment selection

- Reframed the screen around an explicit **Termux Host** versus **Debian PRoot** target selector, with the selected target visually distinct and a separate environment-detection action.
- Grouped checked capabilities, manual commands, and workspace authorization with clearer headings. Terminal result text flags visible failure/denial wording with a warning color.
- Corrected the Termux status label so “Termux host verified” and the success color appear only when the environment probe reports an isolated available host. A reachable but non-isolated/nested result is labeled as not independently verified.
- All controls continue to call existing ViewModel methods. The update does not change shell commands, permission handling, workspace validation, package installation approval, autonomy policy, or command authority.

### Live Worker Console

- Increased the operation/event hierarchy's text sizes and enlarged copy/share targets from 34 dp to 40 dp.
- Added color mappings for verification, planning, blocked, and review-needed statuses.
- Run/history selection, objective controls, event detail, history-clear confirmation, and bounded redacted export remain unchanged.

## Safety and scope

This is a presentation/state-binding update, not a new autonomy capability. The implementation does not introduce unrestricted shell access, widen grants, change external-provider consent, bypass workspace validation, alter checkpoint/recovery semantics, remove review confirmation, or modify Room entities/schema. It does not delete or migrate user data, credentials, models, workspace contents, or bundled llama.cpp source. The package application ID is unchanged.

## Build and verification

- Version metadata: application ID `com.nyx.ai`, versionCode **16**, versionName **0.8.3**, minSdk 26, compile/target SDK 37, native ABI `arm64-v8a` only.
- Build: `./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`.
- Result: `BUILD SUCCESSFUL in 1m 29s`.
- JVM tests: **702 passed; 0 failures, 0 errors, 0 skipped; 67 suites**.
- `assembleDebugAndroidTest` compiled the instrumentation APK; instrumentation was not executed.
- APK SHA-256: `bd6e00a4d3dfbd17cfd9dfc15f1b2c83b0f6e3b7b666edc51d8021766e29ea32`.
- Instrumentation APK SHA-256: `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`.
- Signing certificate SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`, matching the established v0.8.2 debug identity.
- APK 16 KB ZIP alignment: passed (`zipalign -c -P 16 -v 4`).
- Existing compiler warnings remain: the Compose `Divider` deprecation and Room migration parameter-name warnings.

The exact final artifact checksums are also recorded in `NYX-v0.8.3-SHA256SUMS.txt` alongside the packaged APKs/source archive.

## Validation limits

There is no `adb` executable and no physical Android device in this build environment. Consequently, no Compose preview/screenshot or on-device rendering/touch/inset test was performed. The application UI has been compile-verified but not visually exercised. Termux permission grant, host-versus-guest switching, live execution, Android back behavior, hardware keyboard/IME behavior, and physical-device objective pause/resume also remain unverified. A passing JVM suite and APK build do not establish those runtime behaviors.

This is a debug-signed development release, not a production-signed or Google Play build. No AAB or Play Store submission was produced. The published APK, instrumentation APK, source archive, and checksum manifest are attached to the v0.8.3 GitHub release. The companion manifest lists the APK and source-archive SHA-256 values; the report records the APK digests above.

# NYX v0.9.3 — Project PRISM

Package `com.nyx.ai`, versionCode 27, versionName 0.9.3. Published as the current stable release; historical tags, releases and assets remain intact. ARM64 only, minimum API 26, compile/target API 37.

## What changed

Project PRISM replaces the crowded navigation with an adaptive Eclipse shell. Compact windows show Chat, Models, Autonomy and a complete **Sections** menu; expanded windows use a scrollable nine-entry rail. At large text sizes the primary bar reduces to Chat, Autonomy and Sections so labels remain readable, while every destination stays reachable.

The nine destinations share consistent surfaces, accents, readable type, 48 dp actions, wrapping action rows and safe-area/IME handling. Models groups inference choices and managed Ollama controls; Arsenal and Gateways add local search; Settings groups Connections, Inference, Autonomy and Advanced without changing secret-draft or permission behavior.

Autonomy now opens with deterministic **Mission Results** built from owned objective/task/check/event records, approved Quick Action snapshots and Workbench registrations. The screen shows recorded check counts, project observations, actual task output and technical records; it never asks a model to format or prove a result. Existing guarded Preview, Save as… and Share actions remain behind their original ownership and byte-integrity checks. Historical selection is read-only and cannot control another live mission.

No execution engine, Room schema, native dependency, permission, provider protocol, managed-server recovery rule or signing identity changed.

## Verification

The final clean network-enabled build passed in **2m 15s** with **1,251 JVM tests across 137 suites, zero failures, zero errors and zero skipped**, including the opt-in public HTTPS acceptance tests. The production-screen host suite passed **48 checks** across compact, expanded, short and 170% text configurations; the unchanged console host suite passed **24 checks**. The compiled `PrismScreensInstrumentedTest` suite passed **12/12 tests in 272.6 s** on the emulator at default font scale.

| Artifact | SHA-256 | Size |
|---|---|---|
| `NYX-v0.9.3.apk` | `ec24559247a33efc62314b0f43ccc306ca91eac8d151b5d1f7cddfc19e98bbb7` | 35,599,095 bytes |
| `NYX-v0.9.3-androidTest.apk` | `6ef6d94af58d4a3f09fa9638a453cfe9db5d1292df8c1f7ab48152d88806dfbb` | 1,046,382 bytes |

Both APKs are signed with the established NYX certificate `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` and pass `zipalign -c -P 16 -v 4`. The app APK was byte-identical before and after the final clean build, so the emulator acceptance binary is the release binary.

## Emulator acceptance

The exact APK was installed on an Android API 30 x86_64 Google APIs AVD with `libndk_translation` ARM64 translation. All nine destinations rendered at 360×640/160 dpi; the 1000×800 expanded rail exposed all nine entries; the 170% compact layout reduced the primary bar to Chat, Autonomy and Sections. The public v0.9.2.1 APK was installed first, a `files/upgrade-marker.txt` marker was created, and v0.9.3 was installed over it with `pm install -r`; versionCode 27, the same data directory and the marker survived, and the app launched. Evidence is in [`docs/prism/emulator-acceptance/`](docs/prism/emulator-acceptance/) and the [device acceptance matrix](docs/prism/DEVICE_ACCEPTANCE.md).

This is emulator evidence, not physical-hardware acceptance. The emulator produced occasional system/System UI ANRs under ARM translation and a stale top-edge pixel artifact when resizing from the expanded rail to the compact window. Physical folding/unfolding, real IME, rotation, split-screen, process-death restoration, TalkBack, real Termux/PRoot/Ollama/native inference, SAF/share recipients, permission denial/recovery and real-model timing remain unverified.

## Install and upgrade

The APK is debug-signed with the same established certificate used by earlier public builds. Existing installations signed with that certificate can update in place; back up important data first. No AAB or Google Play package is provided. The source archive contains the exact working tree, tests, docs, licenses and Gradle wrapper; model weights and private files are not included.

See [PRISM_IMPLEMENTATION_REPORT.md](PRISM_IMPLEMENTATION_REPORT.md) for architecture, control preservation, exact commands and remaining limits.

# NYX v0.9.1 — Live Worker Console UI hotfix

Scope: source hotfix from official main `8c736932a6f8832c443ab776f502306a9236f41f`. VersionName 0.9.1, versionCode 24 and com.nyx.ai are unchanged. No release tag, APK publication, inference, planner, terminal, scheduling, worker or data-schema change. README and official v0.9.1 release links/assets remain untouched.

## Root cause and evidence

The original dialog reserved fixed space for the objective title, phase/status, active task, execution buttons and history controls, then OperationDetail reserved more space for Back/title/metadata. Only the remainder was allocated to its weighted LazyColumn. In a short window these headers could consume the entire height. Every event was also forced permanently expanded with entire detail/input/output in one lazy item. Detail list state was implicit and not keyed to the operation. In the other views, count-driven near-bottom effects could override manual reading; OperationList jumped to lastIndex even though operations are newest-first, targeting the oldest operation.

The unchanged baseline dialog was executed in Robolectric API 35 native graphics at **640 × 360 dp** with the same long objective/active-task context and 300-event history. After opening the operation, its event viewport measured **0 px** and the usable-viewport assertion failed. Raw evidence: [baseline XML](docs/console-hotfix/baseline-viewport.xml). This reproduces a constrained-window layout failure, **not the owner's exact Pixel 10 Pro Fold interaction** and not an inference/planning failure. Fixture operation status stays waiting.

## Focused repair

The variable context and operation heading now live inside the same bounded lazy viewport as their view content. Close stays available; detail Back and scrolling controls stay outside the viewport. Dialog safeDrawing insets are explicit with edge-to-edge window handling and padded list edges. Layout height is not inflated and overflow is not hidden.

Operation/raw events start as compact, individually expandable summaries. Full title/detail/input/output are retained in separate lazy rows, capped at 600 UTF-16 code units and 12 newline breaks per text part, without splitting surrogate pairs or discarding whitespace. Existing dark/violet palette, typography, icons and rounded surfaces remain. Metadata wraps and long titles have a full expandable representation.

There is no implicit near-bottom following. **First**, **Jump to latest**, **Follow live** and visible row-range/history status make position explicit. Following tracks appended or updated final-event output only when selected; manual vertical input or expanding/collapsing an event turns it off. The latest target respects newest-first operation ordering. Stable item keys preserve reading anchors on arrival. Parent grouped/raw list states survive Back; selecting a different operation/run creates its own starting state. System Back returns from detail before closing. Existing copy/share export bounds and pause/cancel/resume/clear callbacks are unchanged.

## UI tests and visual QA

**24/24 host Android UI simulations passed, zero failures/errors/skips**: the eight production-dialog Compose test cases at **360 × 640**, **840 × 600**, and **640 × 360 dp**. They cover opening/closing, first/final events in 300-event histories, 2,000-line output expansion/end/collapse, explicit following and manual gestures, incoming events while reading, expansion while following, operation switches/Back, grouped/raw views, a 40-entry history menu with oldest/newest selection, system Back, and copy/share/pause/cancel/close. All three sizes assert a usable detail viewport. Native-Canvas screenshots were inspected for short-window layout and reachable long-output ends. These are Android simulations, **not hardware screenshots or real fold/unfold acceptance**. See [summary](docs/console-hotfix/ui-acceptance.json) and retained per-size XML.

The same eight test cases are checked in as Android Compose instrumentation tests. They must still be run on an ARM64 phone and an expanded/foldable window. No device is attached and no compatible ARM64 emulator is configured. Physical navigation-bar/cutout/IME, touch/fling behavior and fold transitions remain **UNVERIFIED**. API 35 is solely the host simulation runtime; application compile/target 37, min 26, ARM64 and native toolchain are preserved.

Host runner: `ANDROID_HOME=/path/to/sdk bash tools/console_ui_host_tests.sh current /new/output`. It generates temporary copies of the instrumentation tests, injects pinned host-only dependencies via an external Gradle init file, retains XML/screenshots/input hashes, and removes generated source on exit. It does not add Robolectric to normal app dependencies. `--baseline /new/output` checks out only the known baseline UI text into a temporary fixture and intentionally returns failure when the old viewport collapses; the baseline Git object must be available. Initial harness discovery/classpath/PixelCopy limitations were corrected; failed diagnostic logs remain under ignored verification-output. Host screenshots use native Canvas because PixelCopy has no real display surface in this simulation.

The existing Verify workflow also runs these 24 host UI cases after the ordinary JVM/app/instrumentation gates. It preserves the earlier JVM XML/HTML before the filtered UI run and retains both sets plus simulation screenshots/input hashes. No CI installation APKs are uploaded.

## Whole-app build and artifact checks

Clean Gradle build **passed in 2m 32s**: **1,202 JVM tests across 134 suites, zero failures/errors/skips**, including the four new text/ordering/expansion policy tests. App and instrumentation APKs compiled. Independent identity (`com.nyx.ai`, 0.9.1/code 24, API 26–37), ARM64, full notices and 16 KB ZIP/ELF gates passed in the existing explicit build-only signer mode. The first source-archive attempt raised a Python pathlib runtime exception; the unchanged checker then passed creation and independent verification for **4,607 exact current-tree source inputs**. Final documentation/evidence additions are inventoried again after commit. No release protection was relaxed. [Host build summary](docs/console-hotfix/host-build.json) records these results; full build/test logs remain under ignored verification-output.

Android instrumentation compilation and host simulation are separate from physical execution. Original distribution signing material is unavailable locally; no local build is represented as upgrade-compatible and no APK is published. Exact-commit hosted CI must be inspected after the authorized normal push to main; both the normal build and the host UI simulation step must pass. The final delivery report links that exact run.

## Files and review artifacts

Production UI changes are confined to `app/src/main/java/com/nyx/ai/ui/WorkerConsoleUi.kt` and its small `ConsoleTimelinePolicy.kt` helper. `app/build.gradle.kts` adds only Compose instrumentation-test dependencies/host activity, without changing versions or application toolchain. Coverage is in `WorkerConsoleScrollingInstrumentedTest.kt`, `ConsoleTimelinePolicyTest.kt`, the optional `tools/console_ui_host_tests.sh` runner and its baseline-only fixture. `.github/workflows/verify.yml` runs and retains the UI simulation evidence. Development/test notes and `docs/console-hotfix/` hold the report, raw XML, hashes and selected simulation images.

Simulated screenshots: [short-window detail](docs/console-hotfix/ConsoleShortHostRegressionTest_detail.png) · [reachable long-output end](docs/console-hotfix/ConsolePhoneHostRegressionTest_long-output-end.png). These are not physical-device screenshots.

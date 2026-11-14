# NYX v0.9.2 — Project Relay implementation report

**Release:** v0.9.2 / versionCode 25
**Package:** `com.nyx.ai`
**Date:** 2026-11-14
**Status:** published release. [GitHub release v0.9.2](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.9.2).

## Scope

Project Relay continues the published v0.9.1 — Project Ignition baseline with four focused planner changes:

1. Require explicit local evidence in every compact-plan step.
2. Avoid a planning call for bounded conversational/readiness objectives.
3. Give compact and incremental planning more bounded output room.
4. Expose provider-reported Ollama planning timing and token telemetry.

The candidate does not add a new orchestration framework, change application permissions, replace Room data, alter the signing identity, or modify vendored llama.cpp. Public download URLs were updated to v0.9.2 when that release was published.

## Baseline and versioning

The active checkout is `/home/ubuntu/Nyx-publish`, fast-forwarded to public `origin/main` before development. The published v0.9.1 build uses versionCode 24. The first local metadata draft considered code 22, but that is lower than 24 and could not install as an update. The candidate therefore uses **versionCode 25 / versionName 0.9.2**.

This is a monotonic version code, not a claim that an in-place upgrade was tested. Package identity, signing certificate, min SDK 26, compile/target SDK 37, and the ARM64-only configuration are unchanged.

## Implementation

### 1. Compact-plan evidence contract

`CompactPlanningPolicy` now requires a `check` object on every step in both the initial compact schema and continuation schema. The accepted shape is deliberately narrow:

- inference: `{"kind":"artifact_nonempty","path":"..."}`
- filesystem: `file_exists`, `file_nonempty`, or related supported local observations
- terminal: `exit_code`, `output_contains`, and existing supported observations
- development/verification: existing local file/output checks

The schema rejects inference plans that claim file, terminal, process, or tool evidence. Expansion validates that the referenced path or observation belongs to the step's declared worker. Invalid evidence still fails closed before execution. The prompts and bounded repair prompt teach the same contract, so a retry is not asked to guess an undocumented field.

This changes planning reliability, not trust: a declaration is a future verification obligation. It is not evidence that the work already happened.

### 2. Narrow conversational direct routing

`ModelCapabilityPolicy` recognizes short conversational/readiness objectives such as `hi`, `hey`, and `are you ready?`. Such a goal routes to the existing `DIRECT` text-only strategy only when it is short, contains no resource intent, and is not classified complex. Coding objectives and resource-bearing requests remain planned.

Direct routing grants no tools and does not bypass the existing direct-response bounds. The intent is to avoid spending a planning call on a greeting while preserving planning for work that can touch files, terminals, packages, or projects.

### 3. Bounded output-room increase

The compact initial-plan cap increased from 384 to 512 tokens; the single-step cap increased from 192 to 256. Incremental non-guided continuations use 512, guided continuations 256. The output remains bounded and schema-validated. This reduces avoidable truncation of required evidence fields without turning planning into unbounded generation.

### 4. Provider telemetry

`OllamaClient.InferenceTokenStats` now includes `promptEvalDurationNanos`, appended after the original five positional fields to preserve existing positional construction. `InferenceTelemetry` is a pure formatter that reports only measured/provider values:

- first-text latency and total request wall time;
- model load, prompt evaluation, generation, and total provider duration;
- prompt and output token counts;
- prompt and output rates when the required measurements exist;
- reasoning characters separately from plan text.

Missing or invalid values are printed as unavailable. Zero is not silently treated as a measured duration, and characters are never converted into tokens. `ObjectiveExecutionEngine` emits a `planning_telemetry` event with planning completion, carries first-token/elapsed/streamed-character values in its internal response, and routes the event through the existing `planning_*` activity classification. `ModelDiagnostic` shows prompt-evaluation timing and measured prompt rate separately.

Telemetry is observational. It does not claim a speedup, change timeouts, or infer provider behavior from wall-clock timing. Ollama queueing and host load can make request wall time differ from provider durations.

## Files changed

Production:

- `app/build.gradle.kts` — 0.9.2 / code 25, with a comment explaining the monotonic choice.
- `app/src/main/java/com/nyx/ai/autonomy/CompactPlanningPolicy.kt` — required check, schema/prompt changes, 512/256 caps.
- `app/src/main/java/com/nyx/ai/autonomy/IncrementalPlanning.kt` — continuation check contract and 512/256 caps.
- `app/src/main/java/com/nyx/ai/autonomy/PlanningRecovery.kt` — repair prompt teaches the evidence shape.
- `app/src/main/java/com/nyx/ai/autonomy/ObjectiveExecutionEngine.kt` — telemetry event and response fields.
- `app/src/main/java/com/nyx/ai/network/ModelCapability.kt` — narrow conversational direct routing.
- `app/src/main/java/com/nyx/ai/network/OllamaClient.kt` — parses `prompt_eval_duration`.
- `app/src/main/java/com/nyx/ai/network/InferenceTelemetry.kt` — new pure formatter.
- `app/src/main/java/com/nyx/ai/network/ModelDiagnostic.kt` — prompt-evaluation display.

Tests:

- new `InferenceTelemetryTest` — measured timings/rates, unavailable values, invalid durations.
- `ModelCapabilityPolicyTest` — direct conversational/readiness routing, resource/complexity exclusions, coding objective remains planned.
- `CompactPlanningPolicyTest` — required `check`, allowed evidence kinds, inference limited to `artifact_nonempty`, inference file evidence rejected.
- `PlanningRecoveryTest` — repair prompt carries the required inference evidence shape.
- `InferenceOptionsHttpTest` — valid and invalid `prompt_eval_duration` parsing.
- `DiagnosticSummaryTest` — prompt-evaluation timing/rate rendering.
- `PlanningPromptPolicyTest` — 512/256 cap expectations.
- `BedrockPlanningContractTest` — schema-honoring fixture updated with `artifact_nonempty`.

## Verification evidence

### Focused checks

Production Kotlin compilation passed. Focused planner/capability/telemetry/diagnostic tests passed, followed by the Workbench and planner regression groups. The first clean build exposed one stale test fixture that omitted the newly required check; the fixture was corrected to honor the schema, and the suite was rerun. No production behavior was relaxed to make the test pass.

### Clean build

Command:

```bash
./gradlew --no-daemon --max-workers=2 --console=plain \
  clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: **BUILD SUCCESSFUL in 2m 17s**. The build assembled the app and androidTest APKs. Compile warnings remain in historical tests (nullable Java types, delicate APIs, and an old planning timeout); there were no test failures or errors.

### Full JVM suite with real-network acceptance

Command:

```bash
NYX_REAL_NETWORK_TEST=1 ./gradlew --no-daemon --max-workers=2 --console=plain \
  testDebugUnitTest --rerun-tasks
```

Result: **BUILD SUCCESSFUL in 2m 03s**, **1,210 tests across 135 suites, 0 failures, 0 errors, 0 skipped**. The two public HTTPS acceptance tests that are intentionally skipped by default ran in this pass. This is host/JVM evidence, not Android instrumentation.

### APK facts

| Item | Value |
| :--- | :--- |
| App APK | `NYX-v0.9.2.apk` |
| App size | 35,468,023 bytes |
| App SHA-256 | `d525c73bc20c760a4674c159ceca552e9a87862a5d44b17c34e4a43301026fb3` |
| androidTest APK | `NYX-v0.9.2-androidTest.apk` |
| androidTest size | 1,024,814 bytes |
| androidTest SHA-256 | `33a4247615607bdf170ac21dd32a01d82c387e1176163ad2bfe17bd25d8c514a` |
| Package | `com.nyx.ai` |
| versionName / code | `0.9.2` / `25` |
| min / compile / target SDK | 26 / 37 / 37 |
| ABI | `arm64-v8a` |
| Signer SHA-256 | `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` |
| 16 KB alignment | `zipalign -c -P 16 -v 4` passed |

These are the facts of the assembled debug APK. Packaging copies these exact files; the source ZIP and SHA-256 manifest are generated separately after the candidate documentation is final. The delivery manifest, not this table, is authoritative for the packaged set.

## Packaging and integrity gates

The repository provides `tools/check_release_integrity.py` and `tools/build_release.sh`. The current `build_release.sh` names v0.9.1 artifacts, so packaging uses the same integrity checker with v0.9.2 filenames rather than silently reusing v0.9.1 names. The source archive is created by the checker's `--make-source` path, which excludes Git metadata, build outputs, caches, model weights, local configuration, and generated source-manifest duplicates.

Intended delivery set:

- `NYX-v0.9.2.apk`
- `NYX-v0.9.2-androidTest.apk`
- `NYX-Source-v0.9.2.zip`
- `NYX-v0.9.2-SHA256SUMS.txt`
- release notes and this report

Verification requires `sha256sum -c NYX-v0.9.2-SHA256SUMS.txt`, an inspection of the source archive contents, APK identity/signer/alignment checks, and `check_release_integrity.py` in delivery mode. No AAB or Play artifact is produced.

## Known limitations and unverified behavior

- No physical Android instrumentation was executed. The androidTest APK was assembled only.
- No real-device Ollama, Termux, Debian/PRoot, foldable, or keyboard/fold transition was verified.
- No upgrade-over-v0.9.1 installation was performed; versionCode 25 is updateable in principle, not proven on hardware.
- No real-model planning-speed improvement is claimed. The telemetry may show provider timing, but no controlled before/after benchmark was run.
- Deterministic HTTP fixtures are not a real Ollama server. The opt-in public HTTPS tests cover network acceptance, not model quality or local inference.
- The debug certificate is a development identity, not a production/Play signing process. Application-level restrictions are not an OS sandbox.

## Publication state

v0.9.2 was published from the verified exact-commit CI run. The v0.9.1 and v0.9.0 releases, tags, and assets remain intact. Publication used a normal fast-forward commit and annotated `v0.9.2` tag; no history was rewritten and no existing tag or asset was replaced.

## Delivery verification

The local delivery folder contains the app APK, androidTest APK, clean source ZIP, release notes, this report, and `NYX-v0.9.2-SHA256SUMS.txt`. The manifest covers all five delivered content files (the checksum manifest does not hash itself), and `sha256sum -c` passed for every record. The source archive was produced by `check_release_integrity.py --make-source` and independently accepted by `--verify-source`; it contains the current README, release notes, implementation report, license, changelog, user guide, build guide, test results, and development report. It excludes `.git`, Gradle/build outputs, IDE state, caches, local configuration, signing material, APK/AAB files, and model weights. Its generated `SOURCE-MANIFEST.sha256` is the archive's internal inventory, not a duplicate root bundle.

APK checks after packaging used Android Build Tools 36.0.0 directly: `aapt2 dump badging`, `apksigner verify --print-certs`, and `zipalign -c -P 16 -v 4`. The app manifest reports `com.nyx.ai`, versionCode 25, versionName 0.9.2, minSdk 26, target/compile 37, and only `arm64-v8a`; the signer is the established certificate and alignment verification succeeded.

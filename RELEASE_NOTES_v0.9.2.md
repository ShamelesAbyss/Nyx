# NYX v0.9.2 — Project Relay (published release)

**Status:** published release. [GitHub release v0.9.2](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.9.2).

**Package identity:** `com.nyx.ai`, versionName `0.9.2`, versionCode `25`, minimum SDK 26, compile/target SDK 37, `arm64-v8a` only. The established debug signing certificate remains `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`.

Project Relay is a focused planner-reliability and observability release. It does not add a new agent framework, change the package identity, replace user data, or modify the vendored llama.cpp source.

## What changed

### Compact plans must declare real evidence

Every compact-plan step now requires an explicit `check`. The compact schema accepts only supported evidence kinds, and expansion rejects a plan that omits or misuses them.

- Inference-only steps may declare only `artifact_nonempty`. They cannot claim filesystem, process, terminal, or tool evidence they do not have.
- Filesystem, terminal, development, and verification steps must name an actual future local observation, such as `file_exists`, `output_contains`, or `exit_code`.
- A tool step without valid evidence still fail-closes during expansion. No model output is treated as proof that work happened.

This addresses a concrete failure mode: a small model could produce a plausible plan without checks, consume the full planning call, and then fail validation. The requirement is now stated in the compact schema, the initial prompt, incremental continuation, and bounded repair prompts.

### Conversational goals no longer pay for planning

`ModelCapabilityPolicy` now routes a narrow set of short conversational/readiness goals (`hi`, `hey`, `are you ready?`, and similar) to the direct text-only strategy when the objective contains no resource intent and is not complex. Coding and resource-bearing objectives remain planned. Direct conversational routing grants no tools and does not relax the existing text-only boundary.

### More room for bounded plans

The compact initial-plan output cap increased from 384 to 512 tokens, and the single-step cap from 192 to 256. Incremental non-guided continuations now cap at 512 and guided continuations at 256. These are bounded increases intended to reduce truncation, not an unbounded generation path.

### Provider timing and token telemetry

Planning emits a separate `planning_telemetry` event alongside plan completion. `InferenceTelemetry` formats only provider-reported values:

- time to first text and request wall time;
- model load, prompt-evaluation, generation, and total duration when Ollama reports them;
- prompt and output token counts;
- measured prompt and output rates;
- separate reasoning-character count.

Missing, zero, or malformed provider durations are shown as unavailable rather than guessed. Character counts are never converted into token counts. The event is classified through the existing `planning_*` activity path and appears in the Live Worker Console. `ModelDiagnostic` also displays prompt-evaluation timing and measured prompt rate separately.

This is visibility, not a claimed speedup. No real-model latency improvement was measured on physical hardware.

## Verification performed

- Production Kotlin compilation passed.
- Focused planner, capability, telemetry, diagnostic, and contract tests passed.
- A clean host build passed: `clean testDebugUnitTest assembleDebug assembleDebugAndroidTest` in **2m 17s**.
- The final full JVM suite was rerun with `NYX_REAL_NETWORK_TEST=1 --rerun-tasks` and passed in **2m 03s**: **1,210 tests across 135 suites, zero failures, zero errors, zero skipped**. The two otherwise-skipped public HTTPS acceptance tests ran in this pass.
- The app APK is 35,468,023 bytes with SHA-256 `d525c73bc20c760a4674c159ceca552e9a87862a5d44b17c34e4a43301026fb3`.
- The androidTest APK is 1,024,814 bytes with SHA-256 `33a4247615607bdf170ac21dd32a01d82c387e1176163ad2bfe17bd25d8c514a`.
- `aapt2` confirms `com.nyx.ai`, versionName `0.9.2`, versionCode `25`, minSdk 26, compile/target SDK 37.
- The established signing certificate is unchanged and `zipalign -c -P 16 -v 4` passed.

The clean-build log and final network-enabled suite log are local evidence. Packaging adds a clean source ZIP and SHA-256 manifest; the final manifest is authoritative for delivered files.

## Version-code note

Published v0.9.1 uses versionCode 24. An intermediate implementation note proposed code 22; that would be a downgrade and could not update over v0.9.1. The candidate uses **25**, which is monotonic for the next build. This does not establish upgrade compatibility, which still requires a physical in-place installation test.

## Known limitations

- No physical Android instrumentation was run. The androidTest APK was assembled but not executed on a device.
- No real-device Ollama, Termux, Debian, foldable, or keyboard/fold behavior was verified.
- No upgrade-over-v0.9.1 installation was performed.
- No real-model planning-speed improvement is claimed. Telemetry reports provider values when available; queueing and host load can make wall time differ from provider durations.
- Deterministic HTTP fixtures are not a real Ollama server. The opt-in public HTTPS tests cover network acceptance, not local model quality.
- Application-level restrictions are not an OS sandbox. Existing authorization, workspace, checkpoint, and no-replay controls remain in force.

## Distribution

The release consists of the debug APK, androidTest APK, clean source ZIP, release notes, implementation report, and SHA-256 manifest described in [V092_IMPLEMENTATION_REPORT.md](V092_IMPLEMENTATION_REPORT.md). Do not install an ephemeral CI-signed APK over existing data.

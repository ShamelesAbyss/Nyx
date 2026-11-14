# NYX v0.8.4 — Project Endurance Implementation Report

**Status: published as [GitHub release v0.8.4](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.4).**

Baseline: v0.8.3, versionCode 16, versionName 0.8.3. Release: versionCode 17, versionName 0.8.4, package `com.nyx.ai`.

## 1. Scope

This cycle adds hardware-aware operating modes, adaptive native threading, opt-in bounded calibration, exact-prefix KV reuse, resource-aware autonomy concurrency, mode-aware stream coalescing, and an on-device Performance dashboard. It preserves the existing NYX autonomy policy, workspace authorization, dependency gate, checkpointing, recovery, provider snapshots, and no-root/no-arbitrary-shell boundaries.

No model weights, signing material, credentials, user data, or AAB were added or produced.

## 2. Implementation map

| Area | File | Change |
| :--- | :--- | :--- |
| Pure policy | `app/src/main/java/com/nyx/ai/performance/PerformanceModel.kt` | New `OperatingMode`, `PerformanceLevel`, `SocFamily`/`SocClassifier`, `CpuTopology`, `DeviceCapabilities`, `CalibrationResult`, `RuntimeTuning`, `PerformancePolicy`, `TuningGovernor`, `CalibrationPlanner`. No Android imports, so all decisions are JVM-testable. |
| Device reader | `app/src/main/java/com/nyx/ai/performance/DeviceIntelligence.kt` | Reads Build/API-guarded SoC fields, ActivityManager memory, PowerManager thermal, sticky battery state, display/configuration, package features, `/proc/cpuinfo`, and cpufreq sysfs. Missing evidence is reported as unavailable, not guessed. |
| State/persistence | `app/src/main/java/com/nyx/ai/performance/PerformanceController.kt` | Persists mode, adaptive-threading preference, and calibration JSON in `nyx_performance`; applies hysteresis; recomputes tuning against current model/runtime/device fingerprint; exposes `StateFlow<PerformanceSnapshot>`. |
| Calibration | `app/src/main/java/com/nyx/ai/performance/CalibrationRunner.kt` | Opt-in bounded sweep over up to three thread counts; real native load/generate measurement; 120 s budget; thermal recheck; restores original options in `finally`; records model/runtime/device provenance. |
| Dashboard | `app/src/main/java/com/nyx/ai/ui/PerformanceScreen.kt` | New Compose screen with capabilities, live resources, mode selector, effective tuning, adaptive-threading switch, apply action, calibration status/samples, accelerator honesty, and refresh. |
| ViewModel | `app/src/main/java/com/nyx/ai/NyxViewModel.kt` | Initializes/collects performance state; 30 s refresh loop; mode/calibration/apply methods; adaptive native options; resident-session thread preservation; mode-aware stream interval; Eco idle release; manual slider disables adaptive mode. |
| Native session | `app/src/main/cpp/nyx_session.h`, `nyx_session.cpp` | Exact resident-token tracking; longest common prefix; `llama_memory_seq_rm` tail removal; at least one prompt token always decoded; `MemoryReset` clears KV and token cache on any failure; cache cleared on unload. |
| Autonomy | `app/src/main/java/com/nyx/ai/autonomy/ResourcePolicy.kt`, `service/ObjectiveExecutionService.kt` | Optional `parallelismCap`; service passes the selected mode's cap while retaining all existing resource limits. |
| UI/navigation | `app/src/main/java/com/nyx/ai/MainActivity.kt` | Adds Performance destination to the rail/More dialog; exposes existing NYX palette tokens to the new screen; explains adaptive/manual threading in Settings. |
| Version | `app/build.gradle.kts` | `versionCode 17`, `versionName 0.8.4`. |

## 3. Behavior and safety invariants

### Mode selection

`PerformancePolicy.desiredLevel` selects a base level. `safetyCeiling` then applies an observed-state ceiling:

- thermal status ≥ 4 (critical) or battery ≤ 5% while unplugged → Eco;
- thermal status 3 (severe) or battery ≤ 15% while unplugged → Balanced;
- otherwise no ceiling.

`effectiveLevel` is the lower of the desired and ceiling levels. `TuningGovernor` derates immediately and requires two consecutive samples to upgrade, preventing thermal oscillation. Explicit mode changes reset the governor so the user's choice applies at the next sample.

### Native threading

`options()` reuses the resident session's exact thread count when the selected model is loaded. Otherwise it uses the tuning count when adaptive threading is enabled, or the manual slider value. This prevents a chat turn from silently reloading a model with a different thread count. The explicit Apply action unloads and reloads only at an idle boundary and refuses while any generation, agent, workflow, or objective is active.

### Calibration

The runner refuses without a loaded native model, during an objective, at thermal status ≥ 3, or at battery ≤ 20% unplugged. It tests at most three candidates, generates at most eight tokens per candidate, checks the 120-second budget between candidates, and restores the original model/threads in `finally`. Results measured under thermal/battery pressure are retained for transparency but excluded from tuning by `CalibrationResult.degraded`.

Validity is exact-match on model path, model size, runtime version, and device fingerprint. A changed model, app version, or device invalidates the calibration for tuning; the UI says so rather than silently applying stale numbers.

### Native prefix reuse

`Session` records the exact `llama_token` IDs represented in the single-sequence KV memory after a successful generation. The next generation:

1. tokenizes the new formatted prompt exactly as before;
2. compares tokens against the resident cache and finds the longest exact prefix;
3. caps reuse at `n_prompt - 1` so at least one token is decoded for logits;
4. calls `llama_memory_seq_rm(memory, 0, reuse, -1)` to remove the divergent tail;
5. decodes only the suffix;
6. on success, stores the prompt tokens plus the generated tokens that were actually decoded;
7. on any mismatch, failed removal, cancellation, decode error, or exception, clears both KV memory and the token cache and falls back to the full-prompt path on the next attempt.

The cache is cleared by `unload()`. No history truncation, context sliding, template rewriting, or silent output-limit change is introduced.

### Autonomy and streaming

`ResourcePolicy.profile(resources, requestedParallelism, parallelismCap)` takes the minimum of the existing resource policy limit, the objective's requested parallelism, and the mode cap. All memory/thermal/battery/storage checks remain. Chat uses 120/60/33 ms publication intervals for Eco/Balanced/Performance; the final assistant message and persistence behavior are unchanged.

Eco mode releases the native model after ten idle minutes only when no generation, agent, workflow, or objective is active. Balanced and Performance keep the model resident.

## 4. Test coverage

New `app/src/test/java/com/nyx/ai/performance/PerformancePolicyTest.kt` contains 11 tests:

- Tensor G5/G4, Snapdragon 8 Elite Gen 5/Gen 6/Elite, other ARM64, and unknown classification; family classification does not change tuning for identical capabilities.
- Fixed-mode thread, concurrency, stream, and idle-release values.
- Thermal/battery safety ceilings, including the charging exception.
- Adaptive selection by RAM, low-RAM flag, and core count.
- Immediate derate and two-sample recovery hysteresis.
- Calibration validity on model/runtime/device changes and degraded measurements.
- Calibration use within Eco/Balanced/Performance thread caps.
- Candidate thread bounds, deduplication, and single-core safety.
- Calibration gate behavior for unloaded model, running objective, heat, low battery, and charging.
- Best-sample selection, ignoring zero/invalid results and preferring fewer threads on a tie.
- `ResourcePolicy` concurrency cap integration.

Existing `ResourcePolicyTest` continues to exercise the default behavior; the new parameter defaults to no additional cap.

## 5. Build and artifact verification

Command:

```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL in 1m 20s`.

| Item | Result |
| :--- | :--- |
| JVM tests | 713 total, 0 failures, 0 errors, 0 skipped |
| Test classes | 68 |
| New performance suite | 11 tests |
| Package | `com.nyx.ai` |
| Version | 0.8.4 (versionCode 17) |
| SDK | min 26, compile/target 37 |
| ABI | `arm64-v8a` only |
| APK SHA-256 | `65a415a2e57d72575fd858d428e997db7b6377e10a3c8e9b701102285e025dce` |
| Instrumentation APK SHA-256 | `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` |
| Signer SHA-256 | `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` |
| 16 KB alignment | Passed |
| Instrumentation execution | Not run (no device/emulator) |

The native C++ changes compile and link in the ARM64 APK. Runtime KV reuse and calibration remain device-validation items because no GGUF model or Android device was available in this build environment. The release owner reports that the Performance dashboard rendered on a Pixel 10 Pro Fold; that is UI evidence only and does not validate native inference, calibration, KV reuse, or battery behavior.

## 6. Device validation checklist

The release owner reports that the Performance dashboard rendered on a Pixel 10 Pro Fold. This partially covers the visual portion of item 2 and the Performance screen portion of item 10. It does not cover native model loading, calibration, KV reuse, battery life, or the remaining items.

1. Install the v0.8.4 APK over a data-bearing v0.8.3 installation and confirm the package upgrades in place with the same signer.
2. Open More → Performance and confirm device, SoC, CPU, memory, battery, thermal, display, and accelerator rows match the device.
3. Switch Eco/Balanced/Performance/Adaptive at idle and confirm the effective thread/concurrency/stream values change.
4. With a native GGUF model loaded, confirm chat does not reload the model when adaptive threading is enabled; use Apply to native session and verify the reported thread count.
5. Run calibration on a cool, charged device and confirm the sample table, best result, persisted provenance, and invalidation after changing the model.
6. Confirm calibration is refused during an objective, above thermal status 3, and at low unplugged battery.
7. In Eco, leave the model idle for ten minutes with no objective and confirm it is released; confirm Balanced/Performance keep it resident.
8. Run a multi-turn native chat and compare prompt-processing latency with a fresh session; confirm no truncated history and no context corruption.
9. Start an objective in Eco/Balanced/Performance and confirm the worker concurrency never exceeds the mode cap while existing resource pauses still work.
10. Rotate/fold the device and verify the Performance dashboard and existing screens remain usable.

## 7. Publication status

Published as GitHub release v0.8.4 with the debug-signed ARM64 APK, compiled instrumentation APK, clean source archive, and SHA-256 manifest. No AAB or Play submission was produced. The APK remains debug-signed and is not a production/Play artifact. Native inference, calibration, exact-prefix KV reuse, and quantitative battery/performance behavior remain open device-validation items.

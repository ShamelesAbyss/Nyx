# NYX v0.8.4 — Project Endurance 🌑🔋

**NYX is evolving from a local Android AI workstation into a hardware-aware adaptive AI environment.**

Project Endurance is about the device underneath the model. It observes the phone's actual capabilities, chooses a conservative operating profile, applies native threading at safe boundaries, protects the session from thermal and battery pressure, and reuses only verified prompt prefixes. The result is a more deliberate local runtime — not a promise of magic performance.

This is a debug-signed ARM64 development release. Native CPU inference remains the verified GGUF backend. GPU/NPU acceleration is not implemented. Battery or speed improvements have not been quantitatively established.

---

## 🧠 Device intelligence

The new **Performance** dashboard reports what NYX can actually observe from documented Android and Linux interfaces:

- manufacturer, model, SoC manufacturer/model, hardware, Android release, API level, and supported ABIs;
- logical cores, estimated performance/efficiency core split, maximum observed CPU frequency, and optional CPU flags such as `dotprod`, `i8mm`, `bf16`, and SVE;
- total and available RAM, Android memory class, and the low-RAM flag;
- battery percentage and charging state, `PowerManager` thermal status on Android 10+, display metrics, smallest width, and a hinge/large-screen hint;
- media performance class, NNAPI system-feature presence, and Vulkan feature version when reported.

Missing evidence is labeled unavailable rather than guessed. Tensor G5/G4/G3 and Snapdragon 8 Elite family labels are **display and thread heuristics** only. They are not proof that a vendor GPU, NPU, or runtime can accelerate GGUF inference.

The app remains phone-first and uses the same adaptive Compose window on standard phones and foldables. A hinge-aware two-pane layout is not claimed.

---

## ⚡ Adaptive performance

### Four operating modes

| Mode | Best for | What it changes |
| :--- | :--- | :--- |
| 🔋 **Eco** | Long battery life, small devices, background work | At most two native threads, one autonomous worker, 120 ms stream coalescing, and release of the resident native model after ten idle minutes. |
| ⚖️ **Balanced** | Everyday chat and objectives | Moderate native threading, up to two autonomous workers, and 60 ms stream coalescing. |
| ⚡ **Performance** | Fast local generation on capable hardware | More native threads, up to four autonomous workers when the device can sustain them, and 33 ms stream coalescing. |
| 🧠 **Adaptive** | A sensible default across devices | Chooses a level from RAM, low-RAM state, core count, thermal status, and battery state, with hysteresis to avoid oscillation. |

### Native CPU-thread selection

The selected mode's thread count is used before the next native model load. A resident session keeps its exact existing thread count, so a chat turn cannot silently reload the model or change the session underneath a generation.

- The manual **CPU threads** slider in Settings is authoritative and switches adaptive threading off.
- **Apply to native session** reloads the resident model only when no generation, agent, workflow, or objective is running.
- Context length is not silently changed or truncated.

### Opt-in hardware calibration

Calibration measures real native inference against the currently loaded model:

- at most three thread configurations, derived from the current setting and observed performance-core count;
- at most eight generated tokens per configuration;
- a 120-second total budget, with a thermal recheck between candidates;
- refusal without a loaded native model, during an objective, on a hot device, or on a low unplugged battery;
- restoration of the original configuration in a non-cancellable `finally` path;
- on-device provenance: model path and size, runtime version, device fingerprint, load time, generated tokens, end-to-end tokens/s, thermal/battery conditions, and timestamp.

Results are invalidated when the model, runtime version, or device fingerprint changes. Measurements taken under thermal or battery pressure are retained for transparency but excluded from tuning. "Tokens/s" is end-to-end throughput for a fixed short prompt, including prompt processing; it is not a decode-only synthetic benchmark.

### Resource-aware autonomous execution

The existing `ResourcePolicy` now accepts a mode cap. Eco, Balanced, Performance, and Adaptive constrain autonomous worker concurrency without bypassing the existing memory, thermal, battery, and storage checks. Chat publication uses the mode's coalescing interval; generation and final message persistence are unchanged.

---

## 🔋 Battery and thermal management

- Thermal status ≥ 4 or battery ≤ 5% while unplugged → Eco ceiling.
- Thermal status 3 or battery ≤ 15% while unplugged → Balanced ceiling.
- Safety ceilings apply to every mode, including an explicit Performance request.
- Derating is immediate; upgrading waits for two consecutive observations.
- Eco releases the native model after ten idle minutes only when no generation, agent, workflow, or objective is active.
- The next message reloads the model through the normal path.

Thermal status is observed at refresh and load boundaries. It is not an active mid-generation throttle, and it does not abort an in-flight token stream.

---

## 🚀 Native inference improvements

The native session now records the exact token IDs represented in its single-sequence KV memory. On the next generation it finds the longest exact prefix shared with the new formatted prompt and removes only the divergent tail with `llama_memory_seq_rm`. At least one prompt token is always decoded so logits exist for the first sample.

The optimization is deliberately conservative:

- any mismatch, failed sequence removal, cancellation, decode error, or failed generation clears both the KV memory and the token cache;
- no prompt history is truncated, slid, or silently rewritten;
- prompt tokenization and template formatting are unchanged;
- the cache is cleared on unload and model reload;
- if reuse is not possible, the existing full-prompt path runs exactly as before.

This should reduce repeated processing of an unchanged system prompt and earlier conversation turns. **No measured speedup is claimed**, because the native path has not been exercised on physical hardware with a GGUF model.

Streaming publication is coalesced more efficiently by mode, but generation semantics and the final assistant message are unchanged.

---

## 🛠 Reliability and verification

- Clean build: `./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest` — `BUILD SUCCESSFUL in 1m 20s`.
- JVM tests: **713**, failures **0**, errors **0**, skipped **0**, across **68 classes**.
- New `PerformancePolicyTest`: **11 tests** covering SoC classification, fixed/adaptive modes, thermal/battery ceilings, hysteresis, calibration validity/invalidation, candidate bounds, calibration gating, best-sample selection, and the autonomy concurrency cap.
- ARM64 debug APK: package `com.nyx.ai`, versionCode **17**, versionName **0.8.4**, minSdk 26, compile/target SDK 37, `arm64-v8a` only.
- APK SHA-256: `65a415a2e57d72575fd858d428e997db7b6377e10a3c8e9b701102285e025dce`.
- Instrumentation APK SHA-256: `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`.
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`, unchanged from the established NYX signing identity.
- 16 KB ZIP alignment: passed (`zipalign -c -P 16 -v 4`).
- Instrumentation APK compiled but was not executed. No Android device or emulator was attached to the build environment.

**Owner-reported UI evidence:** the Performance dashboard was opened on a Pixel 10 Pro Fold. This is UI evidence only. It does not validate native model loading, calibration, exact-prefix KV reuse, or battery life.

---

## 📱 Compatibility and installation

- Package: `com.nyx.ai` (unchanged).
- Version: `0.8.4` (versionCode 17).
- Minimum Android: 8.0 / API 26.
- Compile/target SDK: 37.
- ABI: `arm64-v8a`.
- Signing: established debug certificate, allowing in-place upgrade of prior NYX builds that used the same signer.
- No model weights, credentials, signing keys, or user workspaces are included in the source archive or APK.

Install the APK on a 64-bit ARM device, then import a compatible GGUF through **Models → Import GGUF** or connect a local Ollama/OpenAI-compatible server. Terminal tools remain off by default and require the documented Termux `RUN_COMMAND` setup.

---

## ⚠️ Known limitations

- Performance dashboard rendering, touch targets, and mode switching are owner-reported on a Pixel 10 Pro Fold but not independently reproduced in this build environment.
- Calibration has not been run on a phone with a real GGUF model. Its UI flow, reload timing, and stored provenance remain device-validation items.
- Exact-prefix KV reuse has no measured device speedup and no device runtime test yet.
- Thermal behavior during long generation is observed, not actively throttled mid-generation.
- The CPU feature parser depends on readable `/proc/cpuinfo` and cpufreq sysfs. Missing or restricted values are reported as unavailable rather than guessed.
- Foldable detection is a hinge-feature/large-width hint; the existing Compose layout is not hinge-aware.
- No GPU or NPU GGUF backend is bundled or verified. NNAPI/Vulkan feature presence does not mean arbitrary GGUF models are accelerated.
- The APK is debug-signed, not a production or Play Store artifact.
- No AAB, Play submission, signing-key upload, force push, history rewrite, or release deletion was performed.

---

## 📦 Release artifacts

| Asset | SHA-256 |
| :--- | :--- |
| `NYX-v0.8.4.apk` | `65a415a2e57d72575fd858d428e997db7b6377e10a3c8e9b701102285e025dce` |
| `NYX-v0.8.4-androidTest.apk` | `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` |

The published checksum manifest covers the primary APK, instrumentation APK, and source archive. Verify all downloaded assets with `sha256sum -c NYX-v0.8.4-SHA256SUMS.txt`.

---

**NYX Eclipse AI** · Your Intelligence. Your Device. Your Rules.

Apache-2.0 · ARM64 · API 26+

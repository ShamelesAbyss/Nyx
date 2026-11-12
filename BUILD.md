# Building NYX 0.6.0

## Requirements

- JDK 17 or newer and the included **Gradle 9.3.1** wrapper. The local environment uses JDK 21; Java/Kotlin bytecode targets 17.
- **Android Gradle Plugin 9.1.1**, AGP built-in Kotlin **2.2.10**, Compose compiler plugin **2.2.10**, **KSP 2.3.6**, and **Room 2.8.5** (runtime, KTX, and compiler).
- Android SDK **Platform 37.0** (`platforms;android-37.0`) and the app's selected **Build Tools 36.0.0**.
- **NDK 28.2.13676358**, **CMake 3.22.1**, and Python 3 for the pre-build SDK-policy guard.
- Network access on first setup for SDK/Gradle/Maven dependencies. llama.cpp is vendored, and the configured native build needs no source download. Model weights are neither build dependencies nor APK assets.

The earlier KSP 2.2-era/Room 2.6.1 combination produced compilation errors in integration testing and was replaced with the pins above. Do not restore it based on an older compatibility-floor example. The standalone Android Kotlin plugin and obsolete `android.kotlinOptions` are removed; no Kotlin/new-DSL suppression flags remain. Compose **compiler** 2.2.10 is not the Compose runtime/BOM version.

For SDK catalog/package-name details and device policy, see [Android compatibility](docs/ANDROID_COMPATIBILITY.md#installed-sdk-package-evidence). The current catalog uses the suffixed `platforms;android-37.0`, not `platforms;android-37`; package-name failure is not evidence that API 37 is unavailable. SDK setup, when explicitly needed:

```bash
SDK_ROOT="$HOME/Android/Sdk"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --list
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  'platforms;android-37.0' 'build-tools;36.0.0' \
  'ndk;28.2.13676358' 'cmake;3.22.1'
export ANDROID_HOME="$SDK_ROOT"
```

The wrapper pins the Gradle distribution SHA-256. Native source is pinned to llama.cpp commit `09338b85e8b230742fa56f93a1d9f0bf006ca2d3`; source archive SHA-256 and retained upstream licenses are recorded in [UPSTREAM.md](app/src/main/cpp/UPSTREAM.md). These inputs support reproduction; they are not a claim that different hosts/signing keys yield byte-identical APKs.

## Build and install the debug APK

From the project root, run one coordinated build rather than simultaneous Gradle invocations:

```bash
python3 tools/check_sdk_policy.py
python3 tools/check_sdk_policy.py --self-test
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest
# Only when you explicitly choose to install on a connected ARM64 device:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Package identity: `com.nyx.ai`; versionCode 6/versionName 0.6.0; min API 26; compile/target API 37. The APK contains only the **`arm64-v8a`** native ABI, not x86/x86_64 Android libraries. A separate host x86_64 JNI test does not add an Android ABI. Native target `nyx_llama` uses C++17 and `c++_shared`, with baseline ARM64 NEON rather than host-specific optional ISA flags; CPU execution only, without GPU/NPU backends. The Android Debug native build uses **`-O2 -g`**, confirmed from its actual CMakeCache, so debug symbols do not imply an unoptimized native inference build.

Debug builds use the local Android debug signing identity, not a Play/store release key. Android only permits an in-place update when signing identities match. If the key differs, uninstalling first may remove app-private chat, memory, settings, and imported GGUF copies; preserve the prior key and back up user data first. `assembleRelease` alone is not a production-signed release. Keep all signing secrets outside source archives.

## Automated checks and current verification boundary

- The coordinating integrated Gradle run reported **BUILD SUCCESSFUL**, with **212 JVM tests, zero failures, zero errors, and zero skips**, plus assembled debug app and instrumentation-test APKs. This is the observed integrated run, not an assertion that final clean source-rebuild/archive verification has finished. No build/test was run by this documentation-only update.
- `testDebugUnitTest` runs JVM tests for the engine, providers/actions, terminal confinement, development repair, native metadata parsing, context/skills, and retained behavior. The old 98-test figure belongs to 0.5.0, not this release; do not add separately run focused suites to the 212-test integrated total.
- `assembleDebugAndroidTest` compiled instrumentation tests, including Room migration/legacy preservation, Keystore, native repository, and opt-in real-model tests. Compilation is not execution.
- To execute instrumentation on a connected supported ARM64 Android runtime, explicitly run `./gradlew --no-daemon connectedDebugAndroidTest`. The real-model test assumption-skips without an app-readable model path; a skip is not an inference pass.
- A genuine **host x86_64 JNI** run loaded the Apache-2.0 SmolLM2-135M-Instruct-Q4_K_M and produced `The capital of France is Paris.` in **7 selected output tokens**. Cancellation, session reuse, callback exception propagation, and double unload were checked. [Native reproduction](docs/NATIVE_INFERENCE.md#reproducible-verification) records the model revision, hash, commands, and focused tests.
- Standalone **ARM64 cross-compilation passed** with API 26, NDK 28.2.13676358, and CMake 3.22.1. This verifies compilation/linkage, not Android execution.
- No Android-runtime or physical-device inference, Termux/PRoot, permission, migration, foreground lifecycle, Fold/GrapheneOS, thermal, or memory-pressure execution is claimed here. Native true token/load/generation measurements are distinct from global chunk/character metrics and are not an Android-throughput guarantee. See [TEST_RESULTS.md](TEST_RESULTS.md) for exact coordinating results and final source/archive checks when recorded.

## Explicit native device procedure

Use a trusted, licensed instruct/chat GGUF with a supported architecture and default template. Import it through **Models → Import GGUF**, record the exact model hash and device/OS/page size, and verify select/load, real streaming, stop/cancel, session reuse, unload, and private-copy deletion. Exercise a refused oversized load and foreground/background interruption without disabling platform memory limits. See the [native test procedure](docs/NATIVE_INFERENCE.md#reproducible-verification) and [Android memory guidance](docs/ANDROID_COMPATIBILITY.md#memory-admission-and-post-exit-diagnostics).

To run only the opt-in real-model instrumentation test, replace the example path with the actual app-readable imported path:

```bash
adb shell am instrument -w \
  -e class com.nyx.ai.nativeinference.NativeRealModelTest \
  -e modelPath /data/user/0/com.nyx.ai/files/models/your-imported-model.gguf \
  com.nyx.ai.test/androidx.test.runner.AndroidJUnitRunner
```

A missing model argument is an explicit assumption skip; an invalid supplied path is a failure. Do not count either as successful native inference.

## Native packaging and 16 KB checks

The configured NDK/linker produces 16 KB ELF alignment, and AGP uses uncompressed native packaging (`useLegacyPackaging = false`). Check the **produced APK**, including every `.so` and `libc++_shared.so`, rather than inferring success from configuration:

```bash
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -v -c -P 16 4 \
  app/build/outputs/apk/debug/app-debug.apk
```

Extract its libraries and inspect each with the NDK `llvm-objdump -p`; `LOAD` alignment must be at least `2**14`. See [16 KB packaging/device guidance](docs/ANDROID_COMPATIBILITY.md#16-kb-native-packaging-and-validation). APK/ELF alignment is not a real 16 KB device-runtime pass.

## Local delivery integrity and source archive

Pre-build metadata consistency check (does not assert artifact existence):

```bash
python3 tools/check_release_integrity.py --no-apk \
  --delivery-file NYX-v0.6.0.apk --delivery-file NYX-Source-v0.6.0.zip \
  --release-doc README.md --release-doc BUILD.md --release-doc CHANGELOG.md
```

After local packaging, replace `--no-apk` with `--apk /path/to/NYX-v0.6.0.apk`. The checker parses the APK's binary manifest and compares package/version metadata, delivery filenames, and current release-document headings. Add `TEST_RESULTS.md` or `NYX_DEVELOPMENT_REPORT.md` only after their current headings/results have been updated; older reports are historical evidence, not current-release passes.

The source ZIP must include the wrapper, build files, application/tests, documentation, **full vendored llama.cpp source**, `LICENSE`, and upstream/third-party licenses. Exclude model weights (`*.gguf` and test downloads), all generated `build`, `.cxx`, `.kotlin`, `.gradle` and cache directories, local SDK configuration (`local.properties`), IDE/temp outputs, private checkpoints, secrets/credentials, and signing keys. Inspect the complete archive inventory before delivery; preserve vendored licenses even where generated files are excluded.

This scope authorizes local artifacts only. No v0.6.0 GitHub publication, self-update, automatic installation, commit/push, or store submission is implied by a filename, build command, or passing test. The current PriorityNine directive ends mid-sentence and grants no additional self-update, installation, or publication authority. The published download remains v0.5.0. The `specialUse` foreground declaration needs Play review and is not an approval guarantee.

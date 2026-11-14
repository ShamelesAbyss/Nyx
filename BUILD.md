# Building NYX 0.8.5 — Project Sovereign

Package identity: `com.nyx.ai`, versionCode **18**, versionName **0.8.5**. The published release passed `./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`: **731 JVM tests** pass across 70 classes, the ARM64 debug APK builds, and the instrumentation-test APK compiles. The APK is debug-signed, not a production Play build. No Android device was attached to the build environment. Native inference, the Sovereign execution loop, calibration, KV reuse, and Termux/PRoot runtime remain device-validation items.
## v0.8.5 release artifacts

The official [NYX v0.8.5 — Project Sovereign release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.5) includes `NYX-v0.8.5.apk`, `NYX-v0.8.5-androidTest.apk`, `NYX-Source-v0.8.5.zip`, and `NYX-v0.8.5-SHA256SUMS.txt`. Verify them with `sha256sum -c NYX-v0.8.5-SHA256SUMS.txt`. The primary APK SHA-256 is `024c5fa4ab91a7d28b19f8fe22c362766109470e72a615a82f81501a1d8aa456`; the instrumentation APK SHA-256 is `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`; the source archive SHA-256 is recorded in the sidecar manifest. The established debug signer SHA-256 remains `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. Instrumentation was compiled but not executed. No AAB or Google Play submission was produced.

## v0.8.4 release artifacts (previous release)
The official [NYX v0.8.4 — Project Endurance release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.4) includes `NYX-v0.8.4.apk`, `NYX-v0.8.4-androidTest.apk`, `NYX-Source-v0.8.4.zip`, and `NYX-v0.8.4-SHA256SUMS.txt`. Verify them with `sha256sum -c NYX-v0.8.4-SHA256SUMS.txt`. The primary APK SHA-256 is `65a415a2e57d72575fd858d428e997db7b6377e10a3c8e9b701102285e025dce`; the instrumentation APK SHA-256 is `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`. The established debug signer SHA-256 remains `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. Instrumentation was compiled but not executed. No AAB or Google Play submission was produced.
## v0.8.3 release artifacts (previous release)

The official [NYX v0.8.3 — Project Mission Control release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.3) includes the ARM64 primary installation APK, compiled instrumentation APK, clean source archive, and SHA-256 manifest. Download the assets from that page and verify the APKs and source archive with `sha256sum -c NYX-v0.8.3-SHA256SUMS.txt`. The APK is debug-signed, not production/Play-signed; instrumentation was compiled but not executed, and physical-device behavior remains unverified. No AAB or Google Play submission was produced.

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

Package identity: `com.nyx.ai`; versionCode 18/versionName 0.8.5; min API 26; compile/target API 37. The APK contains only the **`arm64-v8a`** native ABI, not x86/x86_64 Android libraries. A separate host x86_64 JNI test does not add an Android ABI. Native target `nyx_llama` uses C++17 and `c++_shared`, with baseline ARM64 NEON rather than host-specific optional ISA flags; CPU execution only, without GPU/NPU backends. The Android Debug native build uses **`-O2 -g`**, confirmed from its actual CMakeCache, so debug symbols do not imply an unoptimized native inference build.

Debug builds use the local Android debug signing identity, not a Play/store release key. Android only permits an in-place update when signing identities match. If the key differs, uninstalling first may remove app-private chat, memory, settings, and imported GGUF copies; preserve the prior key and back up user data first. `assembleRelease` alone is not a production-signed release. Keep all signing secrets outside source archives.

## Automated checks and current verification boundary

- The v0.8.5 clean Gradle run completed with `BUILD SUCCESSFUL in 1m 21s`: **731 JVM tests, zero failures, zero errors, and zero skips** across 70 classes. `assembleDebugAndroidTest` compiled the instrumentation APK; no instrumentation test was executed.
- Project Sovereign's adaptive budgets, working state, tool scoping, false-success guard, and bounded repairs preserve the existing autonomy policy, workspace grants, command authority, checkpoints, and resume confirmations. No policy boundary was removed.
- The published debug APK is `com.nyx.ai`, versionCode 18/versionName 0.8.5, min API 26, compile/target API 37, `arm64-v8a` only. Its SHA-256 and the established debug signing certificate are in [TEST_RESULTS.md](TEST_RESULTS.md). APK 16 KB ZIP alignment passed on the produced artifact.
- No Android device was attached to the build environment and `adb` is unavailable. The release owner reports that the Performance dashboard rendered on a Pixel 10 Pro Fold; native inference, calibration, KV reuse, Mission Control rendering, Termux/PRoot runtime, package installation, and resumed objective behavior remain device checks.
- The earlier host x86_64 JNI/model run and standalone ARM64 cross-compilation recorded in [TEST_RESULTS.md](TEST_RESULTS.md) are historical host evidence, not an Android runtime pass or proof that this release was tested on a phone.
- Existing compiler warnings are the Compose `Divider` deprecation and Room migration parameter-name notices; no compile errors occurred.

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

## Historical v0.8.2 release artifacts and source archive

The reproducible packaging script in `/home/ubuntu/package_nyx_v082.sh` copies the built ARM64 debug APK and compiled instrumentation APK, creates a clean source ZIP, and writes SHA-256 checksums. Verify with:

```bash
cd /home/ubuntu
bash package_nyx_v082.sh
sha256sum -c NYX-v0.8.2-SHA256SUMS.txt
```

The source archive must retain the Gradle wrapper, build files, app sources/tests, documentation, `.gitattributes`, `LICENSE`, full vendored llama.cpp source, and upstream/third-party licenses. It excludes model weights, generated build/native/cache directories, local SDK configuration, IDE/temp files, secrets/credentials, and signing keys. `.gitattributes` classifies `app/src/main/cpp/llama.cpp/` as vendored without changing or stripping those sources.

The APK, instrumentation APK, source archive, and checksum manifest are attached to the public v0.8.2 GitHub release. No AAB or Google Play submission was produced. The instrumentation APK was assembled but not executed; physical-device behavior remains unverified.

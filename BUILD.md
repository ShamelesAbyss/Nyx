# Building NYX 0.5.0

## Requirements

- Android Studio with Android Gradle Plugin 8.7.3 support, or JDK 17+ and the included Gradle 8.9 wrapper.
- Android SDK Platform 35 and Build Tools 35.0.0.
- Network access on first build for Gradle/Maven artifacts.
- No NDK required; native GGUF/llama.cpp inference is not included.

## Build and install the debug APK

From the project root:

```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Package identity: `com.nyx.ai`; versionCode 5/versionName 0.5.0; min API 26; target API 35. Debug builds use the local Android debug signing identity, not a Play/store release key. Android only permits an in-place update when signing identities match. If the key differs, uninstalling first may remove app-private chat, memory, and settings; preserve the prior key and back up user data first.

## Automated checks

- `testDebugUnitTest`: 98 JVM tests covering plan JSON/DAG validation, concurrent scheduling and resource policy, command-risk policy and shell construction, evidence verification and recovery decisions, context storage/ranking, provider capabilities, credential transport, and authenticated request routing.
- `assembleDebugAndroidTest`: compiles Android instrumentation tests, including the v1→v3 Room migration/legacy-record preservation fixture and Keystore tests. Compilation is not execution; run `./gradlew --no-daemon connectedDebugAndroidTest` with a connected Android runtime to execute.
- No physical Pixel/GrapheneOS/Termux/Ollama device is available in the build environment. See `TEST_RESULTS.md` for exact counts and unverified behavior.

## Release integrity

Pre-build consistency check:

```bash
python3 tools/check_release_integrity.py --no-apk \
  --delivery-file NYX-v0.4.0.apk --delivery-file NYX-Source-v0.4.0.zip \
  --release-doc CHANGELOG.md --release-doc NYX_DEVELOPMENT_REPORT.md --release-doc TEST_RESULTS.md
```

After packaging, add `--apk /path/to/NYX-v0.4.0.apk` instead of `--no-apk`; the checker parses the APK's binary manifest and compares package/version metadata against Gradle. It also checks release filenames and current release-document headings.

`assembleRelease` is not a production-signed release. Configure the owner's signing key locally and privately before store distribution. Never place signing secrets in source archives.

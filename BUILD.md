# Building NYX 0.3.0

## Requirements

- Android Studio with Android Gradle Plugin 8.7.3 support, or JDK 17+ and the included Gradle 8.9 wrapper.
- Android SDK Platform 35 and Build Tools 35.0.0.
- Internet access on the first build to resolve Gradle/Maven artifacts.
- No NDK is required by this version because direct llama.cpp/Rust inference is not included.

## Build and install the debug APK

From the project root:

```bash
./gradlew --no-daemon clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK uses application id `com.nyx.ai`, versionCode 3/versionName 0.3.0, minimum Android 8/API 26, target API 35. The standard Android debug signing key is used; this is not a store-release signing configuration.

For an in-place update, Android requires the new APK's signing certificate to match the installed build's certificate. The delivered v0.3 APK was checked against the preceding delivered NYX v0.2 debug APK. APK/source archives do not include a signing keystore. If you install a build signed with another debug key, Android may reject it; uninstalling first can erase NYX's app-private database, agent memory, and preferences. Back up anything important before changing signing identity. Never share or commit your private signing key.

## Automated checks

Run the JVM tests and build both APK variants:

```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

JVM coverage includes calculator validation, credential transport policy, Bearer header routing, bounded/redacted HTTP error diagnostics, and recognition of Ollama's unsupported-tools HTTP 400 response.

Run Android Keystore instrumentation tests on a connected Android device/emulator with:

```bash
./gradlew --no-daemon connectedDebugAndroidTest
```

The Keystore round-trip, deletion, and ciphertext-tamper tests compile as part of `assembleDebugAndroidTest`, but must run against an Android runtime. Live Ollama/API interoperability, fold/unfold behavior, memory/thermal advisories, and long-duration battery/performance require physical-device tests.

## Release variant

`assembleRelease` currently produces an unsigned/unconfigured release variant because production signing has not been supplied. Configure signing locally and privately before distributing a release; do not put signing credentials in the source ZIP or repository.

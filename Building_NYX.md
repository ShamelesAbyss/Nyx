# Building NYX

## Requirements

- Android Studio with Android Gradle Plugin 8.7.3 support, or JDK 17+ and the included Gradle 8.9 wrapper.
- Android SDK Platform 35 and Build Tools 35.0.0.
- Internet access on the first build to resolve Gradle/Maven artifacts.
- No NDK is required for the current build. Native llama.cpp/Rust components are not yet present.

## Build a debug APK

From the project root:

```bash
./gradlew --no-daemon clean assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Install with Android Debug Bridge:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is not signed for store distribution. Android Studio can also sync and build the project using the Gradle wrapper.

## Build a release APK

```bash
./gradlew --no-daemon assembleRelease
```

The current release variant is not minified and does not have a production signing configuration. Configure a private keystore locally before distributing a signed release. Do not commit signing credentials.

## Tests

Run JVM tests with:

```bash
./gradlew testDebugUnitTest
```

Run Android instrumented tests only with a connected compatible device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

An Android emulator/device is not part of the source project; successful compilation alone does not validate device inference or performance.

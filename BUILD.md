# Building NYX

## Requirements

- Android Studio with Android Gradle Plugin 8.7.3 support, or JDK 17+ and the included Gradle 8.9 wrapper.
- Android SDK Platform 35 and Build Tools 35.0.0.
- Internet access on the first build to resolve Gradle/Maven artifacts.
- No NDK is required. Native llama.cpp/Rust inference components are not part of this build.

## Build the debug APK

From the project root:

```bash
./gradlew --no-daemon clean assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (application id `com.nyx.ai`, version 0.2.0, minimum Android 8/API 26, target API 35). Install on a connected device with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with a development key and is not for store distribution. Configure a private signing keystore before publishing a release; never commit its credentials.

## Tests

Run JVM unit tests, including request-header/route and credential transport policy tests:

```bash
./gradlew --no-daemon testDebugUnitTest
```

Compile instrumented app/tests:

```bash
./gradlew --no-daemon assembleDebugAndroidTest
```

Run Keystore-backed storage instrumentation tests on a connected Android device/emulator:

```bash
./gradlew --no-daemon connectedDebugAndroidTest
```

The Keystore storage tests require Android's actual Keystore implementation; they are not JVM tests. No emulator or target device is included in this project. A successful build does not verify live network interoperability, fold posture/hinges, or device thermal behavior.

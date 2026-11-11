# NYX 0.3.0 Test Results

## Automated checks run

The project was built with the included Gradle wrapper, Android SDK 35, and JDK 21. A clean full check succeeded; after the final agent-warning UI adjustment, unit tests, APK assembly, and instrumentation APK compilation were rerun successfully:

```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

- **JVM unit tests:** 9 run, 0 failures, 0 errors, 0 skipped: 2 calculator safety tests, 5 MockWebServer request/authentication/error tests, and 2 credential transport-policy tests.
- **Android debug APK:** assembled successfully from application id `com.nyx.ai`, versionCode 3/versionName 0.3.0, minimum API 26/target API 35.
- **Android instrumentation APK:** `assembleDebugAndroidTest` succeeded; Keystore instrumentation tests compile.
- **Room database:** schema remains version 1; no destructive migration or schema change was introduced.
- **APK signature:** `apksigner verify` succeeded. Its debug certificate SHA-256 matches the preceding delivered v0.2 APK: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. This establishes in-place upgrade compatibility with that exact preceding delivered APK, not with builds signed by a different identity.

## Not executed / needs a device or live endpoint

Keystore round-trip, deletion, and ciphertext-tamper instrumentation tests were compiled but not run because no Android device/emulator was attached. No live Ollama/OpenAI-compatible server was available here for end-to-end discovery, streaming, tool execution, unsupported-tool text fallback, cancellation under load, or remote consent/TLS tests.

The owner previously reported successful Ollama chat and good cover/inner-display appearance on a Pixel 10 Pro Fold using the prior build; v0.3.0 was not independently run on that device. Physical-device verification is still needed for fold/unfold posture and hinge behavior, accessibility, actual Keystore behavior, memory pressure, sustained thermal/battery use, and long-session stability.

## Build warnings

The successful build emitted non-fatal Compose `Divider` deprecation warnings, an SDK XML schema-version compatibility warning, and a native-library strip notice for packaged AndroidX/DataStore libraries. There were no build errors or failing tests. No Tensor/TPU, Gemini Nano, NNAPI, or GPU inference acceleration is implemented or claimed.

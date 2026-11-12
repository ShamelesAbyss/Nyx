# NYX 0.4.0 Test Results

## Final automated run

From the v0.4.0 source, this clean command completed successfully with exit code 0:

```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

- **JVM tests:** 27 run, 0 failures, 0 errors, 0 skipped. Coverage: installed version formatter (1); credential transport policy (2); OpenAI-compatible auth/error protocol (5); Termux request/result protocol (6); DAG scheduler policy (4); agent tool safety (2); strict objective plan validator (7).
- **Debug APK:** assembled successfully as `com.nyx.ai`, versionCode `4`, versionName `0.4.0`, min API 26, target API 35.
- **Instrumentation APK:** `assembleDebugAndroidTest` succeeded and generated `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` (about 558 KB). Tests were compiled, not executed on a device.
- **Signing:** `apksigner verify` succeeded. Debug signer SHA-256 is `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`, matching the delivered v0.3.0 debug APK checked in this workspace. This supports an in-place update from that exact signer identity; it is not a production/store signature.
- **Migration:** Room schema v1→v2 migration instrumentation source compiled. No connected Android runtime was available to execute migration or verify legacy data preservation on a device.

## Release integrity and artifacts

Both checks passed using `tools/check_release_integrity.py`: pre-build mode cross-checked the configured Gradle version, release filenames, and current release-doc headings; post-build mode also parsed the packaged APK's binary manifest and confirmed package `com.nyx.ai`, versionName `0.4.0`, and longVersionCode `4`.

Delivered APK checksum (SHA-256):

- APK: `6a6cc48672a3df3a366b9d270520a34f59c0df5670a560cd2f70680e372f7808`

The source archive passed ZIP integrity testing and includes Gradle wrapper/configuration, Kotlin source, tests, docs, and the release checker. Local SDK settings, build outputs, caches, and signing keys are excluded.

## Not executed / physical device required

No Android device/emulator or live inference endpoint was available. Therefore the Room migration/data-preservation test, Android Keystore instrumentation tests, live Termux RUN_COMMAND permission/callback behavior, Debian PRoot, Ollama/OpenAI-compatible streaming/tool calls, API-key transport, objective execution/recovery on process death, Fold/GrapheneOS layout, thermal/memory/battery behavior, and long-duration stability were not exercised on device. `assembleDebugAndroidTest` proves compilation only.

The Termux adapter is library code not exposed in the UI or objective tools. Its timeout/cancel only stops NYX waiting for a callback; it does not supervise or guarantee termination of the Termux process. A successful Gradle build is necessary but does not establish end-to-end autonomy.

## Build warnings

The final build succeeded with non-fatal Android SDK XML version compatibility output, Compose `Divider` deprecation warnings, and a notice that two native libraries were packaged without stripping. There were no Kotlin compilation errors or failing JVM tests.

# Test Results — NYX 0.2.0

## Final build

Command run from the project root:

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

**Passed** with Android SDK Platform 35, Gradle 8.9, and JDK 21. It produced the debug APK and the Android-test APK. The APK is development-signed, not a store-release artifact.

| Verification | Result |
|---|---|
| Kotlin/Compose and Android resource compilation | Passed |
| Debug APK assembly | Passed |
| JVM unit tests | Passed: 7 tests, 0 failures, 0 errors |
| `assembleDebugAndroidTest` | Passed; instrumented test APK compiled |
| Keystore instrumentation tests executed on a device | Not run; no attached Android device/emulator |

The JVM tests cover calculator safety, HTTPS/loopback transport policy, OpenAI-compatible Bearer authorization on model and chat routes, and the absence of an Authorization header on Ollama requests. The instrumented test sources cover encrypted credential round-trip/deletion and ciphertext tamper rejection, but they have only been compiled—not executed—because Android Keystore requires a real Android runtime.

Gradle printed a non-blocking SDK XML-version compatibility warning and deprecation warnings for Compose `Divider`; neither prevented the build.

## Not verified on hardware or against a live service

No Android handset, foldable, Ollama service, or OpenAI-compatible server was available. Thus actual Keystore behavior on target devices, endpoint discovery against real services, live token streaming/tool calls, document-provider behavior, external-consent UX, fold hinge/posture handling, thermal/RAM behavior, performance, and battery impact remain unverified. The navigation and multi-pane layouts respond to window width, but were not device-tested.

## Remaining product limitations

Embedded llama.cpp/JNI/Rust inference, GGUF execution, GPU/Tensor acceleration, Gemini Nano integration, PDF/office import, a full endpoint-profile manager, thermal-aware scheduling, and production release signing are not included. GGUF selection remains registration-only. API-key at-rest encryption is implemented with Android Keystore AES-GCM, but secure-storage instrumentation tests need a connected device before that behavior can be claimed as runtime-verified.

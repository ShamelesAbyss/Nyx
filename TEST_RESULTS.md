# NYX 0.7.9 Test Results

## Clean build

Executed on 2026-11-12 UTC from `/home/ubuntu/nyx`:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL in 1m 17s`.

Only two pre-existing Kotlin warnings remain (`Divider` deprecation in `MainActivity.kt` and Room migration parameter-name warnings). No compile errors.

## JVM tests

- Total: **632**
- Failures: **0**
- Errors: **0**
- Skipped: **0**

New focused coverage:

| Class | Tests |
| --- | ---: |
| `OllamaGuestRuntimeTest` | 9 |
| `OllamaDiscoveryTest` | 6 |
| `OllamaLifecyclePolicyTest` | 30 |
| `OllamaProcessTest` | 26 |

`OllamaGuestRuntimeTest` covers `proot-distro ps` row parsing, malformed rows, new-session selection, capability markers, session start-token parsing, startup-stage classification, safe script construction, and token validation.

## APK identity

- Package: `com.nyx.ai`
- versionCode: **12**
- versionName: **0.7.9**
- minSdk: 26
- compileSdk / targetSdk: 37
- Native ABI: `arm64-v8a`
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`
- The signer digest is identical to the delivered v0.7.7 and v0.7.8 APKs, so this build installs as an update.

Artifact digests from this clean build:

| Artifact | SHA-256 |
| --- | --- |
| `NYX-v0.7.9.apk` | `48b0f1ef929f815dd5fb18380355fade65736c72039538bc131a07f2f881f3a6` |
| `NYX-v0.7.9-androidTest.apk` | `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` |

## Manifest and lifecycle checks

- Merged manifest contains `android.permission.FOREGROUND_SERVICE_DATA_SYNC`.
- `com.nyx.ai.service.OllamaHostService` is declared `android:exported="false"` with `android:foregroundServiceType="dataSync"`.
- The service is started only for a verified `DEBIAN_PROOT` guest; `TERMUX_HOST` keeps the direct lifecycle path.
- `OllamaGuestRuntime` probes `login --detach`, `ps`, and `kill` before choosing detached mode; the fallback keeps one foreground PRoot login alive.
- Stop verifies the guest PID, kills the recorded session, and re-inspects `/proc/<pid>` before reporting STOPPED.

## Not executed

No physical Android device was attached. The following remain device checks, not automated claims:

- actual `proot-distro ps` column output and detached-session lifetime;
- Termux `RUN_COMMAND` behavior while the NYX activity is backgrounded;
- Android 14+ `dataSync` foreground-service acceptance, persistent notification states, and View Activity/Stop Worker/Open Termux actions;
- guest `ollama serve` startup, model enumeration, and Android-to-guest API reachability;
- Chat's automatic loopback runtime startup and model refresh;
- Stop removing both the guest Ollama process and the PRoot session;
- the foreground fallback on a `proot-distro` release without `--detach`;
- adopted-server protection against a user-started Ollama process.

The JVM tests prove the parsers, state policy, and command construction. They do not replace an on-device run.

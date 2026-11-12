# NYX 0.5.0 Test Results

## Verified automated run

From the v0.5.0 source tree:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Result: `BUILD SUCCESSFUL`.

| Check | Result |
| --- | --- |
| `compileDebugKotlin` | Passed |
| `testDebugUnitTest` | **98 tests, 0 failures** |
| `assembleDebug` | Passed, `app-debug.apk` produced |
| `assembleDebugAndroidTest` | Passed, instrumentation APK compiled |
| Package identity | `com.nyx.ai`, versionName `0.5.0`, versionCode `5` |

## JVM coverage added in 0.5.0

- `CommandPolicyTest` (18 tests): read-only classification, unknown-executable confirmation, root/`su` blocking, protected Android settings, destructive `rm` and `git reset`, package installation, chained segments, empty/overlong rejection, quote-aware tokenization, working-directory traversal rejection, POSIX quoting, timeout wrapping.
- `VerificationTest` (14 tests): exit codes, case-insensitive output matching, injected file probes, SHA-256 comparison, tool-failure rejection, no-evidence semantics, persisted-object parsing, incomplete-spec rejection, failure-signature normalization, retry/review/permanent-failure decisions.
- `EnvironmentProbeParserTest` (5 tests): host probe parsing, installed-vs-supported distro sections, single-path parsing, Debian availability.
- `ConcurrentTaskSchedulerTest` + `ResourcePolicyTest` (15 tests): readiness, capacity, transitive blocking, deadlock detection, resource tiers.
- `ContextEngineTest` (11 tests): UTF-8 round trip, path confinement, atomic writes, restart reconstruction, dependency ordering, relevance ranking, budget truncation.
- `ProviderCapabilityTest` (8 tests): local URL detection, `/v1` detection, provider selection, capability cache behavior and bounds.
- Existing suites for arithmetic tools, plan validation, credential transport policy, and authenticated request routing continue to pass.

## Not executed here

These require hardware and were **not** run in this environment. They are unverified claims if presented otherwise:

1. Installing the APK on a physical device or foldable and exercising chat, agents, and workflows.
2. Termux `RUN_COMMAND` round trip, including `allow-external-apps=true`, the `com.termux.permission.RUN_COMMAND` grant, and callback result delivery.
3. `proot-distro` Debian login, package installation, and command execution.
4. Foreground-service behavior under Android 14/15 restrictions, Doze, and OEM battery management.
5. Process-death recovery of a real in-flight objective and lease expiry behavior.
6. Keystore instrumentation tests (`CredentialVaultInstrumentedTest`) and the Room 1→3 migration test; both compile but were not executed on a device.
7. Any real model output, token throughput, thermal throttling, or memory-pressure behavior.

## Delivered artifacts

| Artifact | Detail |
| --- | --- |
| `NYX-v0.5.0.apk` | 19 MB, debug-signed, `com.nyx.ai` 0.5.0 (versionCode 5) |
| APK SHA-256 | `e9d61f371b09ca8eccb96447b588d715bac714dba52512184e690da7b34bc636` |
| Signer | `CN=Android Debug`, SHA-256 `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` (same as the delivered v0.3/v0.4 builds, so in-place update is possible) |
| `NYX-Source-v0.5.0.zip` | Source, Gradle wrapper, tests, docs, and LICENSE; no build caches, local SDK settings, or signing material |

## Interpretation

The automated suite proves the deterministic logic: policy classification, path confinement, evidence evaluation, retry decisions, scheduling, context bounding, and provider routing. It does **not** prove device integration. A build that compiles and passes these tests can still fail on a specific Termux, PRoot, or OEM configuration.

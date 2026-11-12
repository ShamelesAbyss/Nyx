# NYX 0.8.0 Implementation Report

## Status

Historical local implementation and verification report. The later v0.8.0 release was published to GitHub; this report retains the pre-publication build evidence and does not describe the current v0.8.1 release. No Google Play publication was performed. Version metadata in this historical report is `versionCode 13` / `versionName 0.8.0`, package `com.nyx.ai`, min SDK 26, compile/target SDK 37, ARM64.

## Implemented scope

### 1. Environment preparation and objective continuation

- `DependencyBootstrap` is a testable orchestration layer over the existing `DependencyPlanner` and `DependencyInstaller`.
- Before an objective plans or resumes, `ObjectiveExecutionService` now:
  1. re-discovers the current Termux host and verified PRoot guests;
  2. reconciles the result with the objective's stored environment snapshot;
  3. probes the selected environment's real tools;
  4. resolves the guest JDK package from APT candidates rather than guessing;
  5. installs only trusted candidates that the shared autonomy policy authorizes;
  6. re-probes every required executable after installation; and
  7. continues the original objective only after the environment is verified.
- The original objective ID, task graph, workspace, checkpoints, and provider snapshot are preserved.
- A failed or approval-blocked bootstrap stops with a current `needs_attention` diagnostic instead of running a doomed task or retrying an uncertain installation.
- A successful current probe clears a stale historical environment error.

#### Debian APT source detection fix

The physical-device failure was caused by a probe that only counted source lines beginning with
`deb`. Debian 13 Trixie stores its repositories in Deb822 format at
`/etc/apt/sources.list.d/debian.sources`, so NYX incorrectly reported `APT sources: 0` and refused
the otherwise valid OpenJDK 21 candidate.

`GuestJdkResolver.aptSourceCountScript()` now performs a read-only scan of:

- traditional `sources.list` and `sources.list.d/*.list` lines beginning with `deb`;
- Deb822 `sources.list.d/*.sources` stanzas.

Deb822 stanzas count only when `Types` contains `deb` (not only `deb-src`), `Enabled` is not `no`,
and both `URIs` and `Suites` are present. The scanner never rewrites sources and never runs
`apt-get update`. Candidate selection still requires a real `apt-cache policy` candidate; on
Trixie, the real `openjdk-21-jdk-headless` candidate `21.0.13+9-1~deb13u2` is preferred, with
`default-jdk-headless` `2:1.21-76` as fallback.

### 2. Full Autonomy scoped installations

- Package installation uses `ActionKind.INSTALL_PACKAGE` with a target under the authorized workspace.
- A previously granted, workspace-scoped `packages.install` routine grant is reused in Full Autonomy without a redundant prompt.
- Manual and Smart modes retain their existing approval requirements. No wildcard grant is created.
- Installation failure classification distinguishes package-manager locks, repository failures, incompatible packages, exhausted storage, and network failures.
- Every install command and result continues through the audited `TerminalRepository` history.

### 3. Policy-gated `terminal_run`

- `TerminalAgentTools` no longer denies all model-directed shell commands.
- `ModelCommandPolicy` validates the workspace, working directory, command length, timeout, and risk. The initial directory must be the approved workspace or a safe subdirectory.
- Credential and private-data paths are denied. Destructive commands require explicit human handling and are not executed by the model.
- `AutonomyModelCommandGate` applies `RUN_READONLY_COMMAND` or `RUN_MUTATING_COMMAND` through the shared policy, so Full Autonomy grants work only inside their scoped workspace and environment.
- Commands are bounded by a timeout and produce auditable exit-code/stdout/stderr evidence.
- This is a policy boundary, not an OS sandbox. A shell command can still use tools that the underlying Termux/PRoot environment permits.

### 4. Environment consistency

- `EnvironmentConsistency` treats a fresh guest verification as the current source of truth.
- A nested PRoot host shell, missing Termux permission, failed `proot-distro list`, or unverified guest produces a current actionable diagnostic.
- Historical objective errors are not reused as proof that the environment is still unavailable.

## Files added

| File | Purpose |
| --- | --- |
| `app/src/main/java/com/nyx/ai/terminal/DependencyBootstrap.kt` | Probe/plan/install/verify orchestration and gateway adapter |
| `app/src/main/java/com/nyx/ai/terminal/EnvironmentConsistency.kt` | Current environment reconciliation and stale-error handling |
| `app/src/main/java/com/nyx/ai/terminal/ModelCommandPolicy.kt` | Pure command policy and autonomy-backed gate |
| `app/src/test/java/com/nyx/ai/terminal/DependencyBootstrapTest.kt` | Ready, install, approval, blocker, origin, and workspace tests |
| `app/src/test/java/com/nyx/ai/terminal/ModelCommandPolicyTest.kt` | Confinement, destructive, credential, and timeout tests |
| `app/src/test/java/com/nyx/ai/terminal/EnvironmentConsistencyTest.kt` | Verified guest, stale error, nested host, and host tests |
| `app/src/test/java/com/nyx/ai/terminal/PackageInstallerTest.kt` | Failure classification tests |

Modified production files: `ObjectiveExecutionService.kt`, `TerminalAgentTools.kt`, `PackageInstaller.kt`, and `app/build.gradle.kts`. Modified documentation: `CHANGELOG.md`, `TEST_RESULTS.md`, and this report. No screens, branding, or unrelated integrations were changed.

## Verification

Final clean build command:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Results are recorded in `TEST_RESULTS.md` after the run. The APK and instrumentation APK compile; no physical Android device was attached.

### Verified result

- `BUILD SUCCESSFUL in 1m 20s`.
- JVM tests: **671** across 62 classes; **0** failures, **0** errors, **0** skipped.
- Debug APK: `com.nyx.ai`, versionCode 13, versionName 0.8.0, minSdk 26, compile/target SDK 37, `arm64-v8a`.
- Debug APK SHA-256: `e2c75971b003859fb771962aa9bf259f177651c8ab0ff7f9542230516e35cdf8`.
- Instrumentation APK SHA-256: `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3`.
- Debug signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` (unchanged).
- 16 KB ZIP alignment check passed.

## Limitations and non-claims

- No on-device validation of Termux `RUN_COMMAND`, Android 14+ foreground-service behavior, PRoot guest APT installation, package locks, storage exhaustion, or real command execution.
- No claim that PRoot is a security sandbox or that `terminal_run` is safely contained at the OS level.
- No AAB or Play publication was performed; the APK remains debug-signed.
- Approval-blocked dependency plans are not auto-approved; they stop with the exact packages and reason.

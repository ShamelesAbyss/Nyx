# NYX v0.7.0 Implementation Status

Internal engineering note for Project Ascension. This is not a publication record.

## Implemented

The autonomy layer is centralized in `autonomy/policy/AutonomyPolicy.kt` and `AutonomyRuntime.kt`, with Manual, Smart, and Full modes, capability grants and scope checks, delegated-scope narrowing, risk classification, persisted grants, a bounded audit journal, Emergency Stop, and a process-wide runtime. `TerminalRepository` accepts an optional `ActionRequest` and blocks denied or unconfirmed actions before dispatch; package installation authorizes the complete plan before the first command; the objective service refuses to start while Emergency Stop is active.

Environment intelligence is implemented in `EnvironmentCapabilities.kt`, `ExecutableOrigin.kt`, `DependencyPlanner.kt`, `PackageInstaller.kt`, and `WorkspaceProvisioner.kt`. The probe reports origin and package manager, inherited Termux binaries inside a guest are marked incompatible, dependency plans use only official `apt` or `pkg` candidates, the installer re-probes after installation, and workspace creation is collision-safe and write-verified.

Controlled network access is implemented in `NetworkPolicy.kt`, `NetworkTools.kt`, and `NetworkAgentTools.kt`: HTTPS for public hosts, private/loopback-pivot refusal, redirect re-validation, guarded DNS, bounded text fetch, untrusted-content wrapping, and atomic SHA-256 downloads. The objective tool surface exposes `fetch_url` only.

Supervision and delegation are implemented in `SupervisorPolicy.kt`, `WorkerRoles.kt`, and `CompositeToolProvider.kt`, and are wired into `ObjectiveExecutionEngine` for role prompts, role events, and a merged terminal/network tool surface.

The UI exposes the policy mode with a Full consent dialog, Emergency Stop and Resume, grant revocation, project creation, dependency planning, install approval, and inherited-tool labels.

## Verification

The frozen source completed a debug build, 450 JVM tests in 43 classes with zero failures, and instrumentation-test APK compilation. APK identity is `com.nyx.ai` versionName 0.7.0 / versionCode 8, compile/target SDK 37, min SDK 26, ARM64 only, signed with the same debug certificate as v0.6.1.

## Remaining

No physical Android device was available. Termux dispatch, PRoot execution, real `apt`/`pkg` installation, Keystore behavior, notification actions, Emergency Stop across service restart, process-death recovery, and device network access remain device-verification items. Role delegation is prompt-level specialization over existing bounded workers, not a separate model process per role. Arbitrary file downloads are not exposed as a model tool.

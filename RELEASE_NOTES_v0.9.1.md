# NYX v0.9.1 — Project Ignition

Package `com.nyx.ai` · versionName `0.9.1` · versionCode 24 · ARM64 · API 26–37.

**Source candidate. Publication is blocked until all release gates pass and the established signing key is available. Public stable downloads remain v0.9.0.**

## Quick Actions

Autonomy → Objectives now offers three explicitly selected deterministic operations through the existing ObjectiveExecutionEngine:

- **Inspect project:** bounded read-only project discovery, language/build markers, available toolchain and fixed operations. Never executes the project's build/test scripts or installs dependencies.
- **Run approved project tests:** executes the existing discovered fixed test procedure for the selected trusted workspace. Retains command identity, real exit/output and task-bound evidence. Trusted scripts retain Termux authority and may modify files or access the network; they are not OS-isolated.
- **Verify Workbench output:** checks an explicitly selected existing objective/artifact and pinned digest using the retained store's ownership, length, format and integrity checks. Creates an execution report, not a new artifact or a claim of factual accuracy.

No preliminary planning, model loading/probing or model inference is needed. A rejecting inference session prevents accidental calls. Ordinary capability-aware planning, including text-only Direct, remains in place for other objectives.

## Authorization and recovery

Approval is action-specific and saved with its objective, workspace or artifact. Project actions require saved and current terminal approval; artifact verification has no terminal/research/gateway grant. No shell override, install, model-selected operation or inferred permission is admitted. Tasks use the existing scheduler, task-local tools, verifier, evidence records and cancellation/recovery. Interrupted or failed Quick Actions require a new explicit selection; uncertain scripts are never automatically replayed.

## Measurements and verification boundary

Submission and tool start/end wall timestamps are retained. Permission validation, task creation, dispatch, operation dispatch, first verified result and final completion have run-local monotonic timing; the verified-result event also reports wall time from objective creation. These clocks include different scopes and must not be silently mixed. Existing model-planned workflows retain request/input/output/first-text metrics. Host fixture timings are not a real-model/device speed benchmark or token-generation improvement. Historical v0.8.7 latency observations do not establish a v0.9.0 regression.

See [implementation report](V091_IMPLEMENTATION_REPORT.md) and [test results](TEST_RESULTS.md) for the actual current verification outcome. Physical Android, Room upgrade, foldable layout, Binder/Termux/PRoot and real-provider acceptance are UNVERIFIED without a device. No database schema/migration, package ID, native inference, provider, dependency or SDK policy change is introduced.

## Distribution gate

The established signer is SHA-256 `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. A local newly generated debug key is build-only and cannot produce a public upgrade APK. Source may be committed/pushed while signing is blocked. No v0.9.1 tag/release/download is claimed until exact-commit hosted CI, signing continuity, source correspondence, independent checksums, ARM64/ZIP/ELF and notices pass.

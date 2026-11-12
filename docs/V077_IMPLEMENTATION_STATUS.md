# NYX v0.7.7 — Project Liberation: implementation status

Status date: 2026-11-12. This document is the engineering handoff for the **published** v0.7.7 build. It does not claim physical-device success.

## 1. Debian Ollama discovery and management

### Implemented

- `OllamaTarget` models the Termux host or a validated PRoot guest alias. Host and guest have separate `environmentId`, label, filesystem, and PID namespace.
- `OllamaServerManager.configureGuests(...)` accepts only aliases that environment discovery actually verified. At most four guests are probed.
- `OllamaServerManager.detect()` probes the host and each verified guest with the read-only `OllamaProcess.detectScript()`.
- `OllamaDetection` now records:
  - `target`
  - `architecture` (`uname -m`)
  - `compatible` (binary executes and identifies as Ollama)
  - existing `installed`, `path`, `version`, `error`
- `/usr/local/bin/ollama` is found by `command -v` inside the selected environment. A warning-prefixed `ollama --version` output is parsed for a semantic version and marked compatible.
- `OllamaDiscovery.choose(...)` deterministically prefers the current environment, then the host, then a compatible guest. It retains incompatible candidates for diagnostics and returns a clear missing/incompatible detail.
- Lifecycle scripts run in the selected target:
  - host: `runHostIsolated(...)`
  - guest: `runShell(... DEBIAN_PROOT, guestAlias=...)`
- Start/stop/state scripts still verify PID **and** kernel start token. No `pkill`, no process-group kill, and adopted servers are never signalled.
- `OllamaServerStatus` distinguishes `NOT_INSTALLED`, `INCOMPATIBLE`, `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, `UNREACHABLE`, and `ERROR`.
- Detection/refresh calls `refreshModels()` when the API is healthy, reconnecting Chat to the existing model inventory without downloading or duplicating models.
- Creating or resuming an objective against a loopback endpoint starts the managed server if disconnected, waits for readiness, refreshes models, and continues.
- `setsid` startup has a `nohup` fallback for PRoot environments that reject session creation.

### Not device-verified

- Real `proot-distro login debian -- ...` execution from Termux `RUN_COMMAND`.
- Real `/usr/local/bin/ollama serve` detachment and survival after the Termux command returns.
- Real API readiness polling against a Debian-hosted server.
- Actual model listing and inference after reconnect.

## 2. Full-Autonomy workspace authorization

### Implemented

- `ActionKind.CREATE_WORKSPACE` is a distinct routine action with capability `workspace.create`.
- `createWorkspace(...)` authorizes against the **approved base root**, not the temporary project name.
- Full mode allows repeated creation inside the authorized root (`full_routine_workspace`) without requiring a per-project elevated grant.
- Manual mode still requires approval. Smart follows its routine/high-risk policy.
- The UI presents a dedicated approval card for a pending project name and does not expose `full_elevated_grant_required` as the only feedback.
- The provisioner remains atomic, collision-safe, traversal-resistant, and never reuses an existing directory.
- After creation, the selected workspace is persisted and a routine grant is scoped to the **new project** for subsequent reads/writes.

### Tests

- `AutonomyPolicyEngineTest.fullAutonomyCanCreateAProjectInsideTheAuthorizedRootWithoutAnElevatedGrant`
- `AutonomyPolicyEngineTest.manualAutonomyStillRequiresApprovalToCreateAProject`
- Existing `WorkspaceProvisionerTest` collision/no-reuse coverage.

## 3. Environment provisioning

Existing v0.7.5 components remain wired: environment discovery, capability profiles, dependency planning, package installation, tool verification, and development preflight. This release fixes workspace authorization so those components can be used repeatedly. It does **not** add automatic objective continuation after an install; the user still starts the objective after setup. This is recorded as a remaining gap rather than claimed as complete.

## 4. Arsenal, gateways, source intelligence

No new framework was added. Existing v0.7.5 behavior is preserved:

- Nine built-in Arsenal procedures with relevance-bounded prompt injection.
- MCP/GitHub gateways with per-server allowlists, budgets, timeouts, redacted logs, and Keystore-only tokens.
- Evidence-linked project memory and Kotlin/Java symbol index.

## 5. Supervision and delegation

Existing v0.7.5 scheduler, specialist roles, supervisor policy, context store, and verification remain. v0.7.7 adds automatic local-inference reconnect before planning/resume, reducing a common failure path without changing the autonomy policy model.

## 6. Live Worker Console and UI

- Managed Ollama card shows the selected host/guest, status, version, architecture, endpoint, and probed installations.
- Stop is available for any verified owned process, including `UNREACHABLE`.
- The global error banner is limited to Chat, Models, Autonomy, and Settings and is cleared when navigating to unrelated screens.
- Existing persisted worker console remains the observable timeline; no fabricated reasoning is rendered.

## 7. Safety and reliability

- Target-scoped PID/start-token records prevent a host record from being used to stop a guest process, or vice versa.
- Adopted/unverified processes are never signalled.
- Emergency Stop blocks lifecycle and workspace actions.
- Policy state remains synchronized.
- APK package identity, Room schema, native inference, and signing certificate are preserved.

## 8. Required physical-device checklist

1. Install the v0.7.7 APK over v0.7.5 (same debug certificate) without clearing data.
2. In Debian, confirm `command -v ollama` returns `/usr/local/bin/ollama`.
3. With the server stopped, open Models and tap **Detect**. Confirm the card reports `Termux host` or `Debian guest · debian`, version `0.40.13`, `aarch64`, and `STOPPED`.
4. Tap **Start**. Confirm readiness reaches `RUNNING`, the PID is shown as NYX-owned, and the existing model list appears.
5. Open Chat and send a prompt. Confirm it uses the Debian-hosted server and does not require a terminal command.
6. Tap **Stop**. Confirm only the NYX-owned PID stops and the status becomes `STOPPED`.
7. Tap **Start** again, then **Restart**. Confirm no duplicate server and no adopted-server kill.
8. Create two different projects under the same authorized root in Full mode. Confirm both succeed without `full_elevated_grant_required` and neither reuses the other's directory.
9. In Manual mode, confirm the workspace approval card appears and Cancel creates nothing.
10. Create a development objective while the local server is stopped. Confirm NYX starts it, reconnects, and proceeds to planning.
11. Kill the app during a running objective. Reopen and confirm recovery does not blindly repeat an uncertain side effect.
12. Verify the Live Worker Console shows real tool/terminal/verification events.

## 9. Release status

- versionCode 10 / versionName 0.7.7.
- 609 JVM tests, zero failures/errors/skips.
- Debug APK and instrumentation-test APK built.
- Source ZIP and SHA-256 manifest prepared.
- Published as the v0.7.7 GitHub release; physical-device acceptance is still outstanding.

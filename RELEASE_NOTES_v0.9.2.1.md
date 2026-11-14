# NYX v0.9.2.1 — Project Relay Ownership Hotfix

**Package:** `com.nyx.ai`  
**Version:** `0.9.2.1` (versionCode `26`)  
**Date:** 2026-11-14  
**Status:** patch release over the published v0.9.2 release.

## Why this patch exists

v0.9.2 persisted a guest launch token and fence before dispatching a PRoot session. If the process or service was interrupted after that checkpoint but before a session PID and kernel start token were saved, the next launch could not distinguish:

- a launch that never reached Termux;
- a launch that was still in flight;
- a session that had already ended; or
- a session whose PID record was unavailable.

The old recovery path required a saved session PID/start token and a prior “guest process stopped” flag. A pending or reboot-stranded launch had neither. The result was a permanent `ERROR` state: `Start` could not launch, `Stop` had no verified handle, and repeated `Detect` polling could open a stream of short-lived Termux execution sessions.

This patch repairs that state machine. It does not relax the existing rule that an unverified process is never signalled.

## Changes

### 1. Evidence-based ownership recovery

A new `OllamaRecoveryPolicy` classifies the saved evidence into explicit states:

- healthy NYX-owned process;
- healthy external server;
- token/start-verified live PRoot session;
- stale detached record recoverable after independent absence checks;
- pending launch recoverable only after its verification grace period;
- pending launch still unconfirmed;
- ambiguous ownership; or
- verification unavailable.

Ownership is cleared only when all required observations agree:

- the guest process probe completed;
- no NYX-owned guest process remains;
- no process or session carries the saved launch token; and
- either the saved detached session is verifiably absent, or an old pending checkpoint is outside its grace period.

A failed probe, an unknown ownership verdict, a live token process, or a live session retains the fence. A healthy server is adopted without claiming stop authority.

### 2. Safe, verified session termination

`Stop` can now end a PRoot session only after the session identity matches the saved token, guest namespace, PID, and kernel start token. The stop script rechecks those values inside the same shell immediately before `proot-distro kill`, then independently verifies that the session PID is absent. If the guest Ollama process cannot be proven gone afterward, the fence is retained rather than reporting a clean stop.

The two-argument script form remains only for script-level regression fixtures. Production recovery always supplies the token and start-token guard.

### 3. Read-only reconciliation and bounded probing

`Refresh` now performs a read-only reconciliation. When the saved evidence is safely stale, it clears the fence and reports that `Start` is available. When the evidence is ambiguous, it explains the specific missing observation and keeps `Start` unavailable.

The managed-server poll no longer runs a fresh full installation discovery on every tick. It uses one bounded `refresh()` loop with a single active poll job, longer intervals, and a read-only foreground-service supervisor. This prevents a launch from creating an uncontrolled sequence of Termux windows.

### 4. Clear recovery UI

The managed Ollama card now shows:

- `Reconcile` instead of `Refresh` while a fence exists;
- a recovery explanation when ownership is unresolved;
- `Start` only when no fence remains; and
- `Stop` only when a verified process or session handle exists.

The existing Eclipse layout, branding, badges, screenshots, and violet styling are unchanged.

### 5. Legacy pending-checkpoint migration

Older v0.9.2 pending records did not store a recovery timestamp. The first reconciliation records one, then applies the same bounded grace period before releasing the record. This avoids treating an in-flight dispatch as stale while still allowing a genuinely stranded record to recover.

## Safety invariants preserved

- A PID is never signalled from a stale record alone.
- A saved token is not proof that a process is alive; a missing PID is not proof that it is gone.
- Provider reasoning remains separate from plan text.
- Invalid or partial model output still fails closed.
- Inference workers still receive only output-existence evidence; tool workers require observed local evidence.
- Direct conversational routing still grants no tools.
- The package identity, signing certificate, SDK levels, ARM64 configuration, and vendored llama.cpp are unchanged.

## Verification

The patch was verified on the host/JVM build environment:

- one clean network-enabled build passed in 2m 31s: 1,233 JVM tests across 136 suites, zero failures, errors or skips;
- new recovery, guest-runtime, process-parsing, and lifecycle regressions;
- debug app and androidTest APKs assembled with the established certificate;
- `aapt2` identity check: `com.nyx.ai`, versionName `0.9.2.1`, versionCode `26`, minSdk 26, target/compile 37;
- signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`;
- 16 KB ZIP alignment check passed.

Exact APK sizes and SHA-256 values are recorded in [V0921_IMPLEMENTATION_REPORT.md](V0921_IMPLEMENTATION_REPORT.md) and the delivery checksum manifest.

## Known limitations

- No physical Android instrumentation was executed. The androidTest APK was assembled only.
- No real-device Ollama, Termux, Debian/PRoot, foldable, or keyboard/fold transition was verified.
- No installation over a published v0.9.2 build was performed on hardware. versionCode 26 is monotonically updateable in principle, not proven on a device.
- The recovery policy is exercised by deterministic JVM fixtures. It is not a substitute for observing a real interrupted Termux session.
- The debug certificate is a development identity, not a production/Play signing process.
- Application-level restrictions are not an OS sandbox.

## Assets

- `NYX-v0.9.2.1.apk`
- `NYX-v0.9.2.1-androidTest.apk`
- `NYX-Source-v0.9.2.1.zip`
- `NYX-v0.9.2.1-SHA256SUMS.txt`
- `NYX-v0.9.2.1-RELEASE_NOTES.md`
- `NYX-v0.9.2.1-IMPLEMENTATION_REPORT.md`

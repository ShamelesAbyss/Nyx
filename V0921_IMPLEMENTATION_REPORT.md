# NYX v0.9.2.1 — Implementation and Verification Report

**Package:** `com.nyx.ai`  
**Version:** `0.9.2.1` / versionCode `26`  
**Baseline:** published v0.9.2 (versionCode `25`) on `main`  
**Date:** 2026-11-14  
**Status:** local release candidate; publication is a separate authorized step.

## Summary

This patch repairs a persistent PRoot ownership fence that could leave managed Ollama stuck in `ERROR` after an interrupted launch. It adds an evidence-based recovery state machine, token/start-verified session termination, read-only reconciliation, bounded polling that no longer performs full discovery on every tick, and clear recovery controls in the existing Compose UI.

No application permissions, database schema, package identity, signing identity, SDK levels, ARM64 configuration, or vendored llama.cpp sources were changed.

## Failure sequence

The previous flow committed a durable pending token before dispatch. It then attempted to save the session PID and kernel start token after the launch. If the app process or foreground service was interrupted between those steps, the next process could see:

- `guest_launch_fence` and `session_token` present;
- `session_pid` absent or zero;
- `session_start_ticks` absent;
- no `guest_process_stopped` evidence; and
- a guest process record that was missing, stale, or unreadable.

`doStart()` refused to launch because a fence existed. `doStop()` required a saved session PID and the prior stopped flag, so it refused too. `doRefresh()` turned any retained fence into `ERROR`. `Start` remained visible in some states but could only fail again, and `Detect` performed full discovery on every poll.

## Recovery model

`OllamaRecoveryPolicy` is a pure decision layer. It receives observations, never desired outcomes, and returns one of:

| State | Release allowed? | Meaning |
| :--- | :--- | :--- |
| `HEALTHY_OWNED` | No | A verified NYX-owned guest process is alive. |
| `HEALTHY_EXTERNAL` | No | An Ollama API answers, but NYX cannot claim the process. |
| `VERIFIED_LIVE_SESSION` | No | A token/start-verified PRoot session is alive. |
| `STALE_RECORD_RECOVERABLE` | Yes | The guest process and verified session are both absent. |
| `PENDING_LAUNCH_RECOVERABLE` | Yes | An old pending checkpoint has no token-bearing process and no guest record. |
| `PENDING_LAUNCH_UNCONFIRMED` | No | The pending launch is still inside its grace period. |
| `AMBIGUOUS_OWNERSHIP` | No | Some protected process may exist, but identity is incomplete. |
| `VERIFICATION_UNAVAILABLE` | No | A required read-only probe failed. |

The release paths require a successful guest probe, no live NYX-owned process, no token-bearing host/session process, and either a verified-dead detached session or an old pending checkpoint. Null observations never authorize release.

## Implementation details

### Recovery evidence

`OllamaServerManager` now:

- aligns the target with the single saved guest fence before reading evidence;
- reads a host snapshot that records whether the probe actually returned an alive/dead marker;
- probes the saved detached session with `sessionStateScript`;
- probes the host process table for the saved token with a read-only script that excludes its own shell and parent;
- records a migration timestamp for legacy pending records that lack one;
- clears the fence only when `OllamaRecoveryPolicy` authorizes it; and
- keeps a specific diagnostic message when it does not.

`OllamaHostSnapshot` gained a `probeSucceeded` field so a successful observation with no PID file is distinguishable from a failed probe. A missing record with a completed probe can be stale; a failed probe cannot.

### Session termination

`OllamaGuestRuntime.sessionKillScript` now accepts optional token, start-token, and namespace guards. Production Stop always supplies them. The generated shell:

1. refuses if the session PID is absent;
2. rechecks the token and namespace in `/proc/<pid>/cmdline`;
3. rechecks the kernel start token in `/proc/<pid>/stat`;
4. calls only `proot-distro kill <pid>`;
5. verifies the session PID is absent afterward; and
6. reports a refusal rather than claiming success if any guard fails.

After a verified session ends, the manager reads the guest record again. If the guest Ollama process still appears owned, the fence remains and Stop reports that the process was not proven gone.

### Bounded reconciliation

`Refresh` is now the safe recovery path. It performs read-only probes and either clears provably stale evidence or explains the missing observation. `Start` first probes the API, so a healthy server is adopted even while a fence exists; a second launch is never attempted.

The ViewModel now keeps a single managed-Ollama poll job. It uses `refresh()` rather than full `detect()` discovery, starts at short intervals and backs off to 15 seconds, and caps the schedule. The foreground service supervises with `refresh()` every 30 seconds instead of full discovery every 20 seconds. This removes the repeated host/guest installation probes that could open many short-lived Termux sessions.

### UI

The managed Ollama card retains the existing Eclipse visual language. Release-specific behavior changes are limited to:

- `Reconcile` label while recovery is required;
- a recovery explanation with the specific unresolved evidence;
- `Start` exposed only through `OllamaServerState.canStart` (no fence, not running, not busy); and
- `Stop` exposed only for a verified process or detached session handle.

## Files changed

Production:

- `app/build.gradle.kts` — versionName `0.9.2.1`, versionCode `26`.
- `app/src/main/java/com/nyx/ai/ollama/OllamaRecoveryPolicy.kt` — new pure recovery state machine.
- `app/src/main/java/com/nyx/ai/ollama/OllamaGuestRuntime.kt` — pending timestamp, token-process probe, guarded session kill.
- `app/src/main/java/com/nyx/ai/ollama/OllamaProcess.kt` — snapshot probe-success evidence.
- `app/src/main/java/com/nyx/ai/ollama/OllamaServerState.kt` — recovery fields and `canStart`.
- `app/src/main/java/com/nyx/ai/ollama/OllamaServerManager.kt` — recovery, safe stop, reconciliation, persistence migration.
- `app/src/main/java/com/nyx/ai/NyxViewModel.kt` — single bounded refresh poll and action guards.
- `app/src/main/java/com/nyx/ai/service/OllamaHostService.kt` — read-only supervision.
- `app/src/main/java/com/nyx/ai/MainActivity.kt` — recovery controls and diagnostics.
- `tools/check_release_integrity.py` — v0.9.2.1/code 26 release gates and self-test fixtures.
- `tools/build_release.sh` — v0.9.2.1 delivery names.

Tests:

- `OllamaRecoveryPolicyTest` — every recovery state, release boundary, probe-unavailable case, and token/live-session case.
- `OllamaGuestRuntimeTest` — token scan exclusion and guarded session termination.
- `OllamaProcessTest` — successful missing-record observation versus failed probe.
- `OllamaLifecyclePolicyTest` — `canStart` is false while a fence exists.

Documentation:

- `README.md` — release-specific links, notes, history, package table, footer.
- `CHANGELOG.md` — v0.9.2.1 entry.
- `USER_GUIDE.md` — managed Ollama recovery behavior.
- `BUILD.md` — v0.9.2.1 build/verification commands.
- `TEST_RESULTS.md` — final test facts.
- `NYX_DEVELOPMENT_REPORT.md` — patch scope and limitations.
- `RELEASE_NOTES_v0.9.2.1.md` — release-facing notes.
- `V0921_IMPLEMENTATION_REPORT.md` — this report.

## Verification

### JVM suite

The final command was `NYX_REAL_NETWORK_TEST=1 ./gradlew --no-daemon --max-workers=2 --console=plain clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`. It passed in **2m 31s** with **1,233 tests across 136 suites, zero failures, zero errors, zero skipped**. Both APKs assembled in the same run. The two opt-in public HTTPS acceptance tests ran; no test was skipped.

### APK facts

Final packaged values are recorded here after assembly and packaging:

| Item | Value |
| :--- | :--- |
| App APK | `NYX-v0.9.2.1.apk` |
| App size | 35,500,791 bytes |
| App SHA-256 | `c5be938f42dca7b9fa2db5779c4f5e068a866312b64b0b105e205305ddef73d9` |
| androidTest APK | `NYX-v0.9.2.1-androidTest.apk` |
| androidTest size | 1,024,814 bytes |
| androidTest SHA-256 | `33a4247615607bdf170ac21dd32a01d82c387e1176163ad2bfe17bd25d8c514a` |
| Package | `com.nyx.ai` |
| versionName / code | `0.9.2.1` / `26` |
| min / compile / target SDK | 26 / 37 / 37 |
| ABI | `arm64-v8a` |
| Signer SHA-256 | `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` |
| 16 KB alignment | `zipalign -c -P 16 -v 4` passed |

The delivery manifest, not this table, is authoritative for the packaged set.

## Packaging and integrity

`tools/build_release.sh` now uses v0.9.2.1 filenames and the updated integrity checker. The source archive is generated by `check_release_integrity.py --make-source`, which excludes Git metadata, build outputs, caches, local configuration, signing material, APK/AAB files, and model weights. The delivery manifest covers the app APK, androidTest APK, source ZIP, release notes, and this report; it does not hash itself.

## Known limitations

- No physical Android instrumentation was executed. The androidTest APK was assembled only.
- No real-device Ollama, Termux, Debian/PRoot, foldable, or keyboard/fold transition was verified.
- No upgrade-over-v0.9.2 installation was performed on hardware. versionCode 26 is monotonic in principle, not proven by an on-device install.
- The recovery policy is covered by deterministic JVM fixtures, not by an actual interrupted Termux session.
- No measured real-model planning-speed improvement is claimed.
- The debug certificate is a development identity, not a production/Play signing process.
- Application-level restrictions are not an OS sandbox.

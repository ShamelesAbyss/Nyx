# NYX v0.9.1 — Project Ignition implementation report

Baseline: official `ShamelesAbyss/Nyx` main `8d46f8720f37af3bfec607c5abb506fb23b9e843`; no v0.9.1 tag existed at setup. Baseline hosted Verify succeeded: https://github.com/ShamelesAbyss/Nyx/actions/runs/43586040838. The connector only lists PR-triggered runs; the public Actions API was used to verify push-triggered runs. Scope ends at v0.9.1.

## Implementation

`QuickAction` is an immutable locally selected contract, bound to a new objective, one fixed type, approval and a normalized workspace or retained artifact ID/source objective/pinned digest. Extra contract fields, malformed identifiers, traversal and cross-objective ownership are rejected. The ViewModel snapshots this contract durably and creates a normal objective without connecting/loading a model. The service rechecks current terminal approval, refuses replay, binds the saved workspace/environment, disables model-command/gateway access and invokes `executeQuickAction` in the existing engine.

The engine creates one task with one attempt. It uses its existing scheduler, task records, context records, cancellation controls, task-local TerminalAgentTools, actual fixed process ledger, common verifier and evidence persistence. Its deterministic branch calls only project_inspect, test_run or retained-store verification. A session whose inference methods throw prevents accidental model use. No second orchestration engine, natural-language permission inference or native runtime rewrite is added. Agent skills and previous-objective tool lists cannot choose another operation. Tests require a real infrastructure-valid fixed process exit; API/discovery status cannot substitute for a child process.

Inspection is read-only; even a project's malicious test entry point is not executed during inspection. Approved tests execute trusted project code, so existing same-UID/network/TOCTOU limitations remain. Verification pins source ownership and existing artifact metadata/bytes; it never synthesizes a new write witness or copied disk artifact. The resulting objective's text execution report is explicitly separate from the retained source artifact.

The Compose card sits within the existing Objectives panel. Action changes, project/environment changes and artifact/objective changes revoke its checkbox approval. Users select existing objectives/artifacts, review scope, start, view the normal mission/live activity/task result and open the existing Workbench. No navigation redesign is introduced. Android/foldable rendering is UNVERIFIED.

## Measurement contract

`quick_submission`: objective creation wall timestamp. `quick_timing`: permission_validated, task_created, execution_dispatch, operation_dispatch, first_verified_result, final_<status>; run-local elapsedNanos and objectiveElapsedWallMillis. `quick_first_operation`: earliest actual task-local tool observation startedAt (wall time), separate from Kotlin operation dispatch. `quick_operation_evidence`: owned tool start/end and exit, plus bounded stdout/stderr. `quick_project_execution`: actual fixed command, operation ID, workspace, ownership, result kind, exit and timestamps. Host runs print timing events into JUnit XML. No remote model performance numbers are fabricated.

Time to first verified action is measured at first_verified_result. Run-local monotonic time excludes ViewModel/service startup; submission-to-result wall time includes it and is susceptible to system clock changes. Provider metrics for ordinary plans already include request/input/response/first-text evidence. No real-model comparable baseline or physical device was available in this session; therefore no numerical real-model/device speedup is claimed. Zero Quick Action planning/model calls is independently enforced and tested.

## Compatibility and release gates

VersionName 0.9.1 / versionCode 24 / com.nyx.ai; Room entities, schema versions, migration code, model/provider settings and native sources unchanged. Contract data uses existing execution_config events, absent in old objectives; missing Quick Action key preserves ordinary planning. Existing recovery remains review-based. Source compatibility is tested, but actual installed-data upgrade requires Android and the original signer.

Release scripts/checks are advanced to 0.9.1/24 without weakening signing, source membership, obsolete bundle, notices or alignment checks. README layout/branding/badges/images/navigation/historical releases are retained. The download table now points to the verified v0.9.1 assets in this published release.

Checkout note: Windows cannot faithfully check out both CHANGELOG.md and Changelog.md. The canonical development checkout is `/home/shamelesabyss/source/Nyx` in WSL Ubuntu (accessible as `\\wsl.localhost\Ubuntu\home\shamelesabyss\source\Nyx`). Both tracked documents are preserved. Preliminary Windows clones/source snapshot are outside OneDrive and are not publication artifacts.

## Verification outcome

**Verified locally:** clean build passed in **2m 50s**; the full suite then reran with real-network acceptance enabled in **2m 6s**. Result: **1,198 JVM tests across 133 suites, zero failures/errors/skips**, including **16 Ignition regressions**. Both APKs were assembled. Identity (`com.nyx.ai`, 0.9.1/code 24, API 26–37), ARM64, notices and 16 KB ZIP/ELF checks passed with the established signing certificate. No physical-device test was run.

**Run-local time to first verified action (one WSL host fixture run):** inspect **37.60 ms**, approved tests **43.97 ms**, retained artifact **4.39 ms**. Quick Action model/planning requests: **0**. These measurements use actual Linux processes/bytes with fixture Android Context/DAO transport; they exclude Android service/UI startup and are not real-model/device benchmarks. Complete event timings and raw JUnit output are retained in [host evidence](docs/ignition/host-verification.json) and [Ignition JUnit log](docs/ignition/QuickActionIgnitionTest.xml).

**Distribution verified:** the original keystore was located and its certificate SHA-256 is `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`, exactly matching prior NYX releases. The final APK is `40680ebfeac10953a41e8142d5a2e5138fcdc3d423ad6cd45fef81235b44200f` and the instrumentation APK is `eccbc53bb3839b9f5f2e172421023bce6aeac96b67836ec3e66bfeaa8e85319b`. Hosted CI passed on exact source commit `538b73adb528bd0ddfafbfa17225b7d76ec01d3f` at https://github.com/ShamelesAbyss/Nyx/actions/runs/43631095524. The v0.9.1 release uses this signed APK and the source archive built from the same commit. No AAB or Play publication was produced.

Preliminary failures remain under ignored `verification-output/`: nullable selector compile error corrected; the ZIP corruption fixture now locates payload using local-header lengths instead of a timezone/JDK-dependent fixed offset; the gateway timeout fixture accepts either the existing coroutine deadline or same-duration socket timeout while preserving failure and elapsed bounds. A later combined-development process-evidence rejection occurred alongside WSL backward-clock events; its ownership/time gates were not relaxed. The final clean complete run passed unchanged process-validation gates. Original logs, XML, compiler/toolchain and artifact reports remain local; the summary and raw Ignition XML are committed. No signing material is included.

The before-task-creation recovery edge is covered: the service durably admits a single-use action and both the UI and engine reject ordinary resume from a Quick Action snapshot, even without a task record. No automatic replay or accidental model connection is needed to recover; select a new action explicitly.

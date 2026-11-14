# Bedrock combined development → Workbench — final disposition

Documentation synthesis, 2026-11-14, for `/home/ubuntu/nyx-bedrock`. Original findings/severities and baseline sources remain in `/home/ubuntu/bedrock-audit-combined.md` at `d35d4d679711614b3d07b8e8b8de16140ae6b71a`. This report replaces the stale in-progress/missing-test handoff with retained execution facts. It is not Android acceptance, release approval or a new implementation by its documentation author.

## Actual host acceptance

`/tmp/nyx-bedrock-combined.HboCqE5p/results.log`: **OK (11 tests)**, JUnit 4.13.2, **18.745 seconds**. Current production non-UI engine, runner, development, terminal and Workbench sources were compiled for this host overlay; `compile.log`, `source-inputs.sha256`, `classpath.txt` and `provenance.txt` retain exact inputs. Read-only surrounding current-app API classes and a compile-only Android jar are dependencies, not a clean whole-app build.

The eleven methods are four in `CombinedDevelopmentBedrockTest.kt` and seven in `CombinedAgentValidationBedrockTest.kt`. The actual `ObjectiveExecutionEngine`, `DevelopmentOrchestrator`, `TerminalAgentTools`, production snapshot, `WorkbenchStore`, `ContextStore`, registration/checkpoint/witness and completion policies execute. Provider replies, Android Context, file-backed fake DAOs and the transaction transport are fixtures. **No real Android/Room/Binder/Termux/PRoot/Ollama or UI execution is implied by the Debian namespace label.**

### Real compiler → behavior → retained bytes → completion

The engine fixture starts with `fun sum(a: Int, b: Int): Int = BROKEN`. Actual fixed BUILD and TEST both return exit 1 with real Kotlin unresolved-reference diagnostics. A deterministic provider supplies the source patch; the existing development flow applies it. Actual fixed Kotlin BUILD then returns 0; TEST compiles and runs Java with **three behavioral assertions**, emits `BEDROCK_BEHAVIORAL_ASSERTIONS=3`, and writes **31 bytes**:

```json
{"passed":true,"assertions":3}
```

This combined payload is **distinct from the terminal-only 43-byte/two-assertion fixture**. `acceptance.json` records the actual commands, cwd/script digests, separate UUID operations, initial failures, repaired successful final BUILD→TEST, source hash, selected snapshot identity, persisted terminal provenance, registered/reopened bytes and `engineCompleted:true`.

Generated and retained SHA-256 both equal:

`3cd210ba339a2dfbabf5d457876196474b1c2b57ba43fe6dab675aa58ba7f22d`

Final BUILD operation: `87ba9007-074a-463a-a519-03ed20eae3d4`; final TEST: `576a5e2f-f81d-4203-9e8b-7c041758fbfb`; snapshot: `a4d41859-abb3-4c99-bc6c-3bd518fd937f`. Objective `combined-bedrock`, task `development-one`, selection `result`, original `build/result.json` → `result.json`. Production event order is observed→registered→verified, the task's ContextStore contains provenance/checkpoint, and completion requires current genuine bytes plus a registration-kind witness from a completed task. Omitting the completed task or substituting a generic workbench_write witness fails the completion gate. Retained fixture files live in `retained-acceptance/` within the evidence directory.

### Planning and runner integration

The actual engine sends initial steps then dedicated next/done schemas. A controlled interruption after continuation-task persistence yields needs_attention. Reopening the file-backed fake database preserves both unique task IDs/dependency and the persisted continuation ordinal. Explicit reviewed resume completes the existing task, reserves the next ordinal and saves DONE; a further reopen makes no provider request. This is logical interruption/reopen evidence, **not OS process death or real Room transactions**.

Full-profile tests seed trusted exact-request capability/throughput prerequisites, accept and persist a bounded three-node DAG/profile, reject 13 tasks and an unavailable terminal worker before persistence, and refuse mismatched request evidence. Execution is stopped before Full tasks run: no full-DAG execution, cold Full discovery or real-model throughput claim. AgentRunner tests reject malformed/unauthorized/prose actions without support/mutation, record a locally validated text action under actual outgoing options, and enforce a no-activity deadline.

## All six numbered findings and retained store risk

Paths below use `app/src/main/java/com/nyx/ai/`; test references use the corresponding unit-test package.

| Original ID / severity | Disposition and repaired production surface | Evidence / remaining boundary |
|---|---|---|
| COM-01 HIGH | **FIXED production host composition; Android/UI UNVERIFIED.** `workbench/CombinedDevelopment.kt`, `NyxViewModel.kt`, `service/ObjectiveExecutionService.kt` bind a default-off objective-specific combined grant and keep the existing engine as sole orchestrator. | Actual engine compiler→behavior→store→witness→completion chain passes. Configuration regression keeps legacy artifact-only Workbench terminal-disabled. UI taps/services/Binder not executed. |
| COM-02 HIGH | **FIXED exercised selected-byte channel.** `terminal/TerminalAgentTools.snapshotOutput`, `TerminalOutputSnapshot.kt`, `workbench/CombinedDevelopmentRegistration`, `WorkbenchStore.registerVerifiedSnapshot` transport only exact approved bounded bytes, not arbitrary guest File paths. | Generated/snapshot/retained/reopened digests agree; missing grant/task, unselected/private/traversal/link/oversize/forged identity cases refuse. Terminal-only regressions cover UTF-8/NUL/transport and fixed-JAR overwrite guards. Same-UID races remain; no source-path relaxation or JAR/APK/PDF/combined ZIP output. |
| COM-03 MEDIUM | **FIXED scoped fixed-only planning/development authority.** `CompactPlanningPolicy`, `TaskCapabilityScope`, engine/service composition expose approved source/fixed build/test without terminal_run, terminal worker, dependency installation or inherited network/gateway/publication authority. | E-CAP fixed-surface helper and E-COM actual development chain without terminal_run, plus unavailable-worker-before-persistence regression. Trusted project code still has existing guest/Termux authority and can copy secrets into outputs. |
| COM-04 HIGH | **FIXED production host process-evidence selection.** `CombinedDevelopment.kt:76–92`, `autonomy/CombinedExecutionVerification.kt`, engine `ObjectiveExecutionEngine.kt:749–762` require unique same-task/objective/scope infrastructure-complete ordered final BUILD→TEST. | Failed/null/124/137/unknown/incomplete/foreign/out-of-order/reused observations cannot register/checkpoint. Generic API success and sibling observations are not process proof. No guarantee descendants have stopped. |
| COM-05 MEDIUM | **FIXED mandatory-check truncation.** `autonomy/CombinedExecutionVerification.kt` checks model declarations and bounded mandatory batches separately. | Eight passing declarations plus failing mandatory digest produce nine results, invoke the digest callback and fail. A process/API result cannot replace the mandatory artifact check. |
| COM-06 HIGH | **MITIGATED immutable configuration/caller repair; real UI/runtime UNVERIFIED.** `WorkbenchExecutionConfiguration.kt` selects monotonic latest config and retains original combined scope; current ViewModel readers use it and Workbench displays original project/output. | Host roundtrip/latest-reader/global opt-out/foreign rebind/legacy/malformed-approval regressions pass. Actual project inaccessibility, service resume, UI selection and Binder execution remain device gates. |
| COM-STORE MEDIUM (original unnumbered risk) | **FIXED scoped foreign-store check.** `CombinedDevelopment.kt:98–99` compares store/task objective before mutation. | Foreign store refused; failing observed checkpoint retains no artifact; failing registered checkpoint may leave bytes but yields no successful witness/completion. Real database/process fault durability UNVERIFIED. |

## Remaining report and platform limits

`WorkbenchCompletionPolicy.missingCitationLinks` currently links exact selected URL tokens only in required MD/TXT/HTML reports. JSON/CSV/ZIP-only citation semantics remain unresolved; if no supported report exists the helper returns no linkage error. URL linkage plus checksum is not factual/semantic support. Parent strengthened two original citation test expectations to include both required URLs; final whole-suite rerun is pending, so no new passing citation campaign is claimed by these eleven methods.

Current source now uses latest configuration readers, shows original scope/recovery diagnostics, retains export selection through failure and reports local snapshot/stream completion rather than destination durability. These source/UI improvements are not device execution. Generic SAF destinations may be partial after interruption; Android readback/durability, URI grants, migration/upgrade, service lifetime, physical 16KiB JNI and real providers remain **UNVERIFIED**.

Reproduce the finite host harness only into a new owned evidence directory using `app/src/test/java/com/nyx/ai/workbench/bedrock_combined_host_verify.sh` and its documented output contract. Parent owns the pending clean whole-app test/app/native/instrumentation build and final candidate signer/ABI/alignment/source/checksum gates. No runtime/test mutation, Gradle, Git/remote/signing/publication/AAB, vendor/model/user-data/credential changes or protected-baseline writes were made by this documentation author. The root security audit and device acceptance procedure retain remaining 1.0 blockers.

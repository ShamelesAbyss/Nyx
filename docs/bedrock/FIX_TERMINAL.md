# Bedrock terminal repair disposition

Completed this continuation on 2026-11-14 in `/home/ubuntu/nyx-bedrock`. This continues the existing repair rather than replacing its architecture. The terminal integration contract remains `/home/ubuntu/bedrock-terminal-contract.md`; original findings are in `/home/ubuntu/bedrock-audit-terminal.md`.

**Result: the isolated current-source terminal suite passed `OK (99 tests)`**, with real Kotlin compilation, a deliberately failing behavioral baseline, a checkpointed source repair, successful fixed build and behavioral test, and an exact approved output snapshot. This is Linux JVM/process evidence, **not Android/Termux/PRoot device acceptance and not an OS sandbox claim**.

## Ownership and restrictions

This continuation modified only terminal production files and terminal test files, plus this report and isolated evidence under `/home/ubuntu/bedrock-terminal-tests`. It did not edit `TerminalBridge.kt`, `TerminalOperationRegistry.kt`, `TerminalDispatchBinding.kt`, `TerminalExecutionScope.kt`, or lifecycle-owned context/registry/bridge behavior. Existing owner changes in those files were used as read-only compilation dependencies. No Gradle command, commit, push, tag, AAB generation, publication, signing operation, user-data access, vendor modification, or write to `/home/ubuntu/nyx` or `/home/ubuntu/Nyx-publish` was performed.

The parent had already compiled the production tree. Verification here used its current classes at:

`/home/ubuntu/nyx-bedrock/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`

Those classes were read-only inputs. Four current terminal source files were compiled into an independent overlay outside the app build directory. The parent should recompile these last source changes after the repair barrier; this report does not claim a new whole-app build.

## Exact changes in this continuation

All paths below are relative to `/home/ubuntu/nyx-bedrock`.

| File | Change |
|---|---|
| `app/src/main/java/com/nyx/ai/terminal/TerminalAgentTools.kt` | Finished `outputGuard`: before Kotlin overwrites an existing `build/nyx-main.jar` or `build/nyx-test.jar`, require a non-symlink regular file, exact canonical target, and `stat -c %h` equal to 1. Keep build-directory canonical/non-symlink checks and exit 73 on unsafe outputs. Missing output remains permitted. Updated comments to describe validated catalog argv rather than arbitrary model shell. |
| `app/src/main/java/com/nyx/ai/terminal/TerminalCommandIdentity.kt` | Fixed a real runtime defect found by execution: internal names such as `file_sha256` were rejected by the previous letters-only operation-kind grammar. New bounded grammar is `[a-z][a-z0-9_]{0,63}`. This changes internal audit encoding, not executable/operand admission. Invalid starts, uppercase, separators, control characters and overlength operation names still fail. |
| `app/src/main/java/com/nyx/ai/terminal/TerminalOutputSnapshot.kt` | Fixed another executed regression: selected-output names now apply `WorkspaceSourcePaths.isSafeRelative` to the portion below build/dist/target. This rejects credential/private ancestors such as `build/credentials/result.json` without admitting generated outputs through source-reading tools. |
| `app/src/main/java/com/nyx/ai/terminal/WorkspaceProvisioner.kt` | Corrected two malformed `if` closing tokens (`}` to `fi`) in the existing creation script. Preserved canonical ancestor/parent/leaf checks, atomic leaf creation, and existing refusal behavior. Added a successful canonical creation control to prevent a rejection test from passing merely because the shell script was syntactically invalid. |
| `app/src/test/java/com/nyx/ai/terminal/BedrockTerminalSecurityTest.kt` | Standalone workspace validator probe now uses a direct `ControlledShell.request`/ProcessBuilder, not the dispatch-only executor requiring a coroutine identity scope. Added output hardlink rejection, successful normal workspace creation, and digit-bearing audit-codec round-trip/malformed-kind regressions. |
| `app/src/test/java/com/nyx/ai/terminal/BedrockFixedDevelopmentTest.kt` | Added pre-overwrite regressions for both fixed JAR names: hardlink to an outside sentinel, symlink, dangling symlink, directory, ordinary existing single-link output, and redirected build directory. A caller-trusted marker-only compiler fixture proves unsafe cases never invoke the compiler; the separate real-compiler test remains the actual compilation proof. |
| `app/src/test/java/com/nyx/ai/terminal/TerminalAgentToolsSecurityTest.kt` | Legitimate host executions now declare `DEBIAN_PROOT` and use the controlled env-i request. Fake discovery includes `stat` and the existing required helpers. Exact serialized printf argv is asserted. Gate-denial/outside-gate tests use a locally valid literal command so they exercise the gate rather than being vacuous local mutation denials. |
| `app/src/test/java/com/nyx/ai/terminal/TerminalProjectDetectionTest.kt` | Legitimate Linux fixtures declare `DEBIAN_PROOT`, include `stat`, use controlled requests, and supply an exact caller-owned canonical Kotlin compiler override when available. Compiler availability is checked against that exact fixture path rather than ambient PATH. |
| `app/src/test/java/com/nyx/ai/terminal/TerminalTaskIsolationTest.kt` | Fixed discovery fixtures with Debian/stat; the unrelated workspace is also in the Debian namespace. Cross-environment writer exclusion remains unchanged. Overflow timeout is asserted denied with zero additional dispatch, then the valid terminal ceiling is exercised. |
| `app/src/test/java/com/nyx/ai/terminal/CommandPolicyTest.kt` | Manual `git status --short` is asserted to require confirmation under the existing stricter `NETWORK_MUTATING` classification. Actual command policy was not loosened. |
| `app/src/test/java/com/nyx/ai/terminal/ModelCommandPolicyTest.kt` | Model `mkdir -p build` is asserted denied/BLOCKED as unsupported, not eligible for a routine mutation grant. Actual model policy was not loosened. |
| `app/src/test/java/com/nyx/ai/terminal/bedrock_terminal_host_verify.sh` | Added finite, reproducible standalone Kotlin/JUnit verification. It never invokes Gradle; uses current compiled app classes as read-only inputs; writes overlay classes/logs/evidence outside app build; compiles all terminal test sources and runs the eight specified terminal classes by default. |

## Original finding dispositions

“Addressed” below describes the repaired application boundary and the named Linux evidence, not kernel confinement or Android success. Several source changes were already present when this continuation began; they were retained and exercised, not replaced.

| Original finding | Disposition and precise repaired surface | Verification / limits |
|---|---|---|
| **T-01: executable identity reduced to basename** | **ADDRESSED for model-facing terminal operations.** `StructuredTerminalCommand.kt`, `ModelCommandPolicy.kt`, `ModelShellSyntax.kt`, and `TerminalAgentTools.runModelCommand` deny arbitrary executable paths, leading assignments, wrappers and unknown executable forms, serialize admitted argv to environment-owned absolute tool paths, and revalidate even with a permissive gate. Manual shell remains a different explicit authority. | `arbitraryExecutablesProgramsGitOptionsAndPathsFailBeforeIpc`, `permissiveGateCannotWidenLocalAuthority`, `validatedReadersUseAbsoluteArgvAndKeepRelativeSubdirectoryMeaning`: attacker fixture executable never ran; denied cases had zero executor dispatch. |
| **T-02: programmable utility options/programs execute code** | **ADDRESSED at the model boundary.** Arbitrary awk/sed programs, interpreters/multicall wrappers, find execution forms, output and unsupported options are not in the fixed catalog. Literal grep and bounded explicit-file readers remain. | Same pre-IPC adversarial test plus `ModelCommandPolicyTest`: quoted awk system/sed e programs and indirect execution fail before dispatch. No destructive or network payload was executed. |
| **T-03: Git option parsing bypasses publishing/config restrictions** | **ADDRESSED for model shell admission; fixed inspection is separately controlled.** Model Git is denied outright. `FixedRepositoryInspection.kt` uses a fixed absolute Git binary, fixed worktree/git-dir, overrides executable fsmonitor/hooks/pager behavior, clears system/global config inputs, and rejects metadata symlinks/indirection. Manual Git now requires confirmation. | Option/value, alias, config, publishing/history variants denied with zero model dispatch. `fixedGitInspectionDoesNotExecuteConfiguredFsmonitorPagerOrAliases` used a temporary repository and marker-only configured helper, with no helper execution. It does not claim a clean Git-history result in an empty repository. |
| **T-04: operands escape approved cwd** | **ADDRESSED for supported operands.** `WorkspaceSourcePaths.kt`, `WorkspacePathGuard` in `StructuredTerminalCommand.kt`, and terminal file operations enforce workspace-relative source operands, canonical containment, no target symlinks and single-link regular files, including execution-time checks. Private/config/generated paths do not become general source exports. | Source/reader symlink, hardlink, traversal, private ancestor and absolute-path regressions; outside sentinel remained unchanged; explicit cwd subdirectory preserved meaning. Same-UID races remain a residual. |
| **T-05: source batch omits confinement/predictable temporary** | **ADDRESSED at the application batch boundary.** `SourceBatchReader.kt` checks canonical source/temp targets, source link count, before/after hashes, and uses umask-077 `mktemp` under the verified workspace. Strict UTF-8/non-NUL parsing and bounded expected-member checks apply. `TerminalAgentTools.indexSourcesLocked` rejects non-success IPC batch payloads and falls back only to guarded single reads. | Injected symlink between listing and batch; malformed UTF-8/NUL; truncation; failed-exit success-looking payload; unexpected/duplicate members; predictable-link sentinels. Both `SourceBatchReaderTest` and Bedrock batch regressions passed. |
| **T-06: login startup/ambient PATH executable authority** | **ADDRESSED for controlled operations, device verification outstanding.** `ControlledShell.kt`, `HostShell.kt`, and controlled requests sanitize with env-i **before bash startup**, fixed PATH, no profile/rc/login shell. Discovery restricts default origins and permits only exact caller-owned compiler overrides for explicit trusted development. Manual login-shell behavior remains intentionally separate. | `cleanControlledRequestSuppressesInheritedStartupPathAndFunctions` supplied marker-only BASH_ENV/ENV/PATH/exported-function hooks; none executed. Exact trusted compiler path worked in real acceptance. Android Binder/Termux/PRoot inheritance is unverified. |
| **T-07: autonomous command lacks durable exact identity** | **PARTIALLY ADDRESSED / owner integration verification remains.** Terminal provides bounded semantic identity/argv and script digest, task dispatch scope, and a controlled bridge extension in `TerminalCommandIdentity.kt`; payload-bearing source writes use digest-only identity rather than persisting base64 source contents. Current read-only lifecycle source has identity binding and journal codec hooks. This continuation fixed digit-bearing identity encoding. | Terminal suite captured exact reader argv and exercised Properties codec/corrupt fields. The test whose name mentions an interrupted journal covers the identity codec, not a real bridge/process interruption. Pre-IPC denials are still task-local observations, not newly durable terminal-journal records. Parent/lifecycle owner must report durable fail-closed pre-dispatch/interruption/reload evidence; this report does not claim it. |
| **T-08: canonical adoption/predictable workspace write probe** | **ADDRESSED in terminal workspace probe/create code; UI integration verification remains.** `WorkspaceManager.kt` validates reserved/private/system/environment rules, verifies canonical spelling before exclusive write probe, rejects duplicates/nonzero exit/canonical mismatch. `TerminalRepository.verifyWorkspace` supplies expected path/environment with controlled execution. `WorkspaceProvisioner.kt` checks existing canonical ancestors before parent creation and rejects redirection; this continuation repaired its shell syntax. | Probe predictable-link sentinel unchanged, aliases/forged duplicate fields/nonzero results refused, symlink-parent creation left outside tree empty, normal nested canonical creation succeeded. Caller must explicitly approve canonical scope; no implicit alias adoption is provided. |

### Other observations from the original audit

The **hardlink residual** is reduced for source reads/writes, selected snapshots and existing fixed Kotlin JAR output overwrite; Java compilation already uses a fresh private output directory. These are pre-open checks, not an atomic kernel-level guarantee. Hostile same-UID mutation between checks and opens remains possible. In-process workspace leases do not constrain other Termux-owner processes or trusted scripts.

The **low provider-boundary schema issue** is addressed by `TerminalAgentTools.execute` invoking `StructuredActions.validate`, plus exact bounded integer checks. `toolBoundaryRejectsCoercionUnknownFieldsAndIntegerOverflowWithoutIpc` passed with zero dispatch for wrong types, explicit null, unknown fields and overflow.

The **low deletion-result issue** is covered by the current `WorkspaceLifecycle.parseDelete` requiring successful exit and unique expected flags. `failedDeletionFlagsCannotConfirmCompletion` passed for nonzero/null/124 and duplicate success-looking fields. No real workspace deletion was run; lifecycle/UI integration belongs to its owner.

## Actual verification evidence

Reproduce from `/home/ubuntu/nyx-bedrock`:

```bash
bash app/src/test/java/com/nyx/ai/terminal/bedrock_terminal_host_verify.sh
```

The final run finished at 2026-11-14T09:36:55Z. Compilation produced no diagnostics, JUnit reported **99 tests, zero failures**, and test runtime was **17.429 seconds**. The eight executed classes were `CommandPolicyTest`, `ModelCommandPolicyTest`, `SourceBatchReaderTest`, `TerminalAgentToolsSecurityTest`, `TerminalProjectDetectionTest`, `TerminalTaskIsolationTest`, `BedrockTerminalSecurityTest`, and `BedrockFixedDevelopmentTest`. Other terminal test sources were compiled but were not part of this execution claim.

Evidence files:

- [Final JUnit results](/home/ubuntu/bedrock-terminal-tests/results.log)
- [Isolated compiler diagnostics](/home/ubuntu/bedrock-terminal-tests/compile.log)
- [Actual fixed Kotlin build/test/snapshot evidence](/home/ubuntu/bedrock-terminal-tests/real-kotlin-evidence.json)
- [Exact dependency classpath](/home/ubuntu/bedrock-terminal-tests/classpath.txt)
- [Versions, changed-file SHA-256 values and final summary](/home/ubuntu/bedrock-terminal-tests/verification-manifest.txt)

The runner used Kotlin JVM compiler **2.2.10**, OpenJDK **21.0.13**, JUnit **4.13.2**, JSON **20240303**, and coroutine dependency **1.9.0** from the existing cache. Android API jar was compile-only/supporting type resolution; no Android app/device process was run.

### Real fixed-development acceptance, not model claims

`BedrockFixedDevelopmentTest.realKotlinCompilationBehavioralRepairAndApprovedSnapshotUseProductionTools` created test-owned Kotlin sources with `answer() == 41` and behavioral assertions expecting 42. The production `TerminalAgentTools` discovered exact caller-trusted compiler/runtime paths. Actual fixed test execution first returned **PROCESS_EXIT / exit 1**, then `applyPatch` changed the source using the exact original SHA-256 and checkpointed atomic replacement. Actual fixed build returned **SUCCEEDED / exit 0**; actual fixed test returned **SUCCEEDED / exit 0**, executing two assertions and writing:

```json
{"passed":true,"assertions":2,"answer":42}
```

The selected `build/result.json` snapshot was bound to objective `fixed-objective`, task `fixed-task`, Debian namespace/guest, canonical workspace and selection `test-result`. Its exact **43 bytes** (including trailing newline) matched the actual output file, with SHA-256:

`1adb25ef922e29ecf8b665c6887c1f4af336145af561fe5270736d0438bdb91c`

The three distinct project operation IDs were:

- baseline TEST: `8513638b-fa9c-4b41-b536-489db4e7eb91`, exit 1;
- repaired BUILD: `3735e348-d8de-4006-82b2-fe8654e3f91c`, exit 0;
- repaired TEST: `0911757c-84ea-42c4-b8fe-725fef7cd5c6`, exit 0.

The fixture made 19 terminal calls, drained separately typed actual project observations, verified snapshot byte/digest agreement, and verified ordinary source reads still deny `build/result.json`. It did not implicitly enable `terminal_run`, use an LLM, contact Ollama/network, register a Workbench artifact, export/publish anything, or exercise Android. The temporary project was cleaned up; the evidence retains command/result/provenance and behavioral JSON, not its compiled JARs.

### Failed runs were investigated, not hidden

The first executed suite compiled but failed **23/99** tests, chiefly because `file_sha256` was rejected by the identity grammar. The second failed **2/99**, exposing private selected-output ancestors and an outdated cross-environment concurrency fixture. Their logs are preserved in `/home/ubuntu/bedrock-terminal-tests/initial-failure` and `/home/ubuntu/bedrock-terminal-tests/second-failure`. Those failures led to the focused fixes above; no authorization policy was relaxed to get the final pass. The output-guard adversarial fixture is deliberately marker-only and is not counted as real compiler proof.

## Remaining blockers / acceptance boundary

There is **no remaining failure in the executed terminal suite**. Parent integration still needs the final whole-tree compile/test barrier and its combined Workbench acceptance, retaining actual typed BUILD and TEST observations before draining task tools, persisting/recovering the original output approval instead of rebinding current UI scope, and registering the exact snapshot bytes through normal Workbench validation. Durable interruption/journal/device acceptance is lifecycle-owned and is not substituted by this test seam.

The user/caller explicitly trusts fixed project code and toolchain overrides. Such project scripts retain Termux/guest filesystem/network authority and may read private content, create hardlinks, mutate unrelated files, or generate secret-containing output. Exact paths are not cryptographic attestation of owner-controlled binaries; SHA-256 proves byte identity, not absence of secrets or factual truth. Canonical/link-count checks narrow mistakes and tested attacks but do not provide an OS sandbox or eliminate same-UID TOCTOU races. Android/Termux/PRoot environment and Binder acceptance remain **UNVERIFIED**. Timeout/cancellation/unknown status remains uncertain; no automatic replay or automatic proof of termination was added.

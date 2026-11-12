# NYX 0.6.1 — Verified Developer and Environment Reliability

This release fixes the environment failures reported from the Pixel Fold and makes development objectives depend on evidence instead of a passing baseline. It is a debug-signed development build. No physical Android device was available in this environment, so every claim below is either a JVM test result or a code-level guarantee; device behavior is listed separately as unverified.

## 1. Termux host isolation and PRoot truth

**Observed failure.** NYX could report "Debian missing" while Debian was installed, or appear to run commands inside Debian while the app believed it was on the Termux host. Both are explained by the Termux startup profile: a `.bashrc`/`.bash_profile`/`.profile` that ends in `proot-distro login debian` runs before the command, so an ordinary `bash -lc` probe executes inside the guest. A command that runs inside a PRoot session also cannot start another `proot-distro` session; it fails with "attempted to run proot-distro in a proot session".

**Fix.**

- Discovery no longer uses the profile-driven shell. It invokes Termux's own bash directly:
  `$PREFIX/bin/bash --noprofile --norc -c 'unset BASH_ENV ENV; <fixed probe>'`.
  `$PREFIX/` is expanded by the documented Termux RUN_COMMAND API; `--noprofile --norc` prevents user startup files from redirecting the process into a guest.
- A host identity probe checks `getprop` and reports `androidHost=false` when the command still lands in a guest. That state is shown explicitly (`nestedProot`) with the remedy: remove the automatic `proot-distro login` from the Termux startup profile.
- The installed-distribution list is read with `proot-distro list --quiet` and parsed strictly. A non-zero exit, a missing exit code, or a nested-PRoot error is a **failure**, never an apparently empty "none installed" list. The sectioned and older annotated formats are still accepted.
- A distribution is reported as available only after a real command runs inside it: `/bin/sh` prints `NYX_GUEST_OK`, HOME, working directory, OS, uid, and resolved tool paths. A name in a list is not verification.
- Execution requests now carry an explicit `distroAlias`, validated as `[A-Za-z0-9][A-Za-z0-9._-]*` before interpolation. Debian is no longer hardcoded in the command descriptor.
- The foreground service refuses to start a Debian objective unless the selected guest passed verification; it surfaces the discovery `listError` instead of a generic "not installed".

**Limit.** If the owner's Termux configuration forces every command into a guest through a mechanism that even `--noprofile --norc` cannot bypass (for example a custom RUN_COMMAND wrapper), NYX cannot silently escape it. It reports the nested state instead of pretending discovery succeeded.

## 2. Capability profile, workspace validation, and preflight

**Capability profile.** A read-only probe records, per tool: `VERIFIED`, `MISSING`, `INCOMPATIBLE`, `PERMISSION_DENIED`, or `NOT_CHECKED`, plus its absolute path and a bounded version line. It also records free storage and whether the selected workspace is writable. Profiles are timestamped and persisted per environment; the UI does not present an old profile as current proof.

**Workspace validation.** A path is checked in two stages: deterministic syntax rules for the selected environment, then a real probe for existence, directory type, and write access. Guest paths belong to the PRoot filesystem, so Android paths such as `/storage/emulated/0` and `/sdcard` are rejected for Debian with an actionable message. The verified path is what gets saved and snapshotted into the objective.

**Preflight.** Before a development objective starts, NYX evaluates provider configuration, model selection, environment verification, workspace authorization and accessibility, required tools, and available memory. Each blocker has a stable code, a message, and a next action. The UI shows the blockers; `createObjective` refuses to start when preflight is not ready.

**Limits.** The capability profile is a point-in-time observation, not a guarantee that a tool will still work later. The memory number is advisory and manufacturer-dependent. Preflight does not install missing tools and does not elevate privileges.

## 3. Verified developer v2

**Goal classification.** Each goal is classified as `DIAGNOSE`, `REPAIR_BUILD`, `REPAIR_TEST`, `MODIFY_FEATURE`, `CREATE_FEATURE`, or `VERIFY_ARTIFACT`, with the matched reasons retained. Classification is deterministic and unit-tested; it is not a model judgment.

**Acceptance criteria.** Criteria are derived from the goal kind: whether a source change is required, whether new or changed test evidence is required, which commands must pass, and the iteration/call/file/byte limits. The report carries the goal kind, the criteria, and a typed outcome.

**Honest outcomes.** `passed` is true only for `REPAIR_VERIFIED` or `FEATURE_IMPLEMENTED_AND_VERIFIED`. A healthy baseline for a repair goal yields `BASELINE_HEALTHY` and is **not** reported as a repair. A feature goal whose post-change build and tests pass but which added no test evidence yields `GOAL_NOT_VERIFIED`. Missing toolchains yield `ENVIRONMENT_UNAVAILABLE`; unsafe paths and unparseable patches never reach the environment.

**Evidence, not prose.** Build and test exit codes come from the environment. The orchestrator records original stdout/stderr separately from model responses and keeps both in the report. Model tool calls and claims of success are rejected as evidence.

**Context quality.** Files are ranked by goal relevance, failing-path hints, diagnostics, recency of modification, and size before the bounded context is built. Source indexing now reads up to 12 files per IPC round trip instead of one command per file, with the original per-file path as a bounded fallback.

**Fixtures.** Four required scenarios plus adversarial cases run against the real orchestrator with deterministic providers and environments: broken project repaired, feature with test evidence verified, feature without test evidence not verified, healthy baseline for a feature request not successful, missing toolchain blocked before inference, and unsafe path rejected before any edit.

**Limits.** "Test evidence" means a test file was added or changed and the suite passed. NYX does not judge whether that test is meaningful, and it does not generate tests semantically. A model can still write a weak test; the outcome is evidence-backed but not a proof of product correctness. No real-model autonomous coding run on a phone has been performed.

## 4. Regression hardening

- New unit tests cover the distro-list formats, nested-PRoot classification, failure-versus-empty semantics, guest probe parsing, host identity, isolated request construction, alias validation, capability parsing, workspace rules, preflight blockers, goal classification, acceptance evaluation, relevance ranking, and batch reading.
- Two existing tests were tightened rather than weakened: a healthy baseline now asserts `BASELINE_HEALTHY` with `passed=false`, and a listed Debian alias now asserts `debianAvailable=false` until a guest probe verifies it.
- The complete JVM suite is run by the release build; counts are recorded in `TEST_RESULTS.md`.

## 5. Device verification still required

1. On the Pixel Fold, with the owner's Termux profile active, run **Detect environment** and confirm the host is reported as isolated (not nested).
2. Install/verify a PRoot guest, then confirm **Verified guests** lists it and the capability table shows real tools and versions.
3. Save `/root/<project>` as the guest workspace; confirm an Android path is rejected with the documented message.
4. Run preflight for a development objective and confirm each blocker disappears as the provider, guest, workspace, and tools are fixed.
5. Run a repair objective on a disposable project and inspect the execution trace for original command output and exit codes.
6. Repeat with the app backgrounded to confirm the foreground service and notification behave as documented.

These steps cannot be executed from this build environment, and this document does not claim they passed.

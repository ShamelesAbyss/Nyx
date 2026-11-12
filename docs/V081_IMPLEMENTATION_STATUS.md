# NYX v0.8.1 — Implementation Status

Status: implemented and JVM/build verified. Published as `v0.8.1` with the APK, instrumentation APK, source archive, and SHA-256 manifest. Physical-device execution remains pending.

Package metadata: `com.nyx.ai`, versionCode **14**, versionName **0.8.1**, minSdk **26**, compile/target **37**, `arm64-v8a`, debug-signed with the established certificate.

## Scope

### Run-scoped Live Worker Console

- `WorkerActivity` now carries `runId`, observed `status`, and an optional measured `durationMillis`; old JSONL records decode with nulls and remain visible as legacy runs.
- `ObjectiveExecutionService` assigns one run identity per launch and passes it to engine/service events.
- `WorkerConsoleModel` provides pure run grouping, current-run selection, objective scoping, operation grouping, observed status, and elapsed-time formatting.
- The dialog defaults to the selected/current objective and current run, exposes earlier runs through a history selector, provides operation cards and a full-height detail view, and retains a raw-event diagnostic mode.
- New events auto-scroll only when the user is already near the bottom. Switching runs starts at that run's newest item.
- **Clear history** requires confirmation, preserves the selected and active runs, and only removes bounded console diagnostics. Room objective/task records and workspace files are untouched.

### Planning recovery

- `PlanningRecovery` defines a hard 120-second per-attempt deadline, a 10-second progress interval, two total attempts, and one repair after a completed validation failure.
- The engine emits `planning_attempt`, `planning_progress`, `planning_validation_failed`, `planning_repair`, `plan_created`, and `planning_failed` events with run identity and observed status.
- Failure classes are derived from exception type or message: timeout, interruption, empty response, invalid JSON, schema rejection, context exhaustion, provider error.
- A validation failure receives one structured-output repair prompt containing the validator reason. A transport failure does not.
- Failed planning lands in `needs_attention` with a sanitized 600-character diagnostic and a status event. It does not silently restart. Existing tasks, dependency evidence, and checkpoints are preserved; resume still requires explicit review.

### Model shell syntax hardening

`ModelShellSyntax` runs before `CommandPolicy` for model-directed `terminal_run` commands. It rejects unquoted output/input redirection, pipes, compound commands, newlines, command substitution, variable expansion, and indirect execution. Quoted metacharacters remain ordinary arguments. Fixed build/test commands and the separately approved dependency installer are not routed through this model-directed boundary.

## Test coverage

| Area | Evidence |
| :--- | :--- |
| Run separation and objective scoping | `WorkerConsoleModelTest`: distinct objectives/runs, legacy null run, current run ordering |
| Operation grouping and duration | `WorkerConsoleModelTest`: task grouping, terminal status, measured timestamps, no invented terminal duration |
| Terminal escape safety | `WorkerConsoleModelTest`: ANSI CSI and OSC removal |
| Planning classification | `PlanningRecoveryTest`: JSON vs schema, timeout, interruption, context evidence, repair prompt bound |
| Shell syntax | `ModelCommandPolicyTest`: redirection, chaining, substitution, indirect execution, quoted metacharacters |
| Regression suite | Full JVM suite, **695 tests**; see `TEST_RESULTS.md` |

## Physical-device checklist

These are not verified claims until executed on a real Android device:

- [ ] Open the console from Chat and Autonomy; confirm it fills the screen and respects navigation/cutout insets.
- [ ] Start a new objective and confirm the default view contains only its current run.
- [ ] Open **History** and select a prior run/objective; confirm the event set and detail view reset correctly.
- [ ] Scroll up during a running operation; confirm new events do not force the viewport to the bottom.
- [ ] Open an operation card; confirm chronological detail, status, timestamps, command input/output, and measured duration.
- [ ] Switch to **Raw events**; confirm ANSI sequences do not appear and copy/share output is bounded and redacted.
- [ ] Clear history with a confirmation; confirm the current/active run remains and objective records are still present.
- [ ] Force a planning timeout or invalid plan with a test provider; confirm progress events, one repair, and a `needs_attention` diagnostic.
- [ ] Resume a `needs_attention` objective after review; confirm no automatic replay occurred.
- [ ] Exercise model shell syntax against Termux and Debian/PRoot; confirm redirection/chaining is denied while quoted metacharacters and fixed build/test commands still work.
- [ ] Re-run the pending v0.8.0 dependency and workspace checks.

## Known limitations

- No physical Android device was available for this build.
- The client-side deadline cannot guarantee that a provider stops its own generation promptly.
- The console is a bounded diagnostic store (300 events / 1.5 MB), not an unlimited audit archive.
- Console history is process-persisted in JSONL; objective and task truth remains in Room.
- The APK remains a debug-signed, ARM64-only development build. No AAB or Play publication was performed.

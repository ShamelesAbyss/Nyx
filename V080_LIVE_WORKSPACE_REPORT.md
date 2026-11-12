# NYX v0.8.0 — Live Worker Console & Workspace Lifecycle

Status: published implementation build. Version `0.8.0` (`versionCode 13`), package `com.nyx.ai`. No AAB or Google Play publication was performed.

## Live Worker Console

The existing persisted `WorkerActivityBus` was extended rather than duplicated:

- `WorkerActivity` now carries bounded `toolInput` and `toolOutput` in addition to the existing timestamped title, detail, objective, task, kind, and tool name.
- The objective engine forwards its existing tool input/output into the bus; the execution service also feeds dependency, environment, file, terminal, recovery, and failure events into the same timeline.
- The console is reachable from the existing Chat preview and from a new visible Live Activity card in Autonomy. It shows the running/selected objective, execution phase, active task, status, and event count.
- The full-height dialog uses a `LazyColumn` and expandable event rows. Input/output blocks are selectable, multiline, and bounded.
- Pause, Resume, and Cancel call the existing objective service methods.
- Copy and share use `WorkerActivityExport`: a bounded (60,000-character) plain-text log with credential-shaped values redacted. No model reasoning is synthesized or exported.
- History remains in the existing on-device JSONL store, capped at 300 events and bounded file size, so navigation, backgrounding, and process recreation do not require a second logging system.

## Workspace lifecycle

The existing `WorkspaceProvisioner`, `WorkspaceValidator`, terminal repository, and autonomy policy remain the foundation. Additions:

- `WorkspaceRegistry` persists verified NYX-managed records (environment, guest alias, managed parent, exact path, verification time).
- On startup, the saved workspace is re-probed. A matching canonical path is reused; an unavailable path is reported and its routine grants are removed. No numbered replacement is created automatically.
- `WorkspaceLifecycle.startupDecision` is the tested decision boundary for reuse.
- Selecting or creating a workspace updates the saved selection, registry, and routine policy scope together. Terminal-enabled objectives refuse to start when the selected path is not verified.
- `AutonomyModelCommandGate` now rejects mutating model commands when a concrete routine grant exists for a different workspace scope. It does not invent authority when no grant exists; the normal policy decision still applies.
- The Terminal screen has a Manage Workspaces panel listing verified direct children of the managed parent, with Use, Delete, and Delete all test workspaces actions.
- Deletion requires an exact path-list confirmation. The generated script refuses the parent root, nested paths, `..`, non-canonical paths, symlinks, and any path outside the managed root. It re-resolves both the parent and target with `realpath`, checks that the target is a direct child and not a symlink, then removes only that target and verifies it is gone.
- Active, paused, or running objectives block deletion. After deletion, the registry is updated, a deleted selected workspace is cleared, and routine grants are reconciled. Deleted workspaces are never recreated automatically.

## Verification

- Focused new JVM tests: 8 passing (6 workspace lifecycle, 2 live export/redaction).
- Full JVM suite: **679 tests, 0 failures, 0 errors, 0 skipped** across 64 classes.
- Clean build: `BUILD SUCCESSFUL in 1m 15s`; APK and instrumentation-test APK compiled.
- Debug APK SHA-256: `a67790fa2f13c66210e7635ce013af92ac77e61f4d6417877a6e547779b080be`.
- Existing signer: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`; 16 KB alignment passed.
- No physical Android device was available. Live rendering, Android service controls, Termux execution, real workspace deletion, and model behavior remain device checks. The console and deletion flows are covered by pure tests and guarded command generation, not device execution.
- No AAB or Google Play work was performed; the APK remains debug-signed.

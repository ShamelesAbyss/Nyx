# NYX v0.8.1 — Project Clear Signal

**A run-scoped execution console and bounded planning recovery for the local-first Android AI workstation.**

NYX v0.8.1 is a focused reliability and observability release. It does not change the application's identity or claim new autonomy. It makes the work NYX already performs easier to inspect, separates one execution run from the next, and ensures a planner that stalls or returns invalid structured output fails visibly and recoverably instead of appearing to run forever.

## What changed

### Run-scoped Live Worker Console

The Live Worker Console now defaults to the current objective and its current execution run. Earlier runs are available through an explicit history selector, identified by objective and run/session identity. The console no longer merges every event in the bounded on-device store into one live view.

- Current run by default; latest objective when no objective is active.
- History selector for earlier objectives and runs, including legacy events written before run identities existed.
- Run selection preserves the event set but resets the operation-detail view.
- New activity follows the bottom only when the user is already near the bottom, so inspecting earlier operations does not yank the viewport away.
- Full-height dialog that respects navigation-bar and display-cutout insets.

### Operations, measured duration, and raw diagnostics

Related events are grouped into operation cards: task execution, planning, and diagnostic service activity. Each card shows the observed status, event count, and elapsed time calculated from real event timestamps. A running operation uses wall-clock elapsed time; it is not presented as completed and no duration is invented for an operation with no usable timestamp.

Tap an operation to open a full-height chronological breakdown with timestamps, status, detail, tool input, and tool output. An optional **Raw events** mode exposes the complete timestamped event stream. Terminal ANSI and OSC control sequences are removed before display and export; credential-shaped values remain redacted.

**Clear history** requires confirmation, preserves the selected/current and active runs, and removes only stored console diagnostics. It never deletes objective records, task evidence, workspace files, or model data.

### Bounded planning and honest failure states

Planning now runs with a hard 120-second per-attempt deadline and emits visible progress events while it waits. A completed model response that fails strict JSON or plan-schema validation gets exactly one structured-output repair attempt containing the local validator's reason. Transport failures are not retried as if they were malformed output.

Failure categories are classified from observable evidence:

- model planning timeout;
- interruption or cancellation;
- empty response;
- invalid strict JSON;
- schema rejection;
- provider-reported context exhaustion;
- other provider failure.

A failed planning phase stops in `needs_attention` with a sanitized, actionable diagnostic. It does not silently restart or repeatedly replay the same planning call. Existing tasks, dependency evidence, and checkpoints remain intact; resuming still requires explicit human review.

### Hardened model-directed shell policy

The model-directed terminal boundary now rejects unquoted shell constructs that the existing command-risk classifier cannot safely attribute to one executable:

- output and input redirection, and process substitution;
- pipes, `&&`/`||` chaining, semicolons, and multiline scripts;
- `$()` and backtick command substitution;
- unquoted variable expansion;
- indirect execution such as `sh -c`, `eval`, `xargs`, `find -exec`, and interpreter `-c` forms.

Quoted metacharacters remain ordinary arguments. Fixed build/test workflows and the separately approved dependency installer are unchanged. This is a policy boundary, not an operating-system sandbox; the real command still runs in the approved Termux or Debian/PRoot environment.

## Verification

Build and test evidence for this release is recorded in [TEST_RESULTS.md](TEST_RESULTS.md).

- JVM tests: **695 passed**, **0 failures**, **0 errors**, **0 skipped**.
- Debug APK: `com.nyx.ai`, versionCode **14**, versionName **0.8.1**, minSdk **26**, compile/target **37**, `arm64-v8a`.
- Instrumentation APK compiled; no physical test device was available to execute it.
- APK signer: same debug certificate as the earlier public NYX releases, certificate SHA-256 `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`.
- 16 KB ZIP alignment verified.

| Artifact | SHA-256 |
| :--- | :--- |
| `NYX-v0.8.1.apk` | `7a05ac2dee6b7b5c3cda54769b659b88bc06ce4af8ed004c3cd8333801949ea6` |
| `NYX-v0.8.1-androidTest.apk` | `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` |

The attached `NYX-v0.8.1-SHA256SUMS.txt` is the checksum index for the APK, instrumentation APK, and source archive. The manifest's own SHA-256 is recorded in the GitHub release body so the index itself can be verified.

## Honest limitations

No physical Android device was available for this build. The following remain device checks rather than verified claims:

- console rendering, history selection, operation cards, details, and scroll behavior on a real phone;
- clear-history confirmation and preservation of an active run;
- real provider planning timeout, progress events, one-repair behavior, and `needs_attention` recovery;
- actual shell syntax behavior through Termux and Debian/PRoot;
- the previously documented v0.8.0 dependency-install path and workspace lifecycle;
- Termux `RUN_COMMAND`, foreground-service behavior, Keystore, notifications, process-death recovery, Room migration, and fold/unfold layout.

The planning deadline is a client-side bound; whether a provider promptly stops its own generation on cancellation depends on that provider. The console retains a bounded on-device event history, not an unlimited audit archive.

No AAB was generated, no Google Play publication was performed, and no signing key or credential was uploaded. The APK is an ARM64-only, debug-signed development build.

## Upgrade

Existing v0.7.5, v0.7.7, v0.7.9, and v0.8.0 installations can update in place when the signing certificate matches. The v0.8.1 APK uses the same established debug certificate. Back up anything important before experimenting; do not uninstall a data-bearing installation merely to test an upgrade.

## Assets

| Asset | Description |
| :--- | :--- |
| **[NYX-v0.8.1.apk](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.8.1/NYX-v0.8.1.apk)** | Application, ARM64, debug-signed |
| **[NYX-v0.8.1-androidTest.apk](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.8.1/NYX-v0.8.1-androidTest.apk)** | Compiled instrumentation tests for device verification |
| **[NYX-Source-v0.8.1.zip](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.8.1/NYX-Source-v0.8.1.zip)** | Complete source, tests, docs, licenses, and Gradle wrapper |
| **[NYX-v0.8.1-SHA256SUMS.txt](https://github.com/ShamelesAbyss/Nyx/releases/download/v0.8.1/NYX-v0.8.1-SHA256SUMS.txt)** | SHA-256 digests for every artifact |

## License

NYX is released under the Apache License 2.0. The bundled llama.cpp runtime retains its upstream license and attribution. Model weights are not distributed with NYX.

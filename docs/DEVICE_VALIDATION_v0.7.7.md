# NYX v0.7.7 — physical-device validation checklist

This build is **not** device-verified. Complete these checks on the owner's Android 17 device before treating v0.7.7 as a stable release.

## Prerequisites

- Install `NYX-v0.7.7.apk` over v0.7.5 without clearing app data. Confirm the package updates in place with the same debug certificate.
- Install/verify Termux and the Debian PRoot guest used in earlier testing.
- In Debian, confirm:
  - `command -v ollama` returns `/usr/local/bin/ollama`
  - `ollama --version` begins with a connection warning but reports a version
- Keep the Ollama server stopped for the first detection test.

## A. Debian-only Ollama acceptance

1. Open **Models**. Tap **Detect**.
2. Confirm the managed card reports the Debian guest (not the Termux host), version, `aarch64`, and `STOPPED`.
3. Confirm the probed-candidate list includes the Debian installation and does not label the host as installed.
4. Tap **Start**. Confirm:
   - the status moves through `STARTING` to `RUNNING`
   - the PID is shown and marked NYX-owned
   - the existing model inventory appears without a download
   - Chat reconnects and can answer a short prompt
5. Tap **Stop**. Confirm only the NYX-owned PID stops; the status becomes `STOPPED`.
6. Tap **Start**, then **Restart**. Confirm there is never a second `ollama serve` and no adopted server is killed.
7. While the server is running, kill the Termux session or background the app. Reopen NYX and confirm detection adopts/recognizes the existing server safely.

## B. Host/guest selection

1. With Ollama installed in both Termux and Debian, confirm the preferred environment is selected deterministically and the other candidate is visible.
2. Remove or rename one installation and tap **Detect**. Confirm the remaining compatible installation is selected.
3. Create a deliberately incompatible `ollama` executable in a guest. Confirm it is reported incompatible and is not started.
4. With neither installation present, confirm `NOT_INSTALLED` and no attempted kill/start.

## C. Full-Autonomy workspace creation

1. Select **Full** and approve the mode.
2. Detect the environment and select Debian.
3. Create `nyx-app`, then create `nyx-0.7.5-test`, then a third independent name under the same root.
4. Confirm all succeed without `full_elevated_grant_required`, each has its own directory, and no existing directory is reused.
5. Switch to **Manual**. Attempt another project. Confirm the approval card appears; tap Cancel and confirm nothing is created.
6. Attempt a name containing `..` or `/`. Confirm traversal is rejected.

## D. Objective continuity

1. Stop the Debian Ollama server.
2. Create a small development objective against a trusted workspace.
3. Confirm NYX starts the managed server, reconnects models, and proceeds to planning without a terminal command.
4. Pause, cancel, and resume. Confirm the service checkpoint and ownership behavior.
5. Kill the app mid-run. Reopen and confirm recovery does not blindly repeat an uncertain side effect.

## E. Existing integrations

- Run an Arsenal skill and confirm a real declared tool executes.
- Add a test MCP server and a GitHub gateway; confirm health, allowlist, timeout, and redacted logs.
- Retrieve a symbol-index result during a Kotlin/Java objective.
- Confirm project memory is reused in a later task.
- Open the Live Worker Console and confirm real terminal/tool/verification events, not fabricated reasoning.
- Toggle fold/unfold and confirm the selected screen, composer text, and terminal/workspace state survive.

## F. Release evidence

Record for each section: pass/fail, device model, Android build, Termux version, PRoot alias, Ollama version, exact command/output, and a screenshot or log excerpt. Do not mark a section passed from JVM tests alone.

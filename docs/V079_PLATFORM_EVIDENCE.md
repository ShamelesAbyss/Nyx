# NYX v0.7.9 platform evidence

## proot-distro (official README, fetched 2026-11-12)
Source: https://github.com/termux/proot-distro

- `login` supports `-d` / `--detach`: "Start the session in the background and return to the prompt immediately. The session is daemonized (double-fork + setsid, detached from the controlling terminal) and its stdin/stdout/stderr are redirected to /dev/null, so output is discarded — redirect inside your own command if you need logs. A detached login with no -- COMMAND exits at once (the shell reads EOF). Track it with proot-distro ps and stop it with proot-distro kill."
- `proot-distro ps` lists active sessions: `PID CONTAINER TYPE USER UPTIME COMMAND`; detached sessions marked `*` after TYPE (e.g. `run*`). `ps -q` prints only PIDs.
- `proot-distro kill PID|CONTAINER|--all` terminates the entire process tree, unlike a bare kill of the proot parent. `--signal KILL` available.
- The underlying proot command line includes `proot --kill-on-exit ...`. This explains why a `setsid`/`nohup` child inside a short-lived `proot-distro login` is killed when that login exits: `setsid` changes the POSIX session but does not detach a tracee from PRoot's ptrace supervision.
- Therefore the confirmed device symptom (PID verified, server starts initializing, never listens, refresh says stopped) is consistent with PRoot tearing down the tracee when the short-lived start command returns.
- `--detach` may not exist on older installed proot-distro versions. Must probe `login --help` for `--detach`, and `ps`/`kill` support, then fall back.

## Termux RUN_COMMAND (official wiki)
Source: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent

- `RUN_COMMAND_BACKGROUND=true` runs a background command; result callback arrives only when the command exits.
- `stdout`/`stderr` are returned then, max ~100KB combined.
- A long-running command can keep the Termux service/command alive; no result callback until it exits.
- Third-party app needs `com.termux.permission.RUN_COMMAND` and Termux `allow-external-apps=true`.
- Android may kill Termux; battery optimization exemption helps but is not a guarantee.

## Design implication
- Preferred guest launch: `proot-distro login --detach <alias> -- /bin/bash -lc '<guest script>'`, with logs redirected inside the guest. Track session PID via `proot-distro ps`, and stop via `proot-distro kill <verified session PID>` after stopping the verified Ollama PID. The implemented `TerminalBridge.prootHostCommand` places `--detach` before the alias.
- Fallback when `--detach`/`ps`/`kill` unavailable: dispatch one long-lived `proot-distro login <alias> -- /bin/bash -lc 'exec ollama serve ...'` through Termux RUN_COMMAND and do not wait for its result. The PRoot session stays alive because its foreground command is the server. Track a unique session token and the Ollama PID; stop the Ollama PID, which ends the foreground session.
- The Android foreground owner is `OllamaHostService` (`dataSync`). It keeps the app process alive while the session is created and exposes an explicit Stop action; it does not bypass Android's own foreground-service timeout.

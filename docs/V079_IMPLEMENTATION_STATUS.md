# NYX v0.7.9 — Persistent Guest Runtime

Status: implemented in source, JVM-tested, debug-built, and **published as GitHub v0.7.9**; **not device-verified**.

## Failure addressed

The v0.7.7/v0.7.8 guest path started Ollama with `setsid`/`nohup` inside a short-lived `proot-distro login`. PRoot supervises tracees with `--kill-on-exit`; changing the POSIX session does not detach a tracee from that supervision. The guest PID could be observed and then disappear before the server listened. Separately, even a successful foreground `RUN_COMMAND` could outlive the NYX activity because no Android foreground owner was held.

## Implementation

| Area | Implementation |
| :--- | :--- |
| Foreground owner | `OllamaHostService` is a non-exported `Service` with `android:foregroundServiceType="dataSync"` and `FOREGROUND_SERVICE_DATA_SYNC`. It owns guest start/stop/restart, keeps a low-importance ongoing notification while the managed server is active, reports Starting/Ready · Running/Recovering/Stopped/Error, and exposes View Activity, Stop Worker, and optional Open Termux actions. It polls the manager every 20 seconds. |
| Host mode | `NyxViewModel` keeps the direct `OllamaServerManager.start/stop/restart` path for `TERMUX_HOST`. The service is used only for a verified `DEBIAN_PROOT` guest. |
| Session capability | `OllamaGuestRuntime.sessionCapabilityScript` probes `proot-distro login --help`, `ps --help`, and `kill --help`. Detached mode requires all three; otherwise the foreground fallback is used. |
| Detached launch | `proot-distro login --detach <alias> -- /bin/bash -lc '<guest script>'`; `--detach` is placed before the alias. The session PID is discovered from a before/after `proot-distro ps` diff, and its `/proc/<pid>/stat` start token is persisted. |
| Foreground fallback | One long-lived `proot-distro login <alias> -- /bin/bash -lc '<guest script>'` dispatched without waiting for a Termux callback. The guest script ends in `exec ollama serve`, so the login command remains alive as long as the server does. |
| Server ownership | The guest script writes `NYX1 <pid> <start-ticks> <timestamp>` only after verifying the binary and state directory. It refuses a second live NYX-owned PID. The manager persists PID/start-token records per target and refuses to signal a mismatched process. |
| Stop | Verified single-PID TERM, then `proot-distro kill <verified session pid>`, then repeated `/proc/<pid>` inspection. A surviving session is reported as an error and remains visible; STOPPED is not claimed. |
| Refresh | Re-reads the session record, verifies its start token, adopts a live process, and clears only an unverifiable record. A verified session with no guest PID record is still shown as a live session with Stop available. |
| Startup states | `PROCESS_GONE`, `GUEST_API_WAITING`, `ANDROID_REACHABILITY_WAITING`, `API_WAITING`, `PORT_CONFLICT`, `READY`. A port conflict leaves the process running. |
| Chat/objective reconnect | `startManagedOllamaForInference` routes guest startup through the service, polls discovery, and refreshes models only after readiness. Chat calls it before generating when a loopback endpoint is disconnected; objective creation and resume use the same helper. |
| UI | The Models card shows the session token identity, launch mode, target, and operation busy state. Lifecycle buttons are disabled while the service operation is running. The notification remains while the managed server is active; Stop Worker uses the same verified stop path. |

## Verification performed

- Full JVM suite: **632 tests, 0 failures, 0 errors, 0 skipped** in the final clean build; see `TEST_RESULTS.md` for the frozen result.
- `OllamaGuestRuntimeTest` covers session-row parsing, malformed rows, new-session selection, capability markers, start-token parsing, startup-stage classification, safe script construction, and token validation.
- Existing `OllamaLifecyclePolicyTest`, `OllamaProcessTest`, and `OllamaDiscoveryTest` remain green.
- Debug APK and instrumentation-test APK compile.
- Merged manifest confirms `FOREGROUND_SERVICE_DATA_SYNC` and `OllamaHostService` with `foregroundServiceType="dataSync"`.

## Required physical-device checks

1. Install Termux and `proot-distro`; confirm `proot-distro login --help`, `ps`, and `kill` output matches the parser.
2. Start a Debian Ollama server from **Models** and confirm the notification appears, the session survives backgrounding the NYX activity, and `GET /api/tags` succeeds from Android.
3. Confirm Stop removes both the guest Ollama process and the PRoot session; verify `/proc/<pid>` and `proot-distro ps` are empty.
4. Repeat with an older `proot-distro` that lacks `--detach` and confirm the foreground fallback stays alive.
5. Confirm a user-started Ollama server is adopted read-only and never killed.
6. Confirm Android 14+ does not reject the `dataSync` foreground service and that the notification action works.

No result above is claimed as device-verified until these checks are run.

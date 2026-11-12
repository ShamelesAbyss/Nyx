# NYX Hotfix Reports

## 0.7.9 — Persistent Debian Ollama Runtime

Date: 2026-11-12
Scope: the confirmed Debian Ollama startup/persistence failure and Android background ownership. Published as GitHub v0.7.9.

### Root cause

The v0.7.8 guest launcher started `ollama serve` with `setsid`/`nohup` inside a short-lived
`proot-distro login`. PRoot supervises tracees with `--kill-on-exit`; a new POSIX session does not
detach a tracee from that supervision. The verified PID could therefore exist during initialization
and disappear when the login command returned. The Android process also had no foreground owner
while waiting for the guest API, so it could be frozen before the server listened.

### Fix

- Added `OllamaHostService`, a non-exported `dataSync` foreground service. It owns guest
  start/stop/restart, keeps a notification while the managed server is active, reports
  Starting/Ready/Recovering/Stopped/Error, and exposes View Activity, Stop Worker, and optional
  Open Termux actions. It polls the verified manager every 20 seconds.
- Added `OllamaGuestRuntime`, which probes `proot-distro login --detach`, `ps`, and `kill` together.
  Supported installations get one detached session with a persisted session PID and kernel
  start-time token. Older installations keep one foreground PRoot login alive.
- The server always runs in the foreground of its session with `exec ollama serve`, avoiding PRoot's
  `--kill-on-exit` teardown.
- Stop terminates the verified guest PID, calls `proot-distro kill` for the verified session, and
  re-inspects `/proc/<pid>` before reporting STOPPED. A surviving session remains visible with a
  retryable error.
- Refresh re-reads the session record, verifies its start token, adopts a live process, and clears
  only an unverifiable record. Orphaned sessions expose Stop; adopted servers are never killed.
- Readiness distinguishes a dead process, guest-local API waiting, Android reachability waiting,
  API waiting, port conflict, and READY. Chat, objective creation, and objective resume start a
  stopped loopback runtime and refresh models before continuing.

### Verification

- Full JVM suite: **632 passed**, zero failures, zero errors, zero skipped.
- `OllamaGuestRuntimeTest`: 9 focused tests for session parsing, ownership, capability parsing,
  startup stages, and safe scripts.
- Debug APK and instrumentation-test APK build successfully.
- Package: `com.nyx.ai`, versionCode 12, versionName 0.7.9.
- Debug signing certificate remains the existing v0.7.7/v0.7.8 certificate.

### Not verified on hardware

No physical Android device was attached. Actual `proot-distro ps` output and detached-session
lifetime, Android 14+ `dataSync` acceptance, Termux `RUN_COMMAND` lifetime, guest API reachability,
the foreground fallback on an older `proot-distro`, notification actions, and the full
Models → Start → Ready → Chat acceptance sequence remain device checks.

## 0.7.8 — Device Hotfix Report

Date: 2026-11-12
Scope: two confirmed device failures only. No feature expansion, no publication.

## Bug 1 — Debian Ollama blocked by a missing PRoot path

### Root cause

`NyxViewModel` created one `TerminalBridge` for `OllamaServerManager` and a different bridge inside
`TerminalRepository`. `EnvironmentDiscovery.discover()` called `configureDiscoveredProot()` only on
the repository's bridge. The manager's bridge therefore kept a null PRoot probe and
`TermuxIpcAdapter` returned:

`proot-distro path probe did not find an available executable`

even though Debian and `/usr/local/bin/ollama` existed.

### Fix

- Added `ProotExecutableStore`, a process-wide and persisted store for the host-verified
  `proot-distro` path.
- `TerminalBridge` now resolves that shared path for every instance. A bridge created before
  discovery sees the path after discovery; a service-created bridge and a post-restart bridge load
  the persisted path.
- `NyxViewModel` now shares one `TerminalBridge` between `TerminalRepository` and
  `OllamaServerManager`.
- `EnvironmentDiscovery` selects a fresh host probe first, then a persisted discovery, and stores
  the validated absolute path.
- No filesystem access is attempted from the NYX process to Termux's private directory; the host
  probe remains the evidence.
- Existing host-isolated shell execution, Debian `proot-distro login`, RUN_COMMAND authorization,
  and environment-scoped process ownership are unchanged.

## Bug 2 — Hardcoded JDK package that does not exist in Debian Trixie

### Root cause

`DependencyPlanner` hardcoded `openjdk-17-jdk-headless` for guest `java`/`javac`. Debian Trixie
does not provide that package, producing:

`E: Unable to locate package openjdk-17-jdk-headless`

### Fix

- Added `GuestJdkResolver`, a read-only probe that reads `/etc/os-release`, architecture, APT source
  count, `apt-cache policy` candidates for `default-jdk-headless`,
  `openjdk-21-jdk-headless`, and `openjdk-17-jdk-headless`, and the observed Gradle version.
- Trixie candidate order prefers OpenJDK 21, then the default JDK metapackage, then OpenJDK 17.
  Bookworm prefers 17. Unknown releases use real candidates, never a fixed package.
- `DependencyPlanner` now requires a verified guest JDK package. If none is resolved, it emits a
  blocker containing the probe diagnostic and proposes no blind JDK install.
- `prepareEnvironment()` resolves candidates inside Debian before planning or installing.
- Missing APT sources, missing `apt-cache`, and stale/empty candidate lists produce actionable
  diagnostics; the probe never runs `apt-get update`, installs packages, or modifies sources.
- `DependencyInstaller` still re-probes `java` and `javac` and only reports success when both are
  `VERIFIED` guest-native executables.
- Gradle remains a separate capability check; its observed version is included in the JDK probe
  diagnostic rather than being conflated with the JDK package.

## Acceptance-test mapping

| Acceptance requirement | Automated coverage |
| --- | --- |
| Discovery and Ollama detection share the PRoot path | `ProotPathResolverTest.discovered path is recovered by a fresh bridge view`; fresh discovery wins over stale persisted path |
| Fresh app/service instances recover guest execution | Shared/persisted store plus `ProotPathResolverTest`; `TerminalBridge` reads the store at command time |
| Debian-only Ollama startup is dispatched, not blocked | `ProotPathResolverTest.verified path produces a debian dispatch descriptor` and missing-path refusal test |
| Missing Java 17 resolves real candidates | `GuestJdkResolverTest` Trixie/Bookworm/default/no-candidate/source tests; planner tests for 21 and unresolved blocker |
| Termux-host behavior remains functional | Existing host planner tests plus `ProotPathResolverTest.termux host execution remains a direct bash dispatch` |

## Verification

- Focused tests: 34 passed (DependencyPlannerTest 22, GuestJdkResolverTest 6, ProotPathResolverTest 6).
- Full JVM suite: 623 passed, zero failures, zero errors, zero skipped.
- Debug APK and instrumentation-test APK build successfully.
- Package: `com.nyx.ai`, versionCode 11, versionName 0.7.8.
- Debug signing certificate remains the existing v0.7.5/v0.7.7 certificate.

## Not verified on hardware

No physical Android device was attached. Real Debian `ollama serve`, `apt-cache` output on the
owner's Trixie guest, PRoot RUN_COMMAND dispatch, model enumeration, Chat reconnection, and the
actual JDK install were not executed here. The tests prove the path and package-selection logic;
they do not replace device validation.

# NYX v0.7.5 — Project Continuum integration status

Status: **published development release**. Package `com.nyx.ai`, versionCode **9**, versionName **0.7.5**, debug-signed with the same certificate as v0.7.0.

## Implemented

- **Managed Termux Ollama:** detect, isolated host start, readiness polling, stop, restart, PID plus kernel start-token ownership, adopted-server protection, bounded logs, RSS/uptime, and Models-screen controls.
- **Arsenal:** nine versioned built-ins, relevance-bounded selection, declared-tool allowlisting, strict untrusted manifest import, enable/configure/remove, and evidence-linked execution logs.
- **Gateways:** streamable-HTTP MCP and GitHub tools, per-server allowlists, per-run budgets, timeouts, health checks, redacted logs, Keystore-only tokens, and UI management/invocation.
- **Project intelligence:** durable evidence-hashed project memory, stale/unverified suppression, objective prompt injection, and an incremental Kotlin/Java symbol index.
- **Live Worker Console:** persisted worker activity, compact chat preview, full timeline, and engine/terminal/gateway/Ollama events.
- **Hardening:** atomic collision-safe workspace creation, terminal cancellation mapped to `uncertain`, workspace-scoped routine grants, synchronized revocation, and package authorization scoped under the workspace.

## Verification

- 598 JVM tests, 0 failures, 0 errors, 0 skipped.
- Debug APK and instrumentation-test APK assembled.
- Release integrity checker passed.
- APK versionName/versionCode/application ID and signer continuity verified.
- No physical Android device was available; Termux, real Ollama lifecycle, Keystore, MCP/GitHub networking, notifications, and service recovery remain device-validation items.

## Deliberate limits

- The symbol index is implemented and tested but not yet automatically injected into every task prompt.
- Gateway tool lists are snapshotted per objective; changes require a new objective.
- The managed server requires Ollama to be installed in Termux; NYX does not bundle or silently install it.
- The APK is debug-signed, not a Play Store production build.

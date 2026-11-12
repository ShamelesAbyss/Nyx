# Changelog

## 0.5.0 — Real execution engine

- Moved objective execution out of the ViewModel into a `dataSync` foreground service with durable leases, heartbeats, notification controls, and startup reconciliation. Closing the UI no longer cancels a running objective; process death still stops it, and interrupted tasks are never replayed automatically.
- Added dependency-aware concurrent scheduling (`ConcurrentTaskScheduler`, `ResourcePolicy`) bounded by device RAM, thermal status, battery, storage, and per-worker-kind limits. Blocking propagates transitively through stalled subgraphs.
- Added locally checked completion evidence: exit codes, expected/forbidden output, file existence, file contents, and SHA-256. A model's prose alone never marks a task complete, and any failed or denied tool call fails verification.
- Added conservative recovery: bounded retry only for read-only/verification work, permanent failure on a repeated identical failure signature, and mandatory human review for anything that may have changed external state.
- Added a real Termux bridge: environment discovery (host, PREFIX, HOME, tools, installed PRoot distros), a command-risk policy that blocks root/protected-Android operations, per-command approval for mutating commands, hard `timeout` supervision when available, and persisted stdout/stderr/exit-code history.
- Added workspace-scoped agent tools: `terminal_run`, `file_read`, `file_write`, `file_list`, `file_search`, `project_detect`, `build_run`, `test_run`, plus a `FileProbe` used by verification. Terminal access is off by default and requires an explicit toggle and workspace path.
- Added provider-neutral inference (`InferenceProvider`, `ProviderFactory`, capability cache) shared by chat, agents, and the engine, with the existing Ollama and OpenAI-compatible transports and Keystore credential path unchanged.
- Added a filesystem-backed context store with bounded, relevance-ranked context assembly and persisted context records, plus persisted task evidence rows.
- Added Room schema v3 (additive 2→3 migration): task priority/worker kind/idempotency/failure signature/evidence, objective lease columns, terminal command history, task evidence, context records, and execution leases.
- Added a Terminal panel in the Autonomy screen and 71 new JVM tests covering command policy, shell construction, evidence verification, recovery, environment parsing, scheduling, context assembly, and provider capabilities. Total JVM tests: 98, all passing.
- Not implemented in this release: native GGUF/llama.cpp inference, mid-process resumption of a killed objective, real Debian/PRoot validation on a device, multi-device sync, and any autonomous publication, credential, or privilege action.

## 0.4.0 — Durable objectives and execution foundations

- Added durable objectives, task DAGs, lifecycle statuses, attempt budgets, checkpoints, structured tool invocation/observation events, and text artifacts in Room schema v2.
- Added a non-destructive Room 1→2 migration and Android instrumentation test fixture that seeds and checks legacy chat, endpoint, agent, workflow, and execution records.
- Added strict, bounded JSON plan parsing (64 KiB, max 12 tasks, field limits, unique keys, valid dependencies, acyclic graph, deterministic stable task UUIDs). Model-provided tools, agents, and actions are ignored; the caller-selected agent and existing tool allowlist govern execution.
- Added a single-inference, deterministic dependency-aware objective runner and Autonomy screen. Completed tasks are not replayed. Interrupted active work is marked uncertain; failed/interrupted task retries require explicit user review. Outcomes and traces persist locally.
- Added a conservative resource policy foundation with serial model execution and explicit local completion checks. Verification is intentionally not presented as independent proof of semantic correctness.
- Added an isolated Termux RUN_COMMAND protocol adapter core with environment labels, request validation, argv-only command descriptors, Termux permission/readiness checks, bounded callback data, timeouts, and pure result-classification tests. It is not yet exposed in the autonomy UI or available as an agent tool; timeout/cancel stops waiting for the callback and cannot guarantee that Termux stopped the process.
- Fixed Settings → About to derive the displayed release from installed Android package metadata. Added a release-integrity checker for Gradle, APK binary manifest, delivery filenames, and release docs.
- Preserved prior branding, adaptive layout, Ollama/OpenAI-compatible clients, encrypted API keys, agents, workflows, chat history, and existing v0.3.0 package identity.

## Retained 0.3.0 and 0.2.0 capabilities

Ollama and OpenAI-compatible `/v1` inference, streaming/cancellation, bounded local agents, agent editing, sequential workflows, Keystore-protected API credentials, explicit remote endpoint consent, user-selected text import, adaptive Fold layouts, and device/thermal snapshots remain in place. No destructive Room migration is used.

## Known incomplete / unverified capabilities

NYX v0.4.0 is not a full autonomous computing platform. Objectives currently allow up to 12 validated DAG tasks and execute one agent task at a time; there is no dynamic replanning, 20-task queue, parallel tool worker, persistent filesystem-backed context store, context compression, pluggable provider interface, or live resource scheduler. Objective execution is ViewModel-owned while the process runs; it does not use a foreground service or WorkManager and cannot continue after process death. Room recovery marks active tasks for review rather than replaying them. The Termux bridge is protocol/library work only: no setup screen, connected process supervision, autonomous shell access, or validated Pixel/GrapheneOS execution. Debian Proot setup is unverified. Native GGUF inference, Android UI automation/accessibility, complete software-development workflows, and production signing are not included. Physical-device migration, Keystore, Termux, Fold, performance, and live backend tests remain outstanding. See `NYX_DEVELOPMENT_REPORT.md` and `TEST_RESULTS.md`.

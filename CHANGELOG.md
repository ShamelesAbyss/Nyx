# Changelog

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

# NYX 0.4.0 Development Handoff

## Release identity

The target release is versionName `0.4.0`, versionCode `4`, application ID `com.nyx.ai`. Existing v0.3.0 features, app identity, user records, dark-violet brand, Fold-adaptive layout, Ollama/OpenAI-compatible inference, agents, sequential workflows, and Keystore credential protection were preserved. No remote GitHub repository was accessed or changed.

## Implemented in this continuation

**Durable objective foundation.** Room schema v2 adds objective, task, event, and artifact records, with an additive v1→v2 migration and no destructive fallback. Instrumentation source seeds the prior schema, checks that legacy conversations/messages, endpoint references, agents, workflows, and execution logs survive, and exercises the new DAO.

**Bounded planner and scheduler.** The model-produced plan is parsed as strict bounded JSON, capped at 12 tasks and checked for valid fields, duplicate keys/dependencies, missing/self dependencies, and cycles. Stable task UUIDs are derived locally from objective ID and task key. Agent identity is supplied by the caller; unknown model-provided agent/tool/action/model fields are ignored. A deterministic scheduler selects one dependency-ready task at a time.

**Objective execution and review UI.** The new Autonomy screen displays persisted objective/task status, task trace, and text artifacts. The coordinator calls the existing selected agent and intersects configured tools with the app's safe default allowlist. It stores bounded tool invocation and observation details, checkpoints, outcomes, and modest verification status. Failed or interrupted tasks are not automatically repeated; a user must review and approve a retry. The verifier only checks for non-empty output and absence of reported tool error/denial; it does not establish semantic truth.

**Termux protocol and release identity.** A standalone RUN_COMMAND adapter core implements request validation, explicit request authorization, host/Debian environment descriptors, bounded result classification, and readiness checks. It is not yet connected to the Autonomy UI or agent tool set; it cannot guarantee termination of a dispatched Termux process. About uses installed Android package metadata, and a Python checker cross-validates Gradle, APK manifest, delivery filenames, and current release docs.

## Milestone status

| Area | Status |
|---|---|
| Room persistence/migration | Implemented; instrumentation test compiles; physical runtime execution pending |
| Objective/task DAG and stable IDs | Implemented, max 12 tasks |
| Dependency scheduling | Implemented, one agent/model task at a time |
| Persistent context | Partial: goal, tasks, outcome, checkpoint, events, and artifacts in Room; no filesystem-backed context store, retrieval, or history compression |
| Recovery | Conservative interruption states and explicit reviewed retry; no autonomous diagnosis, repair, dynamic replanning, or continuation without app-process work |
| Inference provider abstraction | Not implemented; current engine uses the existing OllamaClient integration |
| Queue and parallelism | No 20-task management queue or parallel independent tools; current plans max 12 and execute serially |
| Termux/Debian | Protocol/library only; not wired into app or agent tasks; real permission, callback, path setup, and PRoot behavior are unverified |
| Background work | No WorkManager/foreground service; ViewModel survives Activity recreation but not process death. Restart recovery is database-based and conservative |
| Autonomous software development | Not implemented; no project-edit/build/test/repair loop |
| Control center | Initial Room-backed objective/task/event/artifact UI; no terminal panel, approval queue, or live resource scheduler |
| Android UI automation | Not implemented |
| Pixel Fold/GrapheneOS verification | Not performed in this environment |

## Build/test evidence

The first integrated compile exposed a coroutine Channel import and a missing bounded title parser helper; both were corrected. The final clean v0.4.0 command `./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest` passed. It ran 27 JVM tests with zero failures/errors/skips, assembled the debug APK, and compiled the instrumentation APK. The debug certificate matched the v0.3.0 APK checked in this workspace. Migration/Keystore instrumentation tests were compiled, not run. Final APK manifest and release checker results are recorded in `TEST_RESULTS.md`.

## Exact next engineering step

On the owner's Android 17/GrapheneOS Pixel Fold, run the Room migration and Keystore instrumentation suites, then test Termux RUN_COMMAND permission and callback behavior against the installed Termux version. Do not expose the bridge to model-directed tasks until environment setup, process ownership/cancellation, command authorization, audit persistence, and output limits are validated. Next, decouple execution ownership from ViewModel using an Android-appropriate worker/service with visible notification and process-death recovery; then add context storage/retrieval and carefully bounded scheduler parallelism.

# NYX v0.8.5 — Project Sovereign 🌑🤖

**From answering questions to accomplishing objectives. From tool execution to evidence-driven autonomy.**

NYX has always been able to plan an objective and run authorized tools. Project Sovereign is the release where that loop learns to stay coherent: it remembers what it has established, gives each task only the capabilities it needs, refuses to accept a confident sentence as proof of work, and recovers from its own malformed actions without spinning forever.

This is not a claim that NYX has achieved unbounded or Manus-style autonomy. It is a deliberate, bounded step toward autonomous execution that is easier to inspect, harder to fool, and more likely to survive a long-running real-world task.

## Why this release

Long objectives fail for ordinary reasons. A model forgets a fact after context compaction. It repeats an action that already failed. It receives a filesystem tool for an inference task, or a shell for work that only needed a read. It announces success because the prose sounds confident, even though no tool produced evidence.

Project Sovereign addresses those failure modes inside the existing planner, scheduler, verifier, and policy boundaries. It does not add a second execution engine, bypass approvals, or widen the app's authority.

## 🧠 Adaptive Agent Intelligence

- **Task-aware reasoning depth.** Each task receives a LIGHT, STANDARD, or DEEP operating profile derived from its worker kind, instructions, and observed constraints. Development, diagnosis, and verification can receive more deliberation; simple inference and routine file work receive less.
- **Bounded budgets.** Every task has hard ceilings for model calls, tool calls, steps, context characters, repair attempts, and consecutive failures. The model cannot widen its own budget.
- **Structured decision-making.** The agent works one decision and one observation at a time. It records a short decision summary and a classified observation; it is never asked for, and does not store, a hidden chain-of-thought transcript.
- **Capability-aware planning.** The planner is told which tools are actually enabled for the run, rather than guessing from a generic tool list.

## 🤖 Reliable Autonomous Execution

- **Task-specific tool access.** Inference, filesystem, verification, terminal, and development tasks receive different capability sets. Verification can inspect and run the fixed test command, but cannot write files or run arbitrary shell. Terminal work remains policy-gated inside the authorized workspace.
- **False-success protection.** For tool-dependent tasks, a completion claim without a successful tool observation is rejected once. A repeated claim is marked unverified and fails objective verification. An honest blocker is preserved as a blocker, not misread as success.
- **Validated structured actions.** Strict schema parsing remains the default. A single complete JSON action may be recovered from a Markdown fence or surrounding text; prose is never interpreted as a shell command, and multiple actions are rejected.
- **Failure detection and correction.** The runner stops after repeated identical failing actions or three consecutive tool failures. Retries receive the previous failure evidence and an explicit instruction to change approach instead of repeating it.

## 🔄 Persistence & Recovery

- **Durable working state.** Each task checkpoints a compact state: objective, requirements, established facts, open errors, next action, evidence pointers, and completed steps. The state is bounded, redacted, and restored after a retry or process restart.
- **Checkpoint restoration.** The state travels inside the existing task checkpoint, so it does not depend on an ever-growing chat transcript. It is a recovery aid, not proof that an interrupted side effect did not happen.
- **Bounded execution repair.** At most two action repairs are attempted, each recorded. The engine persists running, verifying, completed, failed, and review states before and after verification.
- **Improved interruption handling.** Uncertain mutating work still requires explicit **Review & resume**. Pause and cancel remain cooperative and visible.
- **Two-hour objective watchdog.** The app-level objective deadline is now two hours, subject to Android execution limits, thermal and memory pressure, battery state, leases, and user control. This is an upper bound, not a promise that Android will keep the process alive.

## 🔐 Safety & Privacy

- **Credential redaction.** Durable observations, decision summaries, and exported diagnostics redact bearer tokens, API keys, passwords, secrets, and private-key blocks.
- **Existing permission boundaries.** Workspace authorization, command approval, dependency installation authorization, provider snapshots, and no-root/no-arbitrary-shell rules are unchanged.
- **Controlled tool access.** Task scope is intersected with the tools actually available on the run. A missing terminal is never presented to the model as a capability.
- **Preserved verification requirements.** The existing task verifier remains the authority for completion. A model's claim is not evidence.
- **No new data collection.** The working state stays in the local task checkpoint. No account, cloud sync, model weights, or signing material was added.

## ⚡ Mobile Performance

- **Project Endurance integration.** Sovereign reasoning depth and budgets respond to Eco, Balanced, Performance, and Adaptive modes.
- **Resource-aware execution.** Eco can reduce deliberation and release an idle native model; Performance may raise budgets within fixed caps.
- **Existing safeguards retained.** Thermal, battery, memory, foreground-service, and lease protections still apply. No speed, battery, or task-completion benchmark is claimed.

## 🛠️ Testing & Compatibility

- **731 JVM tests across 70 classes**, zero failures, zero errors, zero skips.
- New focused coverage: 9 adaptive-policy tests, 6 working-state/tool-scope tests, and 3 tolerant-parser tests.
- Clean Android build completed successfully in **1m 21s**.
- **Android SDK 37** target, minimum API 26, **ARM64 (`arm64-v8a`)** only.
- **Unchanged signing certificate**: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`.
- **16 KB ZIP alignment passed**.
- **No native llama.cpp modifications.** All 4,148 vendored files match the v0.8.4 source archive byte-for-byte.
- Package `com.nyx.ai`, **versionCode 18**, **versionName 0.8.5**. VersionCode 18 is required because v0.8.4 already used 17; the established certificate preserves in-place upgrade behavior.
- No AAB or Google Play submission was produced.

## What this release does not claim

- The Sovereign execution loop, task-scoped tools, false-success guard, durable recovery, and two-hour run behavior have **not** been validated on a physical Android device in this cycle.
- No real provider/model was used to exercise the new loop end to end; the verification is JVM policy/state/parser coverage plus a clean Android build.
- The instrumentation APK compiled but was not executed. No Android device or `adb` was available in the build environment.
- No measured improvement in task completion rate, inference speed, battery consumption, or reliability is claimed.
- The APK is debug-signed and ARM64-only. It is a development release, not a Play Store or production-signed artifact.

## Upgrade notes

Install `NYX-v0.8.5.apk` over v0.8.4 when the signing certificate matches. The application ID and signing identity are unchanged. Back up important chats, models, and workspaces before experimenting; an in-place update is not a substitute for a backup.

## Release artifacts

| Asset | SHA-256 |
| :--- | :--- |
| `NYX-v0.8.5.apk` | `024c5fa4ab91a7d28b19f8fe22c362766109470e72a615a82f81501a1d8aa456` |
| `NYX-v0.8.5-androidTest.apk` | `5454b3e6d8f33b86a07d30ea5b3b4f50a63ec26e3feba371a98805cdb5f022b3` |
| `NYX-Source-v0.8.5.zip` | Recorded in the published `NYX-v0.8.5-SHA256SUMS.txt` |
| `NYX-v0.8.5-SHA256SUMS.txt` | Verify every downloaded asset with `sha256sum -c` |

> The source ZIP is rebuilt after documentation changes. Its hash and the manifest hash are published with the release assets; verify every download with `sha256sum -c NYX-v0.8.5-SHA256SUMS.txt`.

---

**NYX v0.8.5 — Project Sovereign**

*Your Intelligence. Your Device. Your Rules.*

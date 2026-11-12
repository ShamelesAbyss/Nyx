# v0.6.0 integration checklist

## Actual v0.5.0 audit

UI uses Compose, Room flows and permission controls. ObjectiveExecutionService owns coroutines, but incorrectly describes all objective computation as dataSync; pause/cancel do not immediately cancel model calls; cleanup in cancelled coroutine is not NonCancellable; engine writes stale objective copies that can erase a lease. ViewModel still builds OllamaClient directly for chat/agents. Shared factory can silently omit an unreadable key and chooses most-recent endpoint rather than a snapshotted objective provider. Scheduler worker labels are not independent inference concurrency limits. Verification accepts model prose as output evidence and no-evidence output as passed. TerminalAgentTools has lexical path checks only, tilde paths are not normalized, generic commands can escape the workspace, writes exceed IPC argument bounds, evidence buffers shared by concurrent tasks. These are integration defects, not proven physical-device behavior.

All v0.5 JVM suites passed; no physical Android, Termux, PRoot, or model inference was executed on hardware. Keep baseline APK/source artifacts untouched.

## Work boundaries

- [ ] SDK37 supported build; API36 only documented blocker; minSdk26 preserved
- [ ] Native llama.cpp CPU provider, SAF private models, load/unload/stream/cancel
- [ ] Shared provider path chat/agents/objectives; queued inference distinct from tool tasks
- [ ] Service lifecycle bounded and classified honestly; prompt cancellation and checkpoint cleanup
- [ ] Objective verification does not use model prose to prove command success
- [ ] Development inspection/baseline/typed edits/test/repair budgets with actual evidence
- [ ] Harden canonical Termux workspace operations, no generic model shell execution
- [ ] Scoped context freshness/compaction and offline skill manifests
- [ ] SDK policy guard, unit tests, APK/instrumentation compilation, device test procedure
- [ ] Accurate docs/status, release identity/checksums and source excluding caches/weights/secrets

## Verified platform sources, 2026-11-12

https://developer.android.com/about/versions/17/setup-sdk — SDK37 configuration; at least AGP8.9 per setup doc, package availability must be tested.
https://developer.android.com/google/play/requirements/target-sdk — new phone apps/updates API36 from 2026-08-31; future checks required, not permanent hardcoded policy assurance.
https://developer.android.com/about/versions/17/behavior-changes-17 — runtime ACCESS_LOCAL_NETWORK for target37 LAN; read-only native dynamic code; use packaged System.loadLibrary not executable downloads; large-screen resizing mandatory.
https://developer.android.com/about/versions/17/behavior-changes-all — app memory limiter based on RAM, ApplicationExitInfo REASON_OTHER description MemoryLimiter:AnonSwap; same-profile loopback unaffected, cross-profile blocked.
https://developer.android.com/develop/background-work/services/fgs/service-types — dataSync is data transfer, not arbitrary compute. shortService roughly 3 minutes; specialUse needs honest manifest subtype and Play review, no guaranteed approval or indefinite background work.

The uploaded directive ends mid-sentence under Priority Nine. No missing permissions, self-update, installation, signing, publication, or repository actions are inferred.

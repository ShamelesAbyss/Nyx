# NYX v0.8.7 — Project Breakthrough

Published release, package `com.nyx.ai`, versionCode 20. Development continues from the verified complete local v0.8.6 candidate, not the older public v0.8.5 source. The original v0.8.6 artifacts remain available unchanged. Published on GitHub with the verified artifacts; no AAB or Play publication.

## Keyboard and foldable editing

The source audit identified competing keyboard/safe-inset ownership and unbounded inline editor sizing as concrete layout risks. MainActivity now assigns keyboard padding to the root Scaffold, consumes Scaffold padding in descendants and explicitly requests window resize while retaining edge-to-edge. Nested editors no longer independently add keyboard padding. This repairs source-level sizing interactions; it is not a demonstrated diagnosis of every Gboard split-keyboard/device configuration.

A selection-preserving full-screen draft editor provides bounded inline multiline inputs and separate Done/Back return, never implicit submission. Long text can scroll; cursor/selection are retained. Objective/chat/terminal/agent/workflow and editable dialogs retain nonsecret drafts through saveable state and configuration changes. Secret drafts remain memory-only. Settings, Arsenal and gateway edits preserve identity and avoid nested dialog sizing problems. Very large saved drafts still face Android Bundle-size limits: saveable state is not unlimited durable storage.

Instrumented tests exercise long-paste recreation/destination changes, full-screen field/Done/caret-adjacent visibility above an IME after rotation and return without submission. They compile but have not been executed on a device; fold/unfold, landscape, split keyboard, font scaling, accessibility and keyboard animations still require hardware validation.

## Extremely slow Ollama planning

The owner reports first text around 109s, 11 characters around 199s and no valid plan before 300s on Pixel Fold/qwen3b. We have not reproduced the request on that hardware. Tiny chunks do not establish whether queueing, cold model load, prompt evaluation, CPU configuration, PRoot overhead, memory/thermal pressure or constrained generation is dominant.

Planning workload is reduced further. Explicit text-only work uses a minimal validated output field and 192-token cap; other compact work uses at most 3 steps and 384 tokens; full graphs retain 1024. Capability subsets prevent requesting disabled workers, and ordinary Kotlin CLI work remains distinct from Android development despite a negated Android-app clause. Compact context is capped at min(configured,2048), full at min(configured,8192); mandatory objective/contract text is refused if too large, never silently truncated. Complete responses pass strict worker/evidence validation; one bounded repair is retained. No partial JSON becomes execution or unchecked progressive expansion.

Reproducible prompt-plus-schema UTF-8 bytes changed from 2160 to 717 for the poem fixture,2271 to 1989 for Kotlin CLI,2525 to 2019 for Kotlin with Android negation and 1942 to 1603 for file-only work. Negated-Kotlin repair context changed from 7115 to 2768 bytes. These are serialized text workload measurements, excluding HTTP framing/chat templates/hidden reasoning. Token estimates are labeled estimates, not provider token counts. No hardware speed, task-completion-rate or battery gain is measured.

Actual provider counters/durations remain available when supplied by Ollama; missing stages are unknown. First-text/inactivity diagnostics are request-local genuine text observations, not network keepalives. The optional Models diagnostic stays 32 tokens / 30 seconds and does not run on startup; slow models may not finish this short check. There is no mandatory warm-up or claim that a sample proves full planner compatibility.

## Mobile patience and checkpointed allowances

Settings offers Quick, Standard, Patient, Extended and Custom independently from Eco/Balanced/Performance/Adaptive. New objectives snapshot their policy; later setting changes do not silently extend an existing objective. Direct agents/workflows capture the selected policy. Ordinary chat and the short diagnostic preserve their prior limits.

Planning/inference request ceilings are 5/30/60/120 minutes, with finite Custom overall1..720 minutes and individual segments at most 120 minutes. First-text limits are2/10/20/30 minutes and inactivity1/5/10/15 minutes, capped by the segment. Extended/Custom require explicit approval. They also allow up to 120-minute build/terminal ceilings; Quick/Standard/Patient keep shorter tool policies. These are ceilings, not artificial delays or guaranteed Android execution entitlement. Custom exposes total minutes; per-stage limits follow the canonical policy, rather than arbitrary independent UI knobs.

Selected policy travels through planner, InferenceSession, Fabric, HTTP and AgentRunner without hidden legacy10min/180s/15min caps. HTTP connection establishment remains short and security settings/pool are preserved. Genuine text updates inactivity, never the hard deadline. Incomplete model responses are rejected before tool execution. Selected tool/build ceilings reach TerminalAgentTools/model gate and development repair elapsed budget, while saved shorter repair budgets and iteration/model-call/file limits remain effective.

The existing specialUse objective service is preserved. Room events reserve a finite allowance segment before dispatch and settle measured monotonic elapsed time on normal stop. Process death conservatively spends the reservation; it never resets allowance or automatically chains more service time. Up to 12 hours means cumulative explicitly resumed, checkpointed allowance, not a 12-hour uninterrupted foreground service. Thermal, low-memory, battery, lease, pause/cancel and uncertain-side-effect review remain. Native cleanup/cancellation is cooperative, not guaranteed hard preemption.

## Independent command contexts and task scheduling

Finite bridge commands now use process-shared bounded admission and app-private durable UUID-correlated operation records. Output, error, actual exit code, environment/workspace and objective/task identity stay isolated. Official RUN_COMMAND correlation IDs are not process-control handles or proof of persistent interactive sessions [1]. Captured output is bounded, not a complete streaming transcript.

Requested command contexts are configurable 1..8, default 3, further capped by resource headroom and energy-mode policy. Balanced can admit2 and Performance up to 4: three is a supported capability when guards allow, not forced parallelism. Model inference remains separately serialized. Terminal and Mission Control display active/queued/uncertain operations, per-lane bounded redacted details and capacity settings. Refresh runs while the displayed work is active, with explicit refresh otherwise.

TaskGraphScheduler preserves dependency ordering and adds stable readiness/launch reservations. Each objective task receives its own TerminalAgentTools state, discovery/checkpoints and evidence drain; composite providers fork children and legacy providers use isolated execute+drain invocation. Evidence carries objective/task ownership, foreign evidence is rejected and preflight observations cannot satisfy completion. Only after those changes was the global tool mutex removed.

Workspace read/write leases protect snapshots, patches/rollback, built-in filesystem operations and builds. Conflicting writes/builds remain serialized; narrow assessed reads or different workspaces may overlap. Guarded shell compositions can remain exclusive in the same project, so three active objective workers do not guarantee three simultaneously running processes in one shared source tree. External edits are not prevented by app locks.

Cancelled/timed-out/dispatched-unknown operations retain uncertain reservations. Recovery neither kills unrelated processes nor replays commands. The user may release a reservation only after explicitly confirming the operation has stopped; release is not a success claim or kill operation. Termux background-service/PRoot behavior still requires device validation [1] [2].

## Verification and device procedure

Exact final clean-build counts, APK digest, signer and alignment are recorded in [TEST_RESULTS.md](TEST_RESULTS.md). Tests include real Linux-host three-process overlap with isolated stdout/stderr and exits0/7/0, failure independence and conservative cancelled-wait ownership, plus deterministic dependency/resource/recovery/conflict/evidence routing. Real Kotlin calculator creation/compile/behavioral fixtures from v0.8.6 are retained. Host tests do not establish Android/Termux execution or real-model autonomy. Room service allowance behavior and Compose IME behavior are source-compiled with policy/instrumentation tests, not hardware executed.

On the Pixel, install over the existing candidate without uninstalling. Test long objective/chat/terminal drafts on folded/unfolded portrait/landscape, open the full-screen editor, move the caret, paste long text and rotate; verify controls/cursor and content survive. Select Standard or explicitly approved Patient/Extended as appropriate, and compare actual final Ollama prompt/load/generation counts/durations for the same calculator goal. Unknown timing must remain unknown.

Use verified guest/native tools and an approved workspace. Request Kotlin calculator files and TestMain.kt behavioral tests, not Android SDK. Confirm a complete validated plan, owned tool operations and observed build/tests before success. In Performance mode with sufficient resources, test three independent read operations or separate workspaces; confirm isolated outputs/IDs, dependency ordering and write/build exclusion. Test failure, pause/cancel and restart on backed-up fixtures; uncertain commands must require review rather than replay. Validate resource-pressure reduction without bypassing safeguards. A120-minute setting does not waive Android service restrictions.

## Remaining limits

The reported slow-model and keyboard failures are not confirmed fixed on-device. No real qwen model ran the full candidate objective here. Full unrestricted incremental planning, generalized interactive terminals, OS-level process cancellation and independently configurable per-stage Custom UI were not added. Concurrency is intentionally reduced for shared mutable source or resource pressure. No claim of measured speedup/energy savings, guaranteed uninterrupted execution or semantic correctness follows from valid JSON or one successful observation. All bundled llama.cpp files, signing identity, user data/model/credential storage and prior features/releases are preserved.

## References

[1]: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent "Official Termux RUN_COMMAND contract"
[2]: https://raw.githubusercontent.com/termux/termux-app/master/app/src/main/java/com/termux/app/RunCommandService.java "Official RunCommandService source"

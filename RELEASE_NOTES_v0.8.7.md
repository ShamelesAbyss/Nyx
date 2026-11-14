# NYX v0.8.7 — Project Breakthrough 🌑⚡

**One phone. Patient intelligence. Independent workers. Evidence-backed execution.**

NYX is evolving into a more patient, capable, hardware-aware Android AI workstation. Project Breakthrough gives slow local models room to work, reduces the planning burden, improves long-form editing, and separates authorized terminal work into independently tracked execution lanes—without dropping the safeguards that make local autonomy accountable.

This official release incorporates **both v0.8.7 Project Breakthrough and the previously unpublished v0.8.6 Project Ignition**. It follows public v0.8.5 Project Sovereign. It is an ARM64 debug-signed development APK, not a production-signed Play Store build.

## 📱 Android, foldable and editing improvements

Keyboard padding has one root Scaffold owner; descendants consume that padding instead of adding competing keyboard space. Bounded inline inputs and a selection-preserving full-screen editor make long objective, chat and terminal drafts easier to edit. Done/Back return the draft without submitting it. Nonsecret drafts survive supported configuration changes; secret fields stay memory-only.

Editable dialogs, agent/gateway identity and other input screens receive focused sizing and draft treatment. Very large saved drafts remain subject to Android Bundle limits. **Fold/unfold, software-keyboard and cursor behavior still require physical-device confirmation.** Instrumentation tests compile but were not executed.

## 🧠 Small-model planning and inference visibility

Capability-sensitive planning uses a minimal validated text response with a 192-token output cap or a compact task plan capped at 384 tokens and three steps. Complex full graphs retain a 1,024-token cap. Context is bounded, mandatory objectives/contracts are never silently truncated, and unavailable workers cannot be selected as if they existed.

A complete response must pass strict extraction, worker and evidence validation before persistence or tools. Truncation is not success; invalid complete plans get one bounded repair. No partial JSON or unchecked progressive expansion is executed.

Deterministic prompt-plus-schema text measurements fell from **2,525 to 2,019 UTF-8 bytes** for the Kotlin CLI goal with an Android-negation clause; its repair fixture fell from **7,115 to 2,768 bytes**. These are workload measurements—not actual provider token counts, hardware benchmarks or measured speedups.

Request-local diagnostics distinguish first text, active text and inactivity. Only genuine content counts as progress, never empty frames or keepalives. Provider-supplied counters/durations are used when available; missing queue/load/prompt stages remain unknown. The optional **Models → Check selected model** check is still a tiny 32-token, 30-second sample, not a mandatory startup warm-up or proof of full planning compatibility.

## ⏳ Patient local intelligence

Execution patience is separate from Eco, Balanced, Performance and Adaptive energy modes.

| Profile | First text ceiling | Text inactivity | Request ceiling |
| :--- | ---: | ---: | ---: |
| Quick | 2 min | 1 min | 5 min |
| Standard, mobile default | 10 min | 5 min | 30 min |
| Patient | 20 min | 10 min | 60 min |
| Extended, explicit approval | 30 min | 15 min | 120 min |
| Custom, explicit approval | Up to 30 min | Up to 15 min | Up to 120 min per segment |

Custom offers a finite cumulative allowance of 1–720 minutes, with each segment capped at 120 minutes. Extended/Custom also support up to 120-minute build/terminal ceilings; ordinary profiles keep shorter tool bounds. Saved shorter repair budgets and iteration, model-call and file limits remain effective.

New objectives snapshot their chosen policy. Planner, HTTP, Fabric, agents and selected tool paths respect it without a hidden shorter legacy planner cap. Ordinary chat retains its existing defaults. Timeout, cancellation and incomplete-response handling cannot quietly become tool execution.

Cumulative allowance is reserved before dispatch and settled on normal interruption. A crash conservatively spends the reservation; it does not reset allowance or automatically chain service segments. **Long patience is not a guarantee of uninterrupted Android foreground execution.** Native cancellation remains cooperative.

## ⚡ Independent Termux and Debian operations

Finite background commands receive unique operation IDs, separate stdout/stderr, actual exit status and durable objective/task ownership records. Requested contexts are configurable from 1–8, defaulting to 3, and are capped by resources, energy mode and workspace conflicts. Balanced may admit two; Performance can admit more when safe.

**Host tests demonstrated three overlapping processes with isolated outputs and exit statuses 0/7/0. Android/Termux concurrency has not yet been independently verified.** These are independent commands, not persistent interactive terminal sessions or public process-control handles.

Mission Control and Terminal show active, queued and uncertain operations, bounded redacted individual details, capacity settings and explicit reservation review.

## 🤖 Coordination, evidence and recovery

Dependency-ready task launches use stable ordering and reservations. Each task has its own tool-provider state and evidence drain; foreign evidence is rejected and preflight output cannot satisfy completion. Workspace read/write leases protect snapshots, edits, rollback and builds.

Conflicting writes/builds remain serialized. Independent safe reads or separate workspaces can overlap; three active workers do not promise three simultaneous guarded processes in one shared project. Inference remains separately serialized.

Cancelled, timed-out or interrupted commands with uncertain process state retain reservations. Recovery does not replay them, kill unrelated processes or interpret a timeout as proof of process death. Releasing a reservation requires explicit confirmation that the operation stopped; it is neither a kill action nor proof of success. Permissions, workspace confinement, process ownership, checkpoints and review safeguards remain intact.

## 🔄 Project Ignition carried forward from v0.8.6

This release brings the previously local-only Ignition work to GitHub: compact planning, strict complete-response extraction, stream-aware lifecycle diagnostics, provider-aware bounded output and the optional model check. Ollama receives its documented schema/`num_predict` fields; compatible endpoints receive explicit output bounds without guessed Ollama fields. Native inference preserves its bounded token contract rather than claiming schema enforcement.

Standalone **Kotlin CLI and Java/JVM development** use the actual required toolchains, without treating an Android-phone mention or a negated Android-app instruction as an SDK requirement. Root-level Kotlin, Java and shell layouts have fixed discovery/build/test paths. Dependency checking follows planned tasks on fresh runs and resume, while installation still requires existing authorization and trusted guest-native candidates.

Agent-loop regressions cover malformed actions, unsupported function-call fallback, exhausted model-call budgets, failed tool evidence, unsupported completion and recovery through successful observations. Linux calculator fixtures create, compile and run behavioral assertions, including intentional failure. **Fixture execution is not proof of end-to-end autonomous project creation by qwen on Android.**

## 🔋 Existing mobile safeguards preserved

Ollama, native GGUF CPU inference, Termux, Debian PRoot, agents and workflows remain first-class paths. Hardware-aware resource policy, battery/thermal protection, inference admission, Endurance operating modes, bounded calibration and native prefix-reuse safeguards are preserved. No vendor llama.cpp changes or measured battery/performance gain are claimed.

## 🧪 Build and compatibility

- **Clean build successful in 1m 48s:** `clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`.
- **909 JVM tests across 92 classes: zero failures, errors or skips.**
- Package **`com.nyx.ai`**, versionName **`0.8.7`**, versionCode **20**.
- **ARM64-only**, minimum API **26**, compile/target API **37**.
- APK signature verification and **16 KB ZIP alignment passed**.
- Established signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`.
- Primary APK SHA-256: `4491df96e646ce24a8e7a92c931c48cc00c1b6e685ef4f43e4f121b0f47d96a7`.

The release contains the installation APK, compiled instrumentation APK, clean source ZIP and SHA-256 manifest. Back up important data, then install over a compatible NYX installation without uninstalling; the signing identity is unchanged. No AAB, Play submission, private signing material or model weights are distributed.

## 📋 Known limits and device checks

The reported Pixel foldable keyboard defect and extremely slow qwen planning are **not confirmed fixed on physical hardware**. Small character counts do not identify queueing, cold load, prompt evaluation, CPU, memory, thermal or PRoot bottlenecks. No hardware speedup, battery gain or real-model completion-rate improvement is asserted.

Instrumentation compiled but was not executed. Native GGUF, Android/Termux/PRoot concurrency, long service allowances and process-death recovery require hardware validation. Custom stage values follow the canonical policy rather than independent per-stage UI controls. Unrestricted incremental planning and generalized interactive terminals were not added.

Read the [implementation report and device procedure](https://github.com/ShamelesAbyss/Nyx/blob/v0.8.7/V087_IMPLEMENTATION_REPORT.md), [test results](https://github.com/ShamelesAbyss/Nyx/blob/v0.8.7/TEST_RESULTS.md) and [user guide](https://github.com/ShamelesAbyss/Nyx/blob/v0.8.7/USER_GUIDE.md).

---

**Your Intelligence. Your Device. Your Rules.**

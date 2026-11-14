# Project Bedrock — acceptance procedure and provider matrix

**Final parent verification (2026-11-14): clean BUILD SUCCESSFUL in 2m 21s; 1,182 JVM tests across 132 suites, zero failures/errors/skips, with real-network acceptance enabled. ARM64 app and instrumentation APKs assembled; independent package/version/SDK, established signer, notice, 16 KB ZIP and ELF checks passed. Instrumentation and Android hardware were not executed; hosted CI and real Ollama remain unverified.**

**Status at 2026-11-14 synthesis: Android/device acceptance UNVERIFIED; actual production non-UI host acceptance is recorded.** Terminal 99/99 and combined 11/11 pass with real compiler/behavioral output, snapshot and engine-owned store/provenance/checkpoint/completion. Provider/Context/file-backed DAO/transaction seams are fixtures, not Android/Room/Binder/Termux/Ollama. Two production Kotlin compiles were reported; the final parent clean build is running/pending. No final candidate signer/alignment or physical-device pass is credited here. Device sections below remain procedures, not execution records. See [security dispositions](../BEDROCK_SECURITY_AUDIT.md) and [build/CI contract](CI_VERIFICATION.md).

## 1. Entry gate: source freeze, host regressions and real artifacts

Only the coordinating parent runs Gradle, after all owners finish. Use `/home/ubuntu/nyx-bedrock`; never write outputs to `/home/ubuntu/nyx`, `/home/ubuntu/Nyx-publish`, toolchains, model storage or signing directories. Preserve AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.2.10 / KSP 2.3.6 / Room 2.8.5 / Java 21 with JVM17 bytecode / min26-target37 / Build Tools36.0.0 / NDK28.2.13676358 / CMake3.22.1. No API fallback, AAB, publication, remote settings or signer replacement.

### Current host acceptance record

The terminal fixture/old expectation and existing Kotlin JAR hardlink-overwrite gates are repaired; the combined regression classes now exist and execute. Latest config readers, original-scope display, recovery failure subscription and export retry/local-versus-destination wording are implemented. This replaces the previous missing/uncompiled handoff, not Android acceptance.

| Retained record | Actual result and scope |
|---|---|
| `/home/ubuntu/bedrock-terminal-tests/results.log`, `real-kotlin-evidence.json`, `verification-manifest.txt` | **99 passed**. Actual Kotlin behavioral TEST1→checkpointed repair→BUILD0→TEST0→43-byte/two-assertion snapshot. Static hardlink/symlink/private/env/schema/probe controls passed; earlier 23-failure and two-failure logs remain. |
| `/tmp/nyx-bedrock-combined.HboCqE5p/results.log`, `acceptance.json`, `source-inputs.sha256` | **11 passed**. Actual current non-UI engine/runner/development/terminal/store with fake provider/Context/file-backed DAO/transaction. Failed Kotlin BUILD/TEST→repair→BUILD0→three Java assertions/TEST0→31-byte selected snapshot→retention/reopen/provenance/checkpoint/witness→engine completion. Actual initial→next→controlled interruption→reopen→DONE unique task IDs and bounded Full caller tests pass. |
| Earlier independent scoped records | Capability106, network106, integrity71, lifecycle22 and native56 passed within their stated scopes. Native includes fresh Linux JNI against one immutable real GGUF, not ARM64/real Ollama. Counts overlap; never sum them. Integrity baseline five failures and native earlier two fixture failures remain retained. |
| `/home/ubuntu/bedrock-lifecycle-tests/ollama/` | Seventeen new persistent-ownership tests passed; accompanying existing 71-test run had one old bash-versus-env-i fixture failure, corrected by parent with final rerun PASSED in the complete 1,182-test suite. Pending token/checkpoint fence is logical host evidence, not Android preferences disk durability. |
| Parent whole-app gate | Two Kotlin compilation passes reported. First whole-suite attempt: **1182 tests, six failures, two skips**, not success; six precise expectation/fixture issues repaired without weaker policy. Direct throttled Ollama/SSE reasoning methods passed that attempt. Final clean build with `NYX_REAL_NETWORK_TEST=1` is **pending**, and final candidate artifact gates remain unverified. |

Combined generated/retained SHA-256: `3cd210ba339a2dfbabf5d457876196474b1c2b57ba43fe6dab675aa58ba7f22d` (31 bytes). Terminal-only snapshot SHA-256: `1adb25ef922e29ecf8b665c6887c1f4af336145af561fe5270736d0438bdb91c` (43 bytes). These are different fixtures, not interchangeable counts/payloads. See the [final repair reports](bedrock/README.md) and [implementation report](../V090_IMPLEMENTATION_REPORT.md).

Remaining source-level citation restriction: exact selected URL tokens are checked only in required MD/TXT/HTML; unsupported JSON/CSV/ZIP-only report linkage and factual semantics remain unresolved. Source/UI improvements still require device observation.

After source freeze, the parent may reproduce the available isolated harnesses (read-only surrounding API class fixtures must be logged, not mistaken for clean current app classes):

```bash
set -euo pipefail
cd /home/ubuntu/nyx-bedrock
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/Android/Sdk
export KOTLINC=/tmp/nyx-toolchain-tests/kotlin-home/bin/kotlinc
export PATH=/tmp/nyx-toolchain-tests/kotlin-home/bin:$JAVA_HOME/bin:$PATH
export PYTHONDONTWRITEBYTECODE=1 CMAKE_BUILD_PARALLEL_LEVEL=2
# Retained runners hard-code OUT: copy ONLY the runner into a new owned directory,
# rebasing its output constant; never execute the original into existing evidence.
CAP_OUT="$(mktemp -d /tmp/bedrock-capability-acceptance.XXXXXXXX)"
sed "s|^OUT=.*|OUT=$CAP_OUT|" /tmp/nyx-bedrock-capability-20261114/run.sh > "$CAP_OUT/run.sh"
bash "$CAP_OUT/run.sh"
INT_OUT="$(mktemp -d /tmp/bedrock-integrity-acceptance.XXXXXXXX)"
sed "s|^OUT=.*|OUT=$INT_OUT|" /tmp/nyx-bedrock-integrity-20261114/run-host-check.sh > "$INT_OUT/run-host-check.sh"
bash "$INT_OUT/run-host-check.sh" current | tee "$INT_OUT/current-results.txt"
bash tools/bedrock_network_regression.sh
# Give lifecycle a NEW directory; never overwrite the owner's existing evidence.
BEDROCK_LIFECYCLE_OUT="$(mktemp -d /tmp/bedrock-lifecycle-acceptance.XXXXXXXX)" \
  bash tools/bedrock_lifecycle_host_tests.sh
# Native runner creates independent outputs. Omit NYX_REAL_GGUF for fake/synthetic-only tests.
ANDROID_JAR="$ANDROID_HOME/platforms/android-37.0/android.jar" \
  bash app/src/test/java/com/nyx/ai/nativeinference/bedrock_host_verify.sh
# Current combined classes exist; the runner creates independent outputs:
bash app/src/test/java/com/nyx/ai/terminal/bedrock_terminal_host_verify.sh
bash app/src/test/java/com/nyx/ai/workbench/bedrock_combined_host_verify.sh
```

Capability/integrity retained runners are campaign artifacts, not portable source-only tooling. Check each runner's output contract before reusing it; select new outputs where supported or make an independently named runner, never overwrite existing failure evidence. Real-model host acceptance is opt-in only against an authorized existing immutable GGUF; record its hash and exact fresh library provenance separately. No real Ollama required for deterministic regressions.

Parent-owned final clean build/gates, **pending and not executed by this author** (the running parent job must not be duplicated):

```bash
set -euo pipefail
export NYX_REAL_NETWORK_TEST=1
bash tools/build_release.sh --preflight
./gradlew --no-daemon --max-workers=2 --console=plain \
  clean testDebugUnitTest assembleDebug assembleDebugAndroidTest \
  :app:dependencies --configuration debugRuntimeClasspath
VERIFY="$(mktemp -d /tmp/bedrock-final-verification.XXXXXXXX)"
bash tools/build_release.sh --verify-built "$VERIFY"
# --package requires an entirely new/nonexistent delivery directory.
DELIVERY="/home/ubuntu/bedrock-delivery-$(date -u +%Y%m%dT%H%M%SZ)-$$"
test ! -e "$DELIVERY"
bash tools/build_release.sh --package "$DELIVERY"
```

Actual SDK/NDK output must prove app `com.nyx.ai`/code23/name0.9.0/min26/target37, test package/target binding, valid single matching established signer, ARM64-only required `libnyx_llama.so` and `libc++_shared.so`, uncompressed native files, every ELF LOAD >=16KiB with congruent offsets/addresses, both APK ZIP alignment and exact shipped notices. Established certificate SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. CI ephemeral debug keys are not upgrade candidates. Byte-compare source archive/internal manifest/delivery hashes after final docs/source changes; source freeze changes make old archives stale.

## 2. Device record and safe upgrade

Use an explicitly authorized disposable/test device or isolated emulator profile. Test at least one supported older API, Android15+ foreground limits, API37 LAN permission behavior, and **physical ARM64 16KiB pages**. A 4KiB device or emulator alone cannot close 16KiB runtime acceptance. Record each unavailable cell UNVERIFIED; do not assert all API levels passed from one device.

Create a new host evidence directory. Set the exact selected device serial; never use ambiguous default-device commands:

```bash
set -euo pipefail
OUT="$(mktemp -d /tmp/bedrock-device-evidence.XXXXXXXX)"
ADB=/home/ubuntu/Android/Sdk/platform-tools/adb
"$ADB" devices -l | tee "$OUT/devices.txt"
SERIAL='<authorized-device-serial>'
"$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk > "$OUT/api.txt"
"$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abilist > "$OUT/abi.txt"
"$ADB" -s "$SERIAL" shell getconf PAGE_SIZE > "$OUT/page-size.txt"
"$ADB" -s "$SERIAL" shell dumpsys package com.nyx.ai > "$OUT/package-before.txt"
```

Also record manufacturer/build fingerprint, app/APK SHA-256, installed Termux build/version/source, Debian guest alias/version, compiler/JDK package paths, endpoint/provider version/model digest, power/thermal/memory state and exact grants. Do not include keys, raw private prompts, full credential-bearing endpoint URLs or user files in evidence.

For upgrade continuity, first seed **fixture-only** data under the established v0.8.9 signer: one agent, settings, one objective/checkpoint, workspace mapping, model import metadata and retained Workbench artifact/source/import. Hash fixture payloads and record IDs/settings. Install verified Bedrock with `adb -s "$SERIAL" install -r <verified-primary-apk>`; **do not uninstall, clear app data, use downgrade flags or replace signing identity**. If baseline fixture/device unavailable, mark upgrade UNVERIFIED. Compare post-upgrade package code/name, migrated rows/IDs/settings, existing workspace/artifact/source/import bytes and model hash. Existing private keys must still resolve; never expose them in logs. A signature mismatch is a blocker, not a reason to delete installed data.

Install the verified instrumentation APK only on this test device. Then run the included classes individually and retain full instrument output, test count and failures/skips:

```bash
set -euo pipefail
APP_APK='<verified-primary-apk>'
TEST_APK='<verified-instrumentation-apk>'
"$ADB" -s "$SERIAL" install -r "$APP_APK"
"$ADB" -s "$SERIAL" install -r "$TEST_APK"
for CLASS in \
  com.nyx.ai.settings.InstalledAppVersionInstrumentedTest \
  com.nyx.ai.settings.CredentialVaultInstrumentedTest \
  com.nyx.ai.settings.GatewayCredentialInstrumentedTest \
  com.nyx.ai.workbench.WorkbenchIntegrityInstrumentedTest \
  com.nyx.ai.nativeinference.NativeBedrockRepositoryRecoveryTest; do
  "$ADB" -s "$SERIAL" shell am instrument -w -r -e class "$CLASS" \
    com.nyx.ai.test/androidx.test.runner.AndroidJUnitRunner \
    > "$OUT/${CLASS##*.}.txt" 2>&1
done
"$ADB" -s "$SERIAL" shell dumpsys package com.nyx.ai > "$OUT/package-after.txt"
```

`am instrument` shell exit alone is insufficient: inspect `INSTRUMENTATION_RESULT`, failures/assumptions and actual test count. These tests cover metadata/Keystore/parser/fixture sidecars, **not** all scenarios below. Synthetic GGUF repository recovery test is not inference. Its truncation/deletion operates only within its newly owned cache fixture, never a real model.

## 3. Provider/model compatibility matrix

Separate transport API availability, model evidence, adapter omissions and measured behavior. SUPPORTED requires completed compatible local validation under the actual provider/model/runtime/context/thread/temp/output/schema/think/tool fingerprint. Generic HTTP failures/truncation leave UNKNOWN; only typed explicit feature rejection establishes UNSUPPORTED. Replacing a model/runtime behind the same endpoint requires revision rotation/invalidation. Observers are not identity; credentials never enter fingerprints.

| Provider / model case | Current implementation / scoped evidence | Live/device status and required check |
|---|---|---|
| Deterministic schema-honoring fake provider | E-CAP helpers and E-COM current production continuation/Full callers passed. Strict budgets/authority and next/done grammar exercised. | **Production host caller PASS; Android/Room UNVERIFIED.** Actual initial→next→interruption→file-backed fake DAO reopen→DONE preserves IDs/profile/ordinals without replay; Full profile/budget tests stop before tasks execute. Never label fake provider as Ollama. |
| Deterministic limited/prose/schema-ignoring fake provider | UNKNOWN defaults, incomplete/typed reject distinctions, inference-only Guided contract; no schema-enforcement claim. | **Host policy and exercised production text-action runner PASS; live/native fallback integration UNVERIFIED.** Malformed/unauthorized/prose actions do not promote support or mutate artifacts; valid text action records exact outgoing identity. |
| Fake local HTTP/DNS/auth/reasoning streams | E-NET transport, E-CAP stream policies and E-COM no-activity runner passed. Parent direct throttled Ollama/SSE methods passed in the first failed whole-suite attempt; final clean rerun PASSED; see final parent verification above. Public tools cannot inherit selected local inference authority. | **Host PASS; not real provider/hardware.** Reproduce mixed DNS/redirect/credential and reasoning-first/inactivity/hard-deadline cases. |
| Ollama on selected same-device Termux loopback | Adapter API can request tools and constrained JSON; model support stays UNKNOWN until completed exact-request evidence. HTTP actual loopback allowed, no bearer for Ollama route. | **Real Ollama/Android UNVERIFIED.** On later authorization test one installed tool-capable model and one limited model; capture native calls/text fallback/schema requested versus honored/reasoning/stats separately. No ecosystem/name shortcut. |
| Ollama on selected RFC1918 / IPv6 ULA LAN | HTTP prompts allowed only by explicit endpoint selection and actual local DNS/route; internet HTTP refused. HTTP bearer credentials outside loopback refused. | **Real LAN UNVERIFIED.** Permission grant/deny/revoke, LAN DNS switching to public, error diagnostics, no redirects and no leaked prompt/key. Global platform cleartext is not a firewall. |
| OpenAI-compatible HTTPS `/v1` endpoint | Header bearer transport with normal TLS; no automatic redirects. Tool API availability is not model evidence. Current adapter does not request constrained schema. | **Live endpoint UNVERIFIED.** Tools/text/reasoning/stats each measured per endpoint/model; constrained-schema support **UNKNOWN/not requested**, not universal UNSUPPORTED. Test malformed/401/403/500/truncated responses and bounded fallback. |
| OpenAI-compatible selected actual loopback HTTP | E-NET direct-client fake server proves permitted local header auth; locality/DNS/route checked. | **Live/device UNVERIFIED.** No key-bearing LAN/public HTTP or endpoint userinfo/query/fragment. Keep endpoint-specific consent; public fetch must still refuse it. |
| Native GGUF, SmolLM2-135M-Instruct-Q4_K_M, architecture `llama` | E-NATIVE fresh **Linux x86_64/Java21 real GGUF PASS**: load/generate/cancel/replay/callback error/double unload; 513 usable→768 allocated with measured prompt rejection. Model SHA `2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d`. | **Android ARM64/16KiB UNVERIFIED.** Adapter explicitly rejects native tool fields and has no constrained-schema grammar API. Validated text actions still require actual local evidence. Host result is one architecture/model, not the GGUF ecosystem or throughput benchmark. |
| Other GGUF architectures/quantizations/templates/context sizes | Strict parser/admission and pinned runtime scope; no acceptance claim by model name. | **UNVERIFIED.** Use only authorized immutable imports and supported architecture metadata; record parser/admission outcomes, usable/allocated limits and real replies/cancellation individually. |
| MCP/GitHub gateways | Gateway query/userinfo/fragment/reference/input rules and fake credential provider regressions E-NET passed. | **Live gateways/Android Keystore integration UNVERIFIED.** Vault exact gateway:<id> roundtrip; configured missing token refuses request. No combined objective inherits gateway approval. |

For later authorized real-provider runs record server version, exact model identifier/digest, cold load time, prompt tokens/eval duration, first actual reasoning/first text latency, generation tokens/duration and observed throughput, request ceiling, final done marker and every effective option. Absent provider counters stay unknown; reasoning characters are not tokens. Reasoning may refresh activity, never answer-text metrics or an unlimited request deadline. Do not increase timeouts to disguise slow inference, disable thinking from observation alone, or claim native cancellation from coroutine completion. Direct OllamaClient timeout-enabled calls now use the effective-options activity watchdog too; final clean suite remains pending. No activity can evade the finite hard ceiling.

## 4. Real combined development → retained artifact acceptance

The **production non-UI Linux host chain PASSED** as recorded above. This section is the still-**UNVERIFIED Android/Termux/Room/Binder** counterpart. Test the existing ObjectiveExecutionEngine/DevelopmentOrchestrator, not a separate orchestrator or script that writes a JSON claiming success. The example below is a later device fixture, not the exact 31-byte/three-assertion host fixture.

In a new Termux/guest project directory (not another project/private/model directory), seed the following simple Kotlin fixture using already installed explicitly trusted compiler/JDK. Creation/editing affects only this fixture:

```kotlin
// Main.kt (deliberate bug)
fun answer(): Int = 41
```

```kotlin
// TestMain.kt
fun main() {
    check(answer() == 42)
    check(answer() + 1 == 43)
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("build/result.json"),
        "{\"passed\":true,\"assertions\":2,\"answer\":42}\n"
    )
    println("BEHAVIORAL_ASSERTIONS=2")
}
```

1. Approve that exact canonical environment/guest/project and default-off **combined development** capability, selecting only `build/result.json` → `result.json` / JSON. Approval must disclose trusted project code has Termux/guest authority and may copy secrets into outputs; PRoot is not OS isolation. No general shell, install/network/gateway/publish approval.
2. Ask the existing objective flow to repair `answer()` to42, build, test and deliver the selected generated result. Record initial failed behavioral test, actual changed source/stale hashes, real fixed compiler/Java commands, real successful final ordered BUILD→TEST, unique operation/task/objective IDs, results/exit/infrastructure kind and timestamps. A model JSON `exitCode:0`, artifact API success, inspection or build-only result must not satisfy it.
3. Compare exact generated result bytes with terminal snapshot, registered artifact and post-reopen/export bytes/digests independently. Expected payload is the test-written JSON above. Require engine-owned registration-kind witness from a completed task and persisted terminal provenance/checkpoint. If durable checkpoint fails, no successful witness/completion; retained uncompleted bytes must not be relabelled complete.
4. Pause after source change or after test; switch current UI project/guest/environment, then resume. The original immutable selection must remain. Global terminal opt-out blocks, inaccessible saved project requires attention, uncertain child reservation prevents blind replay.
5. Repeat denial cases in independently owned disposable fixtures: no combined grant; missing/unselected output; traversal/absolute/private names; symlink file/ancestor, hardlink or special file; oversize/changing bytes; forged Base64/size/hash/operation/task/objective/scope; failed/missing/unknown/cancelled/timeout-like build/test; successful artifact tool after failed command; selected destination model write/bundle forgery; eight declarations plus failed mandatory digest.
6. Legacy/artifact-only Workbench must stay terminal-disabled. Inference/filesystem tasks cannot invoke fixed build/test merely because tools exist. Keep mandatory checks separate from eight declared checks. Reopen artifact and source evidence and check checkpoint/witness ownership, not UI prose.

Host tests may mock inference/Android lifecycle seams only; actual compiler/Java behavior, production snapshot/registration and completion policy must run. If host cannot execute real Room/service path, state exactly which policy path ran and require this device chain. `BedrockFixedDevelopmentTest` executed real terminal-only compiler/behavior/snapshot acceptance (99-test scope); `CombinedDevelopmentBedrockTest` additionally executed the actual production engine/store/witness chain (11-test scope). Neither is Android/Room acceptance.

## 5. Security, concurrency and interruption checks

Use fixture-only sentinels/processes. Never send real destructive/publish/privileged requests, read unrelated private files, signal adopted processes or alter user/model/credential data.

| Scenario | Procedure / required evidence | Pass condition |
|---|---|---|
| Structured terminal authorization | Through actual model-tool route submit marker-only executable override/PATH assignment, awk/sed execution, Git global-option/alias/config forms, unsupported options, malformed JSON/null/type/bounds, traversal/private/link operands. Run legitimate fixed readers/project build separately. | Rejected requests never dispatch to IPC; no outside sentinel changes or marker program execution; allowed readers have controlled absolute argv/task binding. Permission weakening is a failure. |
| Three-worker overlap | Start three approved independent fixture processes with controlled gate and separate stdout/stderr; one exits7, others0. Record distinct operation IDs and overlapping actual live intervals, not just queued tasks. Cancel one callback wait. | Three live contexts overlap when resources allow; outputs/exits remain separate; siblings not signalled. Cancelled external ownership remains UNCERTAIN, not "stopped". |
| Workspace conflict | Gate a write lease on project A, request nested/conflicting writer plus unrelated project B; retain interval/admission records. | A conflict serializes/refuses; B progresses independently; dependencies/resource cap honored. Cross-environment unknown mappings remain conservative. |
| Timeout/escaped child | Test-owned marker-only child in separate session with independent PID/start identity and redirected output; timeout wrapper, record124 and surviving child. Cleanup only fixture handles. | Wrapper exit does not free conflict reservation or justify automatic replay; unknown/null/124/137 retains uncertainty. Android behavior must be separately observed from existing Linux fixture. |
| Process death/recovery | In disposable app/Termux fixtures interrupt during inference, terminal wait, source commit, research fetch and export. Use force-stop/reopen only with test-device authorization. Retain before/after operation/lease/checkpoint/file/provider bytes. | No invented task success, duplicate side effect, automatic replay or sibling kill. Initially live lease eventually expires→review in bounded watcher; failure diagnostic visible. Exact rename/fsync death requires explicit fault injection; post-created orphan tests do not prove it. |
| Persistent host launch/stop | **LIF-06 MITIGATED, runtime UNVERIFIED.** Pending token/namespace fence and unknown-stop refusal pass 17 host tests. On the authorized device inject checkpoint/dispatch faults and observe adopted/mismatched/token/PID/start identity; unresolved fences may remain stuck. | No blind relaunch, missing stop identity remains unresolved, no signal to unowned process. No naked PID/session-tree kill or automatic reset/relaunch is permitted. Real preferences disk and PRoot release evidence remain unverified. |
| Research retention/citations | Use authorized retained local fixtures first, malicious delimiter/role text, same-size corrupt source, missing/foreign/matching/multi-URL citations and unsupported report formats. Later public source access only with explicit URL authorization. | Text never grants authority; corruption retry fails without overwriting evidence; report references link to readable approved source bytes or explicit review. Linked URL/checksum/valid format are not factual proof. |

Force-stop is not power loss or proof an external Termux child stopped. Recovery should remain review-only. Current source subscribes to recovery failure and shows a review diagnostic; verify that UI behavior on device. Mark missing diagnostics as failure/incomplete, not invisible success.

## 6. Android networking, services and file handoff

### Network and credential acceptance

Configure only selected endpoints. Verify remote public HTTP inference refusal, selected actual loopback/LAN HTTP compatibility, HTTPS OpenAI header auth, no HTTP bearer outside loopback, unsafe URL/query refusal, DNS mixed/private/public switching, every redirect's full scheme/port/origin policy and final-route checking. Public tools must emit no HTTP request to local inference/emulator host/private services. Compare fixture server request logs, not just UI messages. Never log real credentials.

On API37 exercise LAN permission via normal UI grant, deny, revoke and regrant; no broad storage/root/accessibility permission. Observe same-device loopback separately rather than assuming exemption. Refusal/timeouts must be actionable and must not widen authorization. Preserve normal TLS; self-signed test endpoints need correctly configured legitimate trust, never disable hostname/certificate validation. Inspect merged/installed manifest: only launcher exported among intended app components, services/receiver/FileProvider non-exported, FileProvider paths limited to handoff subtree, backups disabled, no permission/type widening.

Run exact gateway logical-reference vault roundtrip/tamper/read/delete instrumentation using dummy tokens. Existing key alias/reference-bound AES-GCM AAD/simple reference filenames/signing stay unchanged. Device Keystore security level is recorded if inspected; hardware-backed protection must not be assumed.

### Foreground service and lifecycle acceptance

Using actual UI/service controls, test notifications allowed/denied, background start refusal, START twice then failure/natural end, rapid pause/cancel→START, STOP/RESTART predecessor chains and foreground notification cleanup. Old finalizers/watchdogs must not cancel/update replacement run. Attempt denied/live-other-owner lease start; no other-owner task/status/lease mutation. Inject final checkpoint failure; owner evidence retained conservatively.

For Android15+ dataSync timeout/budget testing, use the platform-supported test mechanism appropriate to that specific OS on a disposable device, retaining any changed test setting and restoration. Do not change service types/permissions to evade limits. If such tooling or a suitable device is unavailable, record UNVERIFIED. No store/Play compliance is asserted by matching manifest types.

### Preview, export and share acceptance

1. Write/register fixture MD/TXT/CSV/JSON/inert HTML/ZIP using production validators. Verify current full record/digest/bytes; stale/forged record refused. Preview remains inert selectable text; imported/fetched HTML must not run active content or navigate automatically.
2. Save using real picker to a fixture document provider granting explicit readback, then independently read/hash the selected destination. Distinguish **verified local snapshot + completed stream** from actual destination readback; cloud durability/atomic replacement is not universal.
3. Inject open/write-after-N/flush/close failure and process interruption in the fixture provider; destination may be partial, local artifact unchanged/reopenable, retry available. Never delete/restore arbitrary selected destination automatically. Current source retains retry selection on failure and reports local snapshot/stream completion, not destination readback/durability; validate actual UI/provider behavior.
4. Share to a controlled recipient app: exact bytes readable with ClipData/read-only URI grant; foreign/no-grant app refused, write refused, FileProvider cannot reach models/credentials/other paths. Cancel chooser and observe fresh cache not prematurely revoked; full-fresh-cache admission fails; expiry removes only owned snapshots. No URI-permission widening.
5. Quota/corruption tests operate only fixture-owned files: invalid numeric manifest fields, duplicate corrupt source, ZIP file/ancestor/case/trailing-dot conflicts, interrupted private atomic writes and locked multi-instance access. Preserve failure evidence; never repair user data by deleting invalid manifests.

## 7. Native ARM64 acceptance

With explicit later authorization use an existing immutable app-readable instruct GGUF or a fixture-owned copy imported through supported selection. Record before/after model digest. Never modify real weights/sidecars as a fault test; corrupt-sidecar instrumentation uses synthetic fixture files. Opt-in command:

```bash
set -euo pipefail
MODEL_PATH='<explicit-authorized-app-readable-instruct.gguf>'
"$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class com.nyx.ai.nativeinference.NativeRealModelTest \
  -e modelPath "$MODEL_PATH" \
  com.nyx.ai.test/androidx.test.runner.AndroidJUnitRunner \
  > "$OUT/native-real-model.txt" 2>&1
```

Without modelPath this test **assumption-skips**, not a native inference pass. Real test requires nonempty streamed reply, cancellation propagation, replay/coroutine cancel/cache reset and double unload. Separately exercise nonaligned513→768 allocation with usable513/training limits, inactive-model deletion not cancelling active inference (active deletion waits), model-switch cancellation epoch, app memory-pressure/process death, usable-limit rejection followed by successful generation, and physical16KiB load. Record actual token/character counters and observed cancellation latency; one successful model is not all architectures/quantizations or low-memory survival.

## 8. Completion record and ranked 1.0 blockers

For every scenario write: source/APK/model hashes; device/provider/runtime/options; real commands and exact fixture ownership; expected versus observed result; raw output with secrets redacted; executed/failed/assumption-skipped/not available; finding disposition and remaining limit. Do not transform host PASS into Android PASS or a missing run into a skipped/pass assertion. Failures and unexecuted boundaries remain part of the record; all final repair reports now exist.

Ranked open blockers:

1. **HIGH impact verification:** parent final clean whole-app/native/APK/instrumentation build and exact candidate signer/ABI/16KiB/notices/source/checksum gates remain pending; do not reuse the failed first suite as success.
2. **HIGH impact platform:** Android/Termux/Binder/Room/service/three-worker/permissions/upgrade/export-share/physical16KiB acceptance remains absent despite actual production host acceptance.
3. **MEDIUM:** persistent token-fence runtime durability and safe reconciliation of stuck uncertain sessions; never naked-PID kill, automatic reset or blind replay.
4. **MEDIUM:** same-address/model runtime revision invalidation (native remains default empty), real provider/model schema/tool/think/reasoning/stats and measured performance; Full caller fixtures are not whole-DAG or cold discovery qualification.
5. **MEDIUM:** unsupported report-format citation semantics and factual support; executed UI/recovery/config/export retry and actual destination readback/durability.
6. **MEDIUM residual architecture:** same-UID/trusted project code is not isolated; authorized LAN cleartext app gates are not a firewall; private/journal/power-loss/SAF durability and global storage growth remain unverified.
7. **MEDIUM engineering/distribution:** hosted Actions/maintainer required checks and future explicitly authorized production-signing migration. No publication, remote/Git changes, AAB, Play or v1.0 work occurs under this documentation preparation.

A local candidate may only be described by the evidence actually obtained. Dependable Android autonomy, hardware compatibility, release integrity and factual verification remain separate claims requiring their own successful observations.

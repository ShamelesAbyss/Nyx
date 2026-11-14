# NYX 0.9.0 — Project Bedrock

**Final parent verification (2026-11-14): clean BUILD SUCCESSFUL in 2m 21s; 1,182 JVM tests across 132 suites, zero failures/errors/skips, with real-network acceptance enabled. ARM64 app and instrumentation APKs assembled; independent package/version/SDK, established signer, notice, 16 KB ZIP and ELF checks passed. Instrumentation and Android hardware were not executed; hosted CI and real Ollama remain unverified.**

**Published stable release · versionCode 23 · `com.nyx.ai` · 2026-11-14.** This is a security/reliability engineering update, not Android hardware acceptance or production-signing approval. The clean build and independent artifact gates passed; physical-device and real-provider behavior remain unverified.

## Implemented changes

**Bounded development → selected artifact.** A default-off Workbench combined approval binds the original objective, canonical project, environment/guest and exact generated text/JSON/CSV/static HTML selection. The existing ObjectiveExecutionEngine and DevelopmentOrchestrator inspect/repair source, require actual final successful ordered BUILD then behavioral TEST, snapshot selected bytes, register them through the private store, and checkpoint provenance before publishing a completion witness. Changing the current project cannot redirect saved approval; global terminal opt-out blocks resume. Legacy artifact-only Workbench stays terminal-disabled. No general shell, package installation, gateway/network grant, JAR/APK/PDF or combined ZIP output is added.

**Terminal authority and identity.** Model terminal commands use a small validated argv catalog rather than arbitrary executable/program/Git syntax. Controlled operations sanitize the environment before shell startup; canonical/single-link/source/output/probe checks reject tested traversal, private paths, symlinks and hardlinks, including existing Kotlin JAR overwrite targets. Task-bound command identity and actual script digests preserve auditable dispatch scope. Explicitly trusted project code remains code execution, not a read-only operation or OS sandbox.

**Honest capability/planning evidence.** Fresh/untested support is UNKNOWN. Effective provider/model/runtime/options/tool/schema fingerprints and per-feature expiry prevent unrelated evidence borrowing. Only completed locally validated actions promote support; incomplete/generic transport failures do not establish unsupported features. Reasoning refreshes bounded request activity without becoming answer text or proving `think=false` acceptance. Initial/continuation schemas now agree with parsers; checkpointed continuation ordinals preserve unique task IDs. Full uses one bounded full-DAG contract and exact evidence prerequisites, not a compact schema with a larger label.

**Conservative recovery and integrity.** Service generations/leases own cleanup/checkpoints; timeout-like external results retain uncertainty rather than proving shutdown. Persistent hosting synchronously checkpoints a pending token/guest fence before dispatch and refuses unknown/adopted/mismatched stop identity; it does not blindly relaunch or kill a naked PID. Store numeric/source/ZIP checks, local-before-export verification, partial-destination warnings and retry wording are tightened. Public HTTPS tools are separate from selected inference origins; DNS/connected-route/redirect/credential/gateway validation and untrusted-text escaping are enforced at application boundaries.

**Build/release hygiene.** A pinned read-only verification workflow and checked-in build/independent artifact gates are prepared. Obsolete v0.2 root APK/source bundles are retired from the current tree without rewriting history. Native/NDK notices are explicit assets. Existing Apache-2.0 license, pinned toolchain, package, permissions and established debug signing identity remain; production-signing migration is deferred.

## What actually ran

| Retained evidence | Outcome and scope |
|---|---|
| Terminal host suite | **99 passed**. Actual Kotlin behavioral TEST1→checkpointed repair→BUILD0→TEST0→43-byte selected snapshot; static link/environment/schema/probe refusal controls. Not Binder/Android. |
| Combined production non-UI host suite | **11 passed**. Actual engine/runner/development/terminal/store with fixture provider/Context/file-backed DAO/transaction. Failed Kotlin BUILD/TEST→repair→BUILD0→three Java assertions/TEST0→31-byte snapshot→registered/reopened bytes/provenance/checkpoint/witness→engine completion. Initial→next→controlled interruption→reopen→DONE and bounded Full callers also ran. Not real Room/Termux/Ollama; Full tasks were not executed. |
| Earlier independent scopes | Capability106, network106, integrity71, lifecycle22 and native56 passed; persistent-host follow-up17 passed. Native includes fresh Linux x86_64 JNI against one immutable real GGUF. These overlap and are **not a whole-app sum**. |
| Whole-app gate | Two production Kotlin compiles reported. First suite attempt **1182 tests, six failures, two skips**; precise expectation/fixture corrections made, final clean rerun with `NYX_REAL_NETWORK_TEST=1` PASSED. New direct throttled Ollama/SSE reasoning methods passed that first attempt, not an overall suite pass. |

Historical failures remain retained. Instrumentation/permission/migration compilation is not Android execution. Final current-source totals and real candidate artifact verification belong in [TEST_RESULTS.md](TEST_RESULTS.md), not inferred from these scoped passes.

## Known limits and next gates

Android/Room/Binder/Termux/PRoot services, permission grant/deny/revoke, upgrade, URI grants/SAF readback and physical ARM64 16KiB JNI remain **UNVERIFIED**. Real Ollama/OpenAI and broader GGUF/model/performance qualification remain unverified. Native capability revision is still default empty; same-address/model replacement needs explicit revision/invalidation. Persistent uncertainty fences can remain stuck after external cleanup; no automatic reset/replay was added.

Selected URL tokens link required MD/TXT/HTML reports to retained URLs but are not factual/semantic proof; JSON/CSV/ZIP-only citation semantics remain unresolved. Generic Android export destinations may be partial/non-durable despite verified local bytes. Global cleartext permission remains application-gated for authorized LAN compatibility, not a firewall. Same-UID/trusted project code can access existing network/secrets and copy them into otherwise allowed outputs; link/checksum guards do not remove that risk.

See the [implementation/evidence report](V090_IMPLEMENTATION_REPORT.md), [all original security dispositions](BEDROCK_SECURITY_AUDIT.md), [repair report index](docs/bedrock/README.md), [device/provider acceptance procedure](docs/BEDROCK_DEVICE_ACCEPTANCE.md) and [build contract](BUILD.md). No commit/tag/push, publication, AAB, Play work, vendor/model/user-data/credential mutation or protected-baseline changes were performed by this documentation synthesis.

## Final reproducible package verification

App APK SHA-256: `bcac425f9e9680fa04e059be76cceaabecc404727070344c5153ab0da5aec6c5`. Instrumentation SHA-256: `eccbc53bb3839b9f5f2e172421023bce6aeac96b67836ec3e66bfeaa8e85319b`. Established signer SHA-256: `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. Source ZIP digest is external to avoid self-reference. `tools/build_release.sh --verify-built` passed on the real APKs. Parser regressions now cover actual aapt2 minSdkVersion wording and intentionally empty instrumentation version metadata, rejecting ambiguous SDK or missing app version. The earlier gate failures were verifier-format defects, not APK metadata changes.

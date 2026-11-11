# NYX 0.3.0 — Final Development Report

## Summary

NYX 0.3.0 continues the existing native Kotlin/Compose/Room Android app (application id `com.nyx.ai`, versionCode 3). It preserves Ollama chat and stored user data, and completes the agent-editing work that was interrupted. This pass focuses on failures the owner specifically called out: Ollama HTTP 400 responses during function/tool calls, models without native tool support, useful but bounded diagnostics, connection reuse, duplicate inference races, and phone/fold state continuity.

The upgrade is meaningfully improved but does **not** complete every item in the original broad roadmap. The missing native inference, posture-specific foldable, endpoint-profile, document-library, and template-builder work is listed below rather than represented as complete.

## Completed and improved work

- **Branding:** kept the existing dark violet NYX artwork across adaptive launcher, themed monochrome icon, splash, and in-app mark.
- **Chat and layouts:** retained chat/history management, streaming, cancel, copy, regenerate, and width-responsive navigation/workspaces. Current section and unsent composer text are saveable across Activity recreation. The visual identity was intentionally not redesigned after the owner's positive Pixel Fold feedback.
- **Ollama/OpenAI compatibility:** HTTP status failures now surface at most 600 sanitized response characters, with the configured API key redacted. Requests share one process-wide OkHttp client/connection pool and retain connection/read/call timeouts, disabled redirects, and coroutine cancellation.
- **Tool-call resilience:** recognizable HTTP 400 errors that explicitly indicate unsupported native tools become a typed capability error. The agent emits a warning and retries once without tool schemas, with instructions not to request tools or pretend to have executed them. Other request failures remain visible with bounded diagnostics.
- **Agent usability:** existing agents/templates can be edited for name and system instructions while preserving their configured tools. Execution warnings are visually distinct. Existing calculator, private memory, planning, app-private workspace, user-approved text import, and sequential workflow behaviors remain bounded.
- **Inference scheduling:** chat, agents, and workflows use an atomic single-inference reservation, preventing overlapping local-model runs even while Room preparation is suspending. Cancellation still propagates to the HTTP call.
- **Device awareness:** the selected Ollama model can show a rough warning when its reported file size approaches current free RAM. It explicitly states this is not a runtime fit prediction. Existing Android/ABI/RAM/thermal snapshots remain advisory only.
- **Security and storage:** existing AES-GCM Android Keystore credential vault, opaque database reference, no-backup ciphertext, HTTPS requirement for remote API-key use, URL validation, external endpoint consent, and no shell/unrestricted filesystem tools are retained. No Room schema change was made.

## Original ten upgrade priorities — status

| Priority | Status | Delivered / remaining |
|---|---|---|
| 1. Branding and identity | **Complete for this upgrade** | Supplied NYX artwork and matching launcher, monochrome, splash, and in-app brand mark retained. |
| 2. UI/UX and chat polish | **Partial** | Existing chat controls, error banner, streaming, clearer agent warnings, inset handling, and adaptive navigation retained. Full Markdown/code rendering and broader accessibility/usability review remain. |
| 3. Foldable optimization | **Partial** | Width-responsive phone/expanded layouts, chat history pane, agent/workflow split workspaces, and saveable section/draft state. Hinge/posture-aware placement and direct v0.3 Pixel Fold testing remain. |
| 4. Model discovery/device awareness | **Partial** | Ollama and OpenAI-compatible discovery/refresh plus endpoint health, device diagnostics, and approximate RAM caution. Named multiple endpoint profiles, richer model metadata, and auto-reconnect/profile management remain. |
| 5. Agent capabilities | **Partial, improved** | Real bounded tool loop, editing, logs/traces, cancellation, and unsupported-tool fallback. Agent-to-agent messaging, advanced permission editor, and guaranteed tool support across models are absent. |
| 6. Documents/workflows | **Partial** | User-selected bounded text import into agent tasks and sequential multi-agent workflows/logs. No persisted document library/metadata, PDF/office parsing, editable workflow template builder, or parallel execution. |
| 7. Performance/efficiency | **Partial** | Batched streaming updates, shared HTTP connection pool, bounded timeouts, cancellation, and exclusive inference. No benchmark suite, sustained battery profiling, or long-session leak test. |
| 8. Tensor/Pixel optimization | **Not implemented as acceleration** | Informational device/thermal diagnostics and conservative RAM warning only. No Tensor TPU, Gemini Nano, NNAPI, GPU, or proprietary acceleration is claimed. |
| 9. Settings/power-user controls | **Partial** | Endpoint/auth controls, consent, inference options, and diagnostics exist. No full endpoint-profile manager, log browser, or comprehensive data-management suite. |
| 10. Tests/docs/delivery | **Build and packaged scope complete; device validation partial** | Five requested Markdown docs plus this report, debug APK, source archive, 9 passing JVM tests, and instrumented-test APK. Keystore/device/live-server tests remain unrun. |

## Build and test results

- `./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest` succeeded; the relevant tasks were rerun successfully after the final UI source change.
- JVM: **9 tests, 0 failures, 0 errors, 0 skipped**.
- Debug APK and Android instrumentation APK compiled successfully.
- Instrumentation tests compile but were not run without a connected Android runtime.
- Gradle emitted only non-fatal `Divider` deprecation, SDK XML compatibility, and library-strip notices.
- Final APK metadata: application id `com.nyx.ai`, versionCode 3/versionName 0.3.0, min API 26, target API 35.

See [TEST_RESULTS.md](TEST_RESULTS.md) for test classes, warnings, and unverified runtime scenarios.

## Device feedback and remaining verification

The owner reports the previous build was installed and visually tested on a Google Pixel 10 Pro Fold, with good cover/inner-screen presentation and successful local Ollama chat. That feedback is for the prior version; this development environment had no attached Pixel/emulator or live Ollama endpoint. NYX 0.3.0 still needs real-device checks for both displays, fold/unfold state, Keystore behavior, document providers, tool-capable and non-tool-capable Ollama models, memory/thermal behavior, accessibility, cancellation under load, and battery/long-session use.

## Signing and upgrade compatibility

The new APK is debug-signed, not a store-release artifact. Its signer certificate SHA-256 matches the preceding delivered v0.2 debug APK (`aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`), so Android can upgrade that exact previously delivered build in place. Other installed NYX copies must have the same signing identity; Android may reject a different signature. Uninstalling a differently signed build may erase app-private chat, agent, and settings data. No release signing credentials are included or fabricated.

## Scope and workspace integrity

The existing Kotlin, Compose, Material 3, Room architecture and package id are preserved. No Git commits, branches, pushes, releases, or remote repository changes were made. The source ZIP excludes build caches, local SDK configuration, and signing secrets. The app remains local-first; remote inference is only used when the user explicitly configures and consents to an endpoint.

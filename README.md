# NYX

**Your intelligence. Your device. Your rules.**

NYX is a native Android local-first AI workstation. Version 0.2 adds optional secure API-key support for user-configured OpenAI-compatible `/v1` servers while retaining local Ollama chat, agent, and workflow functionality.

## What's included

- Kotlin, Jetpack Compose, Material 3, Room, coroutines, and OkHttp; Android API 26 minimum, API 35 target.
- Ollama discovery at `http://127.0.0.1:11434`, endpoint configuration, model listing, streaming/cancellable chat, and conversation history.
- OpenAI-compatible `/v1/models` and streaming `/v1/chat/completions`, including streamed function calls. Optional Bearer credentials are sent only on `/v1` calls.
- API-key protection with AES-GCM encryption and a non-exportable Android Keystore key; encrypted blobs live in app-private no-backup storage, and Room stores only an opaque credential reference. Remote credential use requires HTTPS; loopback HTTP is allowed for local development. Redirects are disabled on inference requests.
- Explicit external-server consent for discovery, chat, agents, and workflows; endpoint URLs with user info, query strings, or fragments are rejected.
- Conversation rename, copy, regenerate, and delete; bounded agents with calculator, per-agent private memory, planning, and app-private workspace tools; sequential workflows and logs.
- User-approved import of plain text, Markdown, CSV, JSON, or log files into agent task context, limited to 256 KB per file and 60,000 characters total. Import is not persisted as a separate document; it becomes task text and is sent to the configured inference server when the agent runs.
- Branded adaptive launcher, themed monochrome icon, Android 12+ splash, width-adaptive navigation (bottom bar on phones, rail on expanded screens), safe drawing insets, and device/RAM/thermal diagnostics.
- Streaming UI updates are batched to reduce recomposition frequency; HTTP requests have connection, idle-read, and total timeouts. Redirects are disabled.

## Security notes

The API key is never stored in Room, ordinary preferences, chat history, or saved UI state. It is revealed only to the HTTP request builder while needed, and the Settings field is masked and cleared after accepting a save. Android Keystore is designed to protect the encryption key at rest; this does not protect secrets on a rooted/compromised OS, from app-process inspection, or from the remote service that receives the key. The app does not pin TLS certificates in this build. Use a trusted HTTPS endpoint for remote access; don't treat loopback HTTP as appropriate for a network-facing host.

## Honest limitations

GGUF files remain registration-only: llama.cpp/JNI, Rust orchestration, native loading/inference, and GPU/Tensor acceleration are not included. Text import is deliberately limited to user-selected text-like files; PDF, DOCX, XLSX, and arbitrary binary parsing are not implemented. Width-based adaptive layouts are included, but window posture/hinge occlusion has not been instrumented or tested on a foldable. Thermal and memory figures are snapshots/advisories, not an inference scheduler. No live server or Android device was available for end-to-end verification.

## Build

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest assembleDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. See [BUILD.md](BUILD.md), [USER_GUIDE.md](USER_GUIDE.md), and [TEST_RESULTS.md](TEST_RESULTS.md).

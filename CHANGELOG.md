# Changelog

## 0.2.0 — Security and workstation upgrade

- Added optional OpenAI-compatible Bearer auth. API keys are AES-GCM-encrypted with a non-exportable Android Keystore key; encrypted files are stored under `noBackupFilesDir`, while Room retains only an opaque credential reference. Added add/replace/remove UI; remote credential use requires HTTPS; endpoint URLs with embedded credentials, query strings, and fragments are rejected. Redirects are disabled, and authentication headers are scoped to `/v1` model/chat requests only. Endpoint record updates preserve credential references. Agents/workflows now enforce external-server consent too.
- Added MockWebServer tests for `/v1` authorization and absence of Authorization on Ollama paths, plus pure JVM HTTPS/loopback policy tests and on-device Keystore round-trip/tamper instrumentation tests.
- Integrated the user-supplied NYX artwork as an adaptive launcher icon, themed monochrome mark, in-app logo, and Android 12+ splash branding.
- Added width-adaptive bottom navigation/navigation rail, safe drawing insets, device/RAM/thermal snapshots, and bounded user-approved text/Markdown/CSV/JSON/log import into agent tasks (256 KB file cap). Imported content is not persisted separately.
- Batched streamed chat state updates (~45 ms) and added bounded connect/read/call timeouts. No periodic polling was introduced.

## Verified scope and remaining work

The Android app remains Kotlin/Compose with Room, Ollama, OpenAI-compatible API, and bounded agents/workflows. Embedded llama.cpp/JNI/Rust inference, GGUF execution, GPU/Tensor acceleration, Gemini Nano, arbitrary office/PDF parsing, app-wide document library, endpoint-profile manager, window hinge/posture awareness, thermal-aware inference scheduling, and release signing remain out of scope. Width-responsive UI is not a claim of verified Pixel Fold posture support. See [TEST_RESULTS.md](TEST_RESULTS.md) for build and test outcomes.

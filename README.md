# NYX

**Your intelligence. Your device. Your rules.**

NYX 0.3.0 is a native Android local-first AI workstation. It connects to Ollama (including a user-reported working Termux/Ollama setup) or an explicitly configured OpenAI-compatible `/v1` server, with persisted chat, bounded agents, and sequential workflows.

## Included

The app uses Kotlin, Jetpack Compose, Material 3, Room, coroutines, and OkHttp (application id `com.nyx.ai`, Android API 26 minimum, target API 35). It discovers Ollama at `http://127.0.0.1:11434`, lists models, streams/cancels chat, and stores conversation history. The OpenAI-compatible adapter uses `GET /models` and streaming `POST /chat/completions`; optional Bearer credentials are attached only to those `/v1` routes.

Optional credentials are AES-GCM encrypted with a non-exportable Android Keystore key and written to app-private no-backup storage. Room holds only an opaque reference. Credentials are allowed over HTTPS, or loopback HTTP for development; remote credential-bearing requests are rejected over HTTP. Endpoint URLs containing user info, queries, or fragments are rejected, external server use has a separate consent gate, and redirects are disabled. HTTP error bodies are bounded and the exact configured key is redacted from surfaced diagnostics.

Built-in and user-created agents support editing of names/instructions, bounded tool execution, local calculator/memory/planning/workspace tools, trace events, and sequential workflows. If Ollama returns HTTP 400 indicating the model/server does not support native tools, the agent runner reports that condition and makes one text-only retry that is instructed not to claim tool use. Chat/agent/workflow jobs are serialized to avoid competing for a mobile local server. User-selected UTF-8 text, Markdown, CSV, JSON, and log files can be appended to an agent task (256 KB per file; 60,000 characters combined).

Branding uses the supplied NYX artwork for the adaptive launcher, Android themed monochrome icon, in-app mark, and Android 12+ splash. The UI keeps the dark-violet identity, uses a bottom bar on narrow layouts and a navigation rail when expanded, and has side-by-side chat history plus agent/workflow workspaces on wide layouts. Key UI state and unsent chat text survive Activity recreation. Settings and Models include memory/thermal diagnostics and a conservative available-RAM warning for large reported model files. OkHttp uses a shared process-wide connection pool; requests have bounded connect/read/overall timeouts and cancel with coroutine cancellation.

## Device feedback and verification distinction

The owner reports that the previous build has been installed on a Pixel 10 Pro Fold, looks good on cover and inner displays, and successfully connects/chat with local Ollama. That is user-reported testing of the earlier build; the current 0.3 APK has not been run on this agent's hardware. Width-adaptive layouts and state-save changes target fold/unfold usability, but no claim is made that hinge/posture occlusion or the 0.3 build has been independently validated on-device. No Tensor TPU, Gemini Nano, NNAPI, or proprietary accelerator path is implemented or claimed.

## Limitations

GGUF remains registration-only: llama.cpp/JNI/Rust inference, direct native model loading, GPU acceleration, and model unloading are not included. The importer is task-context text, not a saved document library; PDF, DOCX, XLSX, OCR, arbitrary binary parsing, and document metadata/history are not supported. There is no named multi-endpoint profile manager or first-launch multi-step wizard. Workflows run a selected agent sequence, but editable workflow templates and parallel execution are not included. RAM and thermal data are advisory snapshots; there is no model-fit guarantee or thermal scheduler. Release signing is not configured.

## Build

```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. See [BUILD.md](BUILD.md), [USER_GUIDE.md](USER_GUIDE.md), [CHANGELOG.md](CHANGELOG.md), and [TEST_RESULTS.md](TEST_RESULTS.md).

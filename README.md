<div align="center">

<img src="brand/nyx_launcher.png" alt="NYX app icon" width="132" />

# NYX

### YOUR INTELLIGENCE. YOUR DEVICE. YOUR RULES.

**A native Android workspace for local AI, connected models, agents, and multi-step workflows.**

[![Version](https://img.shields.io/badge/version-0.2.0-A78BFA?style=for-the-badge)](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.2.0)
![Android](https://img.shields.io/badge/Android-8.0%2B-12121A?style=for-the-badge&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin)
![Local first](https://img.shields.io/badge/local--first-AI-72D6A3?style=for-the-badge)

**[Download NYX](https://github.com/ShamelesAbyss/Nyx/releases/latest)** · **[User guide](USER_GUIDE.md)** · **[Build from source](BUILD.md)** · **[Changelog](CHANGELOG.md)**

</div>

---

## Meet NYX

NYX puts a flexible AI interface on your Android device without locking you into a single model provider. Connect to **Ollama** running locally (including in Termux), or point NYX at a compatible **OpenAI-style `/v1` API**. Choose a model, chat with streaming responses, configure specialized agents, and chain their work together.

The interface is built for Android, not squeezed into a mobile browser: dark obsidian surfaces, violet accents, adaptive navigation, persistent local conversations, and a dedicated workspace for models, agents, and workflows.

> **Local-first, not magically self-contained.** NYX is the Android client and orchestration interface. Inference happens on the Ollama or compatible server you connect. NYX does **not** ship with model weights or run GGUF inference inside the APK.

## What you can do

| Workspace | Capabilities |
| :--- | :--- |
| **Chat** | Stream model responses, stop generation, regenerate, copy messages, and manage locally stored conversation history |
| **Models** | Discover available models from Ollama or a compatible `/v1` endpoint; switch models and register a selected GGUF file |
| **Agents** | Create agents with custom instructions and bounded tool access, private memory, planning, and sandboxed text files |
| **Workflows** | Run multiple agents **sequentially**, passing earlier results forward and retaining local execution logs |
| **Settings** | Configure endpoints, optional encrypted API credentials, model parameters, and device diagnostics |

### Designed for the device in your hand

- **Native Kotlin + Jetpack Compose** with Material 3 components.
- **Adaptive navigation:** bottom navigation on compact screens and a navigation rail on wider layouts.
- **Local persistence:** conversations, agents, and workflow data remain in the app's private storage.
- **Streaming and cancellation:** responses arrive progressively; stop an active request when you need to.
- **Device visibility:** inspect available RAM, Android information, and thermal status snapshots.
- **Purposeful branding:** custom NYX launcher artwork, themed icon, and splash screen.

## Get started

### 1. Install NYX

Download **`NYX.apk`** from the **[latest GitHub Release](https://github.com/ShamelesAbyss/Nyx/releases/latest)** and open it on your Android device. If prompted, permit your browser or file manager to install apps.

**Requirements:** Android 8.0 (API 26) or newer, plus access to a running supported inference server. The published v0.2.0 APK is **debug-signed**, not a Play Store production build.

### 2. Connect a model

**Option A: Ollama on the same Android device**

Install and configure Ollama separately in your Android terminal environment. For example, once the `ollama` command is available:

```bash
ollama pull gemma3:4b
ollama serve
```

NYX checks **`http://127.0.0.1:11434`** by default. Open **Models → Refresh**, select your installed model, and head to **Chat**.

If Ollama is already running, do not start a second server. Downloaded model weights stay in Ollama's storage, not inside NYX.

**Option B: Another Ollama server**

In **Settings**, enter the server's base URL (without `/api`), then select **Save & connect**. Grant external-server consent when connecting outside the device.

**Option C: OpenAI-compatible API**

Enter a compatible endpoint ending in **`/v1`**. NYX supports model listing and streaming chat completions, with optional Bearer credentials stored using Android Keystore-backed encryption. Remote endpoints using credentials must use **HTTPS**; loopback HTTP is permitted for local development. The server must support the relevant API routes and tool calling if you want agent tools.

### 3. Explore the workspace

1. **Chat:** create a conversation, select a model, and send a prompt.
2. **Agents:** select or create an agent, give it instructions, and run a task.
3. **Workflows:** arrange agents in order and execute a shared goal.
4. **Settings:** adjust your connection and inspect device diagnostics.

For endpoint configuration, credentials, imported documents, and detailed controls, see the **[User Guide](USER_GUIDE.md)**.

## Agents and workflows

NYX agents can work with a deliberately limited set of local tools:

```text
NYX
├── Chat         → model conversations
├── Models       → server discovery + model selection
├── Agents       → custom instructions + bounded tools
│   ├── calculator
│   ├── memory_read / memory_write
│   ├── plan_task
│   └── workspace_read / workspace_write
└── Workflows    → Agent A → Agent B → Agent C → results + logs
```

Each agent's workspace is confined to app-private files. Agents **cannot** execute shell commands or access arbitrary Android files. Workflows run **in sequence**, not as a parallel swarm.

You can also import user-selected **TXT, Markdown, CSV, JSON, and log** content into an agent task (up to **256 KB per file**, with a **60,000-character** combined task limit). Imported text becomes part of the task and may be sent to the selected inference endpoint when the agent runs.

## Privacy and connection security

NYX does not require a NYX account and includes no built-in analytics or advertising. Its conversations and workflow records are stored in app-private local storage.

- Optional API keys are encrypted with **AES-GCM** using an Android Keystore-backed key; Room retains only an opaque credential reference.
- External-server access requires explicit consent.
- Non-loopback API-key connections require HTTPS.
- Inference requests do not follow redirects.
- **Your selected inference provider still receives the prompts and context you send it.** A remote endpoint is not equivalent to offline inference.

These protections have limits: they do not make a compromised device trustworthy, and this version does not pin TLS certificates. Read the [User Guide](USER_GUIDE.md) for details.

## Current boundaries

NYX v0.2.0 is an early release. To set expectations clearly:

| Supported now | Not yet implemented |
| :--- | :--- |
| Connect to local Ollama and compatible remote servers | Embedded GGUF / llama.cpp inference |
| Select models already available on the server | Downloading Ollama models from inside NYX |
| Register a GGUF document URI | Executing a registered GGUF file |
| Bounded agents and sequential workflows | Parallel autonomous agent swarms or unrestricted system tools |
| Import selected text-like files into tasks | PDF, DOCX, XLSX, OCR, or a persistent document library |
| Width-adaptive layouts | Verified hinge/posture-aware foldable layouts |
| Informational RAM and thermal snapshots | GPU/NPU acceleration or thermal-aware inference scheduling |

Model performance depends on the connected runtime, device memory, and the chosen model. The repository's build tests passed, but not every device-specific behavior was verified in automated testing. See [TEST_RESULTS.md](TEST_RESULTS.md).

## Built with

**Kotlin** · **Jetpack Compose** · **Material 3** · **Room** · **Coroutines** · **OkHttp** · **Android Keystore** · **Gradle**

- **Minimum Android:** API 26
- **Target SDK:** API 35
- **Application ID:** `com.nyx.ai`
- **Current version:** `0.2.0`

### Build from source

```bash
git clone https://github.com/ShamelesAbyss/Nyx.git
cd Nyx
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. See [BUILD.md](BUILD.md) for SDK requirements and instrumented tests.

---

<div align="center">

### NYX is just getting started.

**A personal AI workspace built around choice, control, and local-first computing.**

[**Get the APK**](https://github.com/ShamelesAbyss/Nyx/releases/latest) · [**Read the guide**](USER_GUIDE.md) · [**View releases**](https://github.com/ShamelesAbyss/Nyx/releases)

<sub>Made for Android. Powered by the models you choose.</sub>

</div>

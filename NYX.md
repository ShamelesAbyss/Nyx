# NYX

**Your intelligence. Your device. Your rules.**

NYX is a native Android, local-first AI workstation. This early build connects to Ollama or an OpenAI-compatible `/v1` inference API, streams chat responses, stores conversations and agent definitions in a local Room database, and runs bounded sequential agents with an allowlisted set of local tools.

## Included in this build

The app uses Kotlin, Jetpack Compose, Material 3, Room, coroutines, and OkHttp (Android API 26+, target API 35). At launch it checks Ollama at `http://127.0.0.1:11434`; users can also configure an Ollama-compatible base URL or an OpenAI-compatible URL ending in `/v1`. It lists models, streams chat, and cancels in-flight requests. Settings include external-server consent and inference options; context/thread options apply to Ollama, while the OpenAI-compatible mode currently sends temperature only.

Room stores chat/messages, endpoint records, agents, workflows, and execution logs. Starter and user-created agents can invoke a bounded function-call loop with arithmetic, per-agent private memory, structured task planning, and file operations confined to an app-private workspace. Sequential workflows pass earlier agents' results forward. Android's document picker can register a user-selected GGUF URI without copying the model file.

## Not included / limitations

**GGUF files are registered, not executed.** This project does not bundle llama.cpp, JNI, Rust, or an embedded native inference runtime. Generic arbitrary document text import, GPU acceleration, and model downloading are also not included. No live device or inference server is provisioned in the build environment, so Android runtime behavior still requires device verification. User-selected non-loopback endpoints can receive prompts and conversation context; only the configured local endpoint is contacted, and external endpoints require an explicit opt-in.

## Build

Open the project in Android Studio or run:

```bash
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Requirements and reproducible commands are in [BUILD.md](BUILD.md); setup, agent, and privacy instructions are in [USER_GUIDE.md](USER_GUIDE.md). Actual build and test outcomes are recorded in [TEST_RESULTS.md](TEST_RESULTS.md).

## Privacy

NYX includes no analytics SDK, advertising SDK, account system, or cloud inference backend. Chat, agents, and logs are stored locally. The app has Internet permission to reach the configured inference server and cleartext traffic is enabled for localhost-style setups. It does not scan shared storage; GGUF registration uses Android's system document picker. Prefer trusted HTTPS endpoints and inspect any remote server before allowing it.

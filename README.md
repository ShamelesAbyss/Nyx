# NYX

**Your intelligence. Your device. Your rules.**

NYX 0.4.0 is a native Android, local-first AI workstation that preserves the previous chat, Ollama/OpenAI-compatible inference, agent, workflow, credential-security, and adaptive UI features while adding a durable objective-execution foundation.

Download the [NYX v0.4.0 APK and complete source archive](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.4.0). The APK is debug-signed and is not a production/store-signed build. This project retains its Apache-2.0 license; see [LICENSE](LICENSE).

## v0.4.0 highlights

The Autonomy screen accepts an objective and asks the selected local model for a bounded task graph. A strict JSON validator limits the plan to 12 tasks, rejects malformed fields and dependency cycles, assigns stable task IDs locally, and ignores model-supplied agent/tool/action choices. One selected agent and its existing safe tool allowlist execute tasks sequentially. Objective, task, status, attempt, checkpoint, tool invocation/observation, and artifact data are persisted in Room. Failed or interrupted work is not automatically repeated; resumption of uncertain tasks requires user review. The local completion checks are modest and do not prove the model's claims are semantically correct.

Room schema v2 adds objective tables through an additive 1→2 migration. The Android test source includes a legacy-data preservation fixture. Settings → About reads the installed app's package metadata; a Python release checker cross-checks Gradle metadata, APK manifest, filenames, and versioned docs.

The source also contains a standalone Termux RUN_COMMAND adapter core with request validation, host/PRoot environment descriptors, callback classification and bounded results. It is not yet wired into the UI or objective agent tools, and it does not supervise/terminate a Termux process. No autonomous shell access is enabled.

## Existing capabilities

NYX uses Kotlin, Jetpack Compose, Material 3, Room, coroutines, and OkHttp (`com.nyx.ai`, min API 26, target API 35). Ollama defaults to `http://127.0.0.1:11434`; an OpenAI-compatible `/v1` server can be configured. Chat streams and persists. Optional API keys are encrypted with AES-GCM under an Android Keystore key and stored in app-private no-backup files; remote credential use requires HTTPS. The dark-violet identity, launcher art, compact/expanded navigation, agents, sequential workflows, user-approved bounded text import, and device snapshots are retained.

## Important limitations

This is an execution foundation, not a Manus-class autonomous computer. Task DAGs are limited to 12 and model tasks execute serially. There is no dynamic replanning, 20-task management queue, parallel tool worker, filesystem-backed context/memory store, long-history compression, provider abstraction, resource-aware scheduler, or independent WorkManager/foreground-service execution. The ViewModel survives Activity recreation but not process death; on restart, Room records are recovered conservatively and uncertain tasks need review. Termux bridge setup and real execution are not yet integrated or device-verified. Debian PRoot, native GGUF/llama.cpp inference, Android UI automation, full software-development workflows, and production signing are not included. Thermal/RAM diagnostics remain snapshots, not scheduling controls.

## Build and verify

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest
python3 tools/check_release_integrity.py --no-apk \
  --delivery-file NYX-v0.4.0.apk --delivery-file NYX-Source-v0.4.0.zip \
  --release-doc CHANGELOG.md --release-doc NYX_DEVELOPMENT_REPORT.md --release-doc TEST_RESULTS.md
```

See [BUILD.md](BUILD.md), [USER_GUIDE.md](USER_GUIDE.md), [CHANGELOG.md](CHANGELOG.md), [TEST_RESULTS.md](TEST_RESULTS.md), and [NYX_DEVELOPMENT_REPORT.md](NYX_DEVELOPMENT_REPORT.md).

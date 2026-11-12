# NYX

**Your intelligence. Your device. Your rules.**

NYX 0.5.0 is a native Android, local-first AI workstation with a real execution engine: a foreground service owns durable objectives, schedules dependency-ready tasks within device limits, runs allowlisted tools, and verifies completion against locally observed evidence instead of model claims.

Download the [NYX v0.4.0 APK and complete source archive](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.4.0). The APK is debug-signed and is not a production/store-signed build. This project retains its Apache-2.0 license; see [LICENSE](LICENSE).

## v0.5.0 execution engine

- **Durable ownership.** Objectives run in a `dataSync` foreground service with leases and heartbeats. The ViewModel only starts, pauses, cancels, and observes Room. Closing the UI no longer cancels work; process death stops it, and interrupted tasks are marked uncertain and never replayed automatically.
- **Dependency-aware concurrency.** A validated DAG is scheduled with per-worker-kind limits (inference, terminal, filesystem, verification, development) derived from RAM, thermal status, battery, and storage.
- **Evidence-based completion.** Exit codes, required or forbidden output, file existence, file contents, and SHA-256 are checked locally. A failed or denied tool call fails the task regardless of what the model says.
- **Conservative recovery.** Automatic retry only for read-only verification work; a repeated identical failure fails permanently; anything that may have changed state requires human review.
- **Real terminal, bounded.** Termux `RUN_COMMAND` executes real commands with real exit codes. Root, protected-Android, disk, and privilege operations are blocked. Mutating commands require explicit approval, and autonomous terminal tools are off by default with a user-set workspace.
- **Provider-neutral inference.** Chat, agents, and objectives share one `InferenceProvider` abstraction over the existing Ollama and OpenAI-compatible `/v1` transports, including the Keystore-protected Bearer key path.

Room schema v3 adds lease columns, task worker/priority/evidence/failure fields, terminal command history, task evidence, context records, and execution leases through an additive 2→3 migration. Settings → About reads the installed app's package metadata; a Python release checker cross-checks Gradle metadata, APK manifest, filenames, and versioned docs.

## Existing capabilities

NYX uses Kotlin, Jetpack Compose, Material 3, Room, coroutines, and OkHttp (`com.nyx.ai`, min API 26, target API 35). Ollama defaults to `http://127.0.0.1:11434`; an OpenAI-compatible `/v1` server can be configured. Chat streams and persists. Optional API keys are encrypted with AES-GCM under an Android Keystore key and stored in app-private no-backup files; remote credential use requires HTTPS. The dark-violet identity, launcher art, compact/expanded navigation, agents, sequential workflows, user-approved bounded text import, and device snapshots are retained.

## Important limitations

This is a real but bounded execution engine, not a Manus-class autonomous computer. Plans are capped at 40 tasks. There is no dynamic replanning during a run, no mid-process resumption of a killed objective, no multi-device sync, and no autonomous publication, credential, or privilege action. Terminal execution requires a correctly configured Termux installation and has not been verified on a physical device here; Debian/PRoot support is implemented but device-unverified. Native GGUF/llama.cpp inference, Android UI automation, and production signing are not included.

## Build and verify

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest
python3 tools/check_release_integrity.py --no-apk \
  --delivery-file NYX-v0.4.0.apk --delivery-file NYX-Source-v0.4.0.zip \
  --release-doc CHANGELOG.md --release-doc NYX_DEVELOPMENT_REPORT.md --release-doc TEST_RESULTS.md
```

See [BUILD.md](BUILD.md), [USER_GUIDE.md](USER_GUIDE.md), [CHANGELOG.md](CHANGELOG.md), [TEST_RESULTS.md](TEST_RESULTS.md), and [NYX_DEVELOPMENT_REPORT.md](NYX_DEVELOPMENT_REPORT.md).

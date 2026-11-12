# NYX v0.5.0

This file is retained for compatibility with the repository's earlier overview filename. The current project overview is [README.md](README.md), and the current release downloads are available from the [NYX v0.5.0 GitHub Release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.5.0).

NYX v0.5.0 replaces the ViewModel-owned objective runner with a foreground service that holds durable leases, schedules dependency-ready tasks concurrently within device-derived limits, executes allowlisted tools, and verifies completion against locally observed evidence: exit codes, output, file existence, file contents, and SHA-256. The Termux bridge is now wired into the UI and into workspace-scoped agent tools, with root and protected-Android operations blocked and mutating commands requiring approval. Inference is provider-neutral over the existing Ollama and OpenAI-compatible `/v1` transports.

This remains a bounded execution engine, not a Manus-equivalent autonomous computer. Terminal and Debian/PRoot behavior, process-death recovery, and live inference still require verification on physical hardware. Native GGUF inference is not included, and the APK is debug-signed.

See [BUILD.md](BUILD.md), [USER_GUIDE.md](USER_GUIDE.md), [CHANGELOG.md](CHANGELOG.md), [TEST_RESULTS.md](TEST_RESULTS.md), and [NYX_DEVELOPMENT_REPORT.md](NYX_DEVELOPMENT_REPORT.md) for current details. The project retains its Apache-2.0 license in [LICENSE](LICENSE).

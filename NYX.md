# NYX Eclipse AI — v0.7.9

This legacy filename is retained so existing links keep working. The canonical project homepage is [README.md](README.md).

NYX v0.7.9 “Project Persistence” makes Debian/PRoot Ollama lifecycle durable: verified `proot-distro` sessions where supported, a foreground fallback otherwise, an Android `dataSync` foreground owner, verified PID/session ownership, bounded readiness diagnostics, and verified stop/cleanup. Chat and objectives can start a disconnected loopback runtime and reconnect models. It folds in the unpublished v0.7.8 hotfix for the shared PRoot executable path and real Debian JDK package resolution. Physical-device validation of the Debian/Ollama path remains pending.

The complete overview, download links, capability status, and honest limitations are in [README.md](README.md); the implementation record is in [NYX_DEVELOPMENT_REPORT.md](NYX_DEVELOPMENT_REPORT.md), the v0.7.9 notes are in [RELEASE_NOTES_v0.7.9.md](RELEASE_NOTES_v0.7.9.md), and the verified test evidence is in [TEST_RESULTS.md](TEST_RESULTS.md).

Official downloads: [v0.7.9 release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.7.9). The APK is ARM64-only and debug-signed, not production-signed.

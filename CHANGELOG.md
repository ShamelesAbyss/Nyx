# Changelog

## 0.3.0 — Agent usability and Ollama reliability

- Completed the interrupted agent-editing UI: saved agents and starter templates can now be edited for name and system instructions while preserving their existing tool configuration.
- Improved HTTP failure diagnostics for model discovery and streaming chat: include at most 600 sanitized response characters, redact the exact configured API key, and classify recognizable HTTP 400 tool-capability failures.
- When a model/server explicitly rejects native function tools, bounded agents now surface a warning and retry once with tools omitted and instructions not to claim tool execution. This is a text-only fallback, not emulated tool calling.
- Reused one process-wide OkHttp client/dispatcher/connection pool across endpoint client instances. Existing connect/read/overall timeouts, redirect blocking, and coroutine cancellation remain enabled.
- Serialized chat, agent, and workflow inference jobs with an atomic reservation, closing a duplicate-start race while Room setup is still in progress.
- Preserved the selected section and unsent chat draft through Activity recreation. Added a rough, explicitly non-predictive RAM advisory when the selected server reports a model file near current available RAM.
- Added MockWebServer tests for tool-unsupported HTTP 400 classification and bounded error-detail/key-redaction behavior.

## Retained 0.2.0 improvements

Keystore-backed API-key encryption, explicit remote-server consent, endpoint URL validation, branded launcher/splash, width-adaptive navigation/workspaces, device/thermal diagnostics, and user-approved bounded text import remain in place. Ollama remains the default/local backend. No database schema change was required; existing Room data remains schema version 1.

## Priority status and remaining work

**Improved in these builds:** branding, chat/history controls, phone/expanded layouts, basic memory/thermal diagnostics, bounded text-to-agent import, agent editing/trace, sequential workflow execution, HTTP timeouts/cancellation, shared connection reuse, API-key security, external-server consent, and automated request/policy tests.

**Still incomplete or unverified:** native GGUF/llama.cpp/JNI/Rust inference and model lifecycle, GPU/Tensor/NNAPI/Gemini Nano acceleration, posture/hinge-aware layout (width adaptation only), live Pixel 10 Pro Fold verification of v0.3, RAM-fit guarantees and thermal-aware scheduling, named endpoint profiles/custom server autodetection, onboarding wizard, imported-document library/metadata and PDF/office parsing, editable workflow-template builder, parallel agents, long-session/battery profiling, Android Keystore runtime instrumentation results, and production release signing. See [TEST_RESULTS.md](TEST_RESULTS.md) and [NYX_DEVELOPMENT_REPORT.md](NYX_DEVELOPMENT_REPORT.md).

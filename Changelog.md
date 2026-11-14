# Changelog

> **Legacy filename:** this file is retained for existing links. The canonical, maintained release history is [CHANGELOG.md](CHANGELOG.md). Current release: **v0.9.0 — Project Workbench** ([published](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.9.0)).
>
> Historical v0.1.0 notes are retained from the initial project publication. The current, canonical release history through v0.9.0 is in [CHANGELOG.md](CHANGELOG.md).

## 0.9.0 — Project Workbench

Measured capability profiles per provider/model/context/thread identity, 24-hour local caching with identity invalidation, four planning strategies (direct, incremental, full, guided), bounded continuation after verified work, separate reasoning-stream progress, and evidence-based thinking suppression for structured planning. No new permissions or tool authority; signing identity, llama.cpp source and user data are unchanged.

## 0.1.0 — Initial implementation

Created the native Kotlin/Compose Android Studio project and Room schema/DAOs. Implemented default Ollama discovery, model selection, streaming chat, cancellation, persistent conversation history, plus OpenAI-compatible `/v1` model listing, chat streaming, and streamed function-tool calls. Added endpoint consent controls, temperature and Ollama context/thread options, SAF-based GGUF URI registration, an adaptive dark interface, and a launcher icon.

Added starter and user-defined agents with a bounded model/tool cycle, private per-agent memory, a safe arithmetic calculator, structured plan storage, app-private workspace file operations, execution logs, and sequential multi-agent workflows with shared results and cancellation. Added conversation rename, deletion, copy, and regeneration controls. Gradle debug packaging and the JVM calculator tests passed in the sandbox; see [TEST_RESULTS.md](TEST_RESULTS.md).

Embedded llama.cpp, JNI/Rust orchestration, native GGUF inference, GPU acceleration, and agent document ingestion remain incomplete. GGUF is registration-only in this build. Live inference and target-device behavior have not been verified.

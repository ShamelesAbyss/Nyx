# Vendored llama.cpp

Upstream: https://github.com/ggml-org/llama.cpp

Commit: `09338b85e8b230742fa56f93a1d9f0bf006ca2d3`

Source archive: https://codeload.github.com/ggml-org/llama.cpp/tar.gz/09338b85e8b230742fa56f93a1d9f0bf006ca2d3

Archive SHA-256: `f5311c680f579b78aa6d64facd4c9de0fb3325fb51ef86f44c229ab411e089a0`

Downloaded 2026-11-12. `llama.cpp/` is the complete extracted upstream archive, not a submodule or Git checkout. Upstream MIT license remains at `llama.cpp/LICENSE`; all bundled third-party license files remain intact. There are no model weights bundled by Nyx. Upstream tests may contain tiny tokenizer/format fixtures, not production model weights.

Nyx-specific sources live outside `llama.cpp/`. Build options disable upstream tools, downloads, optional external backends and KleidiAI. Public API contracts were verified against this commit's `include/llama.h`, `src/llama.cpp`, `src/llama-sampler.cpp`, and `gguf-py/gguf/constants.py`; do not update the archive without rechecking those contracts.

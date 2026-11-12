# Native GGUF inference

Nyx's native implementation executes real llama.cpp CPU inference through `libnyx_llama.so`; it is not a stub, HTTP wrapper, or canned-token fallback. The single process-wide model/context session is reused for successive requests. Each request rebuilds the model's chat-template prompt, tokenizes it, decodes bounded batches and samples tokens. No model weights are downloaded by the app or bundled in the APK.

## Integration APIs

All Kotlin types are in `com.nyx.ai.nativeinference`. The existing `network.InferenceProvider` interface and `ApiMessage`, `InferenceOptions`, `ChatResult`, `LocalModel` contracts are unchanged.

```kotlin
val repository = NativeModelRepository(context)
val provider = NativeInferenceProvider(context) // optional id/displayName/generationOptions parameters
val imported: NativeModel = repository.import(uri) // suspend; SAF content URI
val models: List<NativeModel> = repository.models() // synchronous; call on IO
val sameModels = repository.list()                 // listing alias
val model = repository.find(imported.id)
val admitted: NativeAdmission = repository.admission(imported, InferenceOptions())

NativeRuntime.initialize(context) // provider/repository already do this; application Context only
val state: StateFlow<NativeRuntimeStatus> = NativeRuntime.status
NativeRuntime.load(imported.path, InferenceOptions(contextLength = 4096, cpuThreads = 4)) // suspend
NativeRuntime.generate(imported.path, messages, options, NativeGenerationOptions(512, 42)) { delta -> /* IO thread */ }
NativeRuntime.cancel() // immediate atomic signal; no waiting for the model mutex
NativeRuntime.unload() // suspend; cancels, waits for active work, safely frees the session
repository.delete(imported.id) // suspend; unloads this path before deleting; Boolean result
```

**Model IDs are canonical absolute filesystem paths**, not `native:` keys and not titles. `NativeModel.id == NativeModel.path`. The provider has `kind = "native-gguf"`, default ID `"native-local"`, local capability and concurrency one. An endpoint can use `provider = "native-gguf"`, `baseUrl = "native://local"`, and `modelId = imported.id`; the URI is a selector, never a network address. `listModels()` uses the absolute model ID as `LocalModel.name`; use repository `NativeModel.name` for a display label. The provider refuses any model not in the private imported repository. Explicit low-level runtime load/generate accepts an app-readable absolute path for authorized testing/integration.

`NativeRuntimeStatus` exposes `phase`, `modelPath`, `modelName`, actual `contextLength`, `estimate`, `availableMemBytes`, `detail`, `lastLoadMillis`, `generatedTokens`, and `lastGenerateMillis`. `generatedTokens` is the real JNI count of selected non-end-of-generation tokens, including nonprinting pieces; it is **not** callback chunk count or character count. It is null during an incomplete/cancelled/failed request. Timings are monotonic elapsed milliseconds. Phase is `UNLOADED`, `LOADING`, `READY`, `GENERATING`, or `ERROR`. Repeated unload is safe. Provider `close()` is intentionally a no-op: the application explicitly owns the shared runtime lifecycle.

Generation is on `Dispatchers.IO`. Callbacks run there, so UI code must dispatch state changes appropriately. Kotlin mutex serialization covers model loading, generation, unloading and deletion. Coroutine cancellation arms the native abort flag, then joins native work under `NonCancellable` before releasing the mutex. An explicit cancellation epoch prevents a cancel signal during operation setup from being lost when resetting the native abort flag. Native decode uses llama.cpp's CPU abort callback; model loading uses its progress callback. Cancellation is cooperative, not an unsafe thread kill. Template formatting/tokenization and file/header parsing have bounded inputs but cannot be interrupted inside a single upstream call. Aborted memory is cleared before reuse.

## Build ownership

Parent/application integration owns Gradle, ABI filters, factory, ViewModel, UI, service and foreground policy. No such files are modified by this implementation. Configure the existing app with NDK `28.2.13676358`, external CMake `3.22.1`, source `app/src/main/cpp/CMakeLists.txt`, and `arm64-v8a` ABI. The target is `nyx_llama` (`SHARED`). C++17, PIC and static upstream llama/ggml linkage produce one JNI library, with the NDK C++ runtime packaged by the Android build as appropriate.

The JNI target uses `-Wl,-z,max-page-size=16384` and `-Wl,-z,common-page-size=16384`. APK ZIP alignment still belongs to the app packaging build; ELF page alignment alone is not a complete APK compatibility assertion. The NDK 28 C++ runtime is selected by parent integration. Android uses baseline `armv8-a` NEON, not host-native optional ISA flags. GPU offload, optional external backends, OpenMP, KleidiAI downloads, upstream tools, server, subprocess support and OpenSSL are disabled. No network access is needed by the configured native build.

Standalone ARM64 cross-compilation succeeded with installed NDK `28.2.13676358`, Clang 19.0.1, CMake `3.22.1`, Android API 26 and `c++_shared`. The resulting `/tmp/nyx-native-arm64/libnyx_llama.so` has machine `AArch64`, all three ELF LOAD segments aligned to `0x4000`, all five expected JNI exports and no separate llama/ggml shared-library dependency. Its C++ runtime dependency is `libc++_shared.so`; parent APK packaging must include the same NDK runtime. This verifies compilation/linkage, not execution on Android.

## Imported files and bounded GGUF validation

SAF import accepts a `content://` URI, obtains its stream and copies through a 64 KiB buffer. The default maximum is 8 GiB; callers may set `maxBytes` up to the parser's 16 GiB ceiling. Declared provider sizes are not trusted: every copied byte is independently checked. The available private-storage budget retains 64 MiB, and imported models are limited to 128. Import uses a same-directory temporary file, syncs its contents, validates GGUF, and uses atomic POSIX rename into `filesDir/models/<UUID>.gguf`. Failure removes temporary/committed partial files. A bounded JSON sidecar persists metadata and import time with its own temporary-file rename. A missing sidecar after an interrupted commit is recovered by streaming the GGUF header; invalid or stale metadata is not listed. Directory fsync is best-effort; atomic visibility does not guarantee survival of every sudden power-loss scenario. Orphan temporary files after process death are not automatically swept and may require app storage cleanup.

The pure `GgufMetadataParser.read(File)` uses `RandomAccessFile` and seeks over ignored fields and all model weights. It accepts little-endian GGUF v2/v3 and rejects wrong magic/version, unsupported unsigned sizes, duplicate metadata/tensor names, unknown/nested array types, malformed relevant UTF-8, NUL names, invalid architecture/context, invalid alignment, split models, absent tokenizer vocabulary, invalid tensor dimensions, removed quantization formats, misaligned quantized rows, overlapping tensors and out-of-file tensor ranges. Metadata/directory processing is capped at 128 MiB, with at most 16,384 metadata keys, 100,000 tensors, 1,000,000 array items, 1 MiB general strings, 64 KiB tokenizer entries and bounded names. Tensor sizes use overflow-checked arithmetic and dtype block sizes from the pinned upstream source. The native loader additionally checks tensor data and model architecture. Validation is a defensive preflight, not a formal sandbox for hostile binary model files; import only trusted model sources.

`NativeModel` includes ID, path, display name, file size, architecture, integer `trainingContext`, import timestamp, parsed metadata and `estimateInfo`. Metadata includes tokenizer model/vocabulary count, default chat template, tensor bytes/data offset, layer/embedding/head dimensions. A model lacking a default template may be imported and inspected, but runtime load explicitly refuses it. No guessed prompt fallback is used.

## Memory and limits

Admission reads **system available memory** from `ActivityManager.MemoryInfo.availMem`, not Java heap maximum. It also respects `lowMemory` and Android's threshold. The safety margin is the maximum of the configured reserve, 20% of currently available memory and Android's low-memory threshold. Configure `nyx_settings` integer preference `native_memory_margin_mb`, default 512, clamped to 256–2048 MiB. Parent settings UI may write that key. Each load rereads it. No memory estimate is a guarantee that Android will keep the process alive.

Weight residency is conservatively estimated using the entire GGUF file size. For supported conventional transformer architectures (`llama`, `qwen2`, `qwen3`, `smollm3`, `phi3`, `stablelm`) with sufficient metadata, FP16 KV is estimated as:

```text
layers × requested_context × KV_heads × (key_head_dimension + value_head_dimension) × 2 bytes
```

KV heads default to attention heads if absent. Key/value head dimensions default to embedding dimension divided by attention heads when qualified. Add a 256 MiB graph/compute reserve and the system-memory safety margin. Unknown layouts refuse load admission instead of presenting a made-up KV estimate. mmap does not make weights free: physical page residency varies, and file size is not Android heap use. A previously loaded model is freed before measuring/loading a replacement to avoid transient double-weight residency. Model-specific graph allocations, sliding-window or other special architectures can differ from these conservative heuristics; use smaller contexts/models if device memory pressure is high.

Native context is bounded to 512–32,768 and must not exceed the model's training context. Threads are bounded to 1–32. Temperature must be finite in 0–2; zero selects greedy sampling, otherwise top-k 40/top-p 0.95/temperature/distribution sampling is used. `NativeGenerationOptions` defaults to 512 output tokens, permits 1–4096, and supports a sampler seed (`-1` delegates to upstream random seed). Prompt/formatted prompt and raw output are each capped at 1 MiB. Native prefill batches are 256 with 128 physical microbatches. Output also stops at the remaining context capacity or model end-of-generation token. There is no silent prompt history truncation, context sliding, persistent conversation KV prefix cache, parallel native session, embeddings, encoder-decoder inference, recurrent/hybrid/diffusion model support, multimodal inference or GPU acceleration.

Tool calling is explicitly **unsupported**: capabilities report `toolCalling = false`, and a nonempty tools array or tool-call message fields cause `ToolCallingUnsupportedException`. Structured text actions and safe execution validation belong to the parent orchestrator; native generated text is not an authorized tool call. Bounded/EOG generation returns the existing `ChatResult(content, done = true)` contract; it does not expose a separate finish reason.

## Reproducible verification

The pinned source is upstream llama.cpp commit `09338b85e8b230742fa56f93a1d9f0bf006ca2d3`. The full archive is vendored at `app/src/main/cpp/llama.cpp` without `.git` and preserves upstream MIT and third-party licenses. Archive SHA-256 is `f5311c680f579b78aa6d64facd4c9de0fb3325fb51ef86f44c229ab411e089a0`. See `app/src/main/cpp/UPSTREAM.md`. Public APIs are verified against the exact pinned `include/llama.h` and implementations, not old tutorials. In particular, model mmap uses current `load_mode = LLAMA_LOAD_MODE_MMAP`; chat uses `llama_model_chat_template` plus `llama_chat_apply_template`, which supports a predefined template set rather than arbitrary Jinja. Sampling uses `llama_sampler_sample`, which already accepts sampled tokens. [1] [2]

A genuine **host x86_64 JNI** test ran on 2026-11-12 through the actual Kotlin `NativeBindings` and `NativeTokenSink`, loading SmolLM2-135M-Instruct-Q4_K_M. The original and quantized model cards declare Apache-2.0. Quantized repository revision is `09816acd5d99df7be770d85ea30822623dab342c`, filename `SmolLM2-135M-Instruct-Q4_K_M.gguf`, size 105,454,432 bytes, SHA-256 `2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d`. Test weights are outside the project at `/tmp/nyx-model-test/`; they are not source or APK assets. [3] [4]

With greedy sampling, seed 42, context 512 and 32-token maximum, the model streamed `The capital of France is Paris.` in 7 selected output tokens. Streamed text equalled completed text. Atomic cancellation after a real first token propagated `CancellationException`; the same session then reproduced the output, showing no retained partial KV state. A deliberately thrown callback exception crossed JNI unchanged. Double unload succeeded. The direct Kotlin compiler compiled every native-owned production Kotlin file against the existing network contracts, and JUnit passed **35** synthetic/malicious parser and UTF-8 tests. These are execution/compatibility checks, not a claim about answer quality or agent-tool reliability.

Host reproduction, with a JDK and cached project Kotlin/test dependencies:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
cmake -S app/src/main/cpp -B /tmp/nyx-native-host -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/nyx-native-host --target nyx_llama -j4
# Download only the explicitly selected Apache-2.0 test GGUF OUTSIDE the project.
mkdir -p /tmp/nyx-model-test
curl -fL 'https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/09816acd5d99df7be770d85ea30822623dab342c/SmolLM2-135M-Instruct-Q4_K_M.gguf?download=true' \
  -o /tmp/nyx-model-test/SmolLM2-135M-Instruct-Q4_K_M.gguf
python3 app/src/test/java/com/nyx/ai/nativeinference/host_verify.py \
  --library-dir /tmp/nyx-native-host \
  --model /tmp/nyx-model-test/SmolLM2-135M-Instruct-Q4_K_M.gguf
```

`host_verify.py` does not invoke Gradle. It uses the preexisting compiled network contract classes and cached Kotlin 2.0.21/dependencies; run the application build once if those contracts are absent. Omit model/library arguments to run only unit tests: the runner explicitly prints that real inference was **not executed**. Host JNI discovery does not require AWT. An optional C++ equivalent uses `-DNYX_BUILD_HOST_TEST=ON` and target `nyx_native_smoke`, which exits 77 when no real model argument is supplied.

`NativeRealModelTest` is an opt-in instrumented test of load, real token streaming, explicit/coroutine cancellation, reuse and unload. Without `modelPath`, JUnit assumption-skips with an explicit reason; that is **not** an inference pass. Supply an absolute file readable by the target app, preferably copied/imported into its private storage. A supplied invalid model path fails rather than skipping:

```bash
adb shell am instrument -w \
  -e class com.nyx.ai.nativeinference.NativeRealModelTest \
  -e modelPath /data/user/0/com.nyx.ai/files/models/your-imported-model.gguf \
  com.nyx.ai.test/androidx.test.runner.AndroidJUnitRunner
```

No Android device inference run has been performed by this implementation subtask. Device performance, thermal behavior, process survival under memory pressure and app foreground/background policy still require parent/device validation. Host tests deliberately bypass Android admission; passing host inference is not evidence that a particular Android phone has sufficient memory.

`NativeModelRepositoryTest` separately checks disk-sidecar recovery, repository recreation, private-path deletion refusal and cleanup using a tiny synthetic format fixture. It does not load that fixture for inference and does not count as a real-model test. These instrumented repository tests have been added but not executed in this subtask.

## References

[1]: https://github.com/ggml-org/llama.cpp/tree/09338b85e8b230742fa56f93a1d9f0bf006ca2d3 "Pinned upstream llama.cpp source"
[2]: https://github.com/ggml-org/llama.cpp/wiki/Templates-supported-by-llama_chat_apply_template "Upstream chat-template reference; exact pinned header remains authoritative"
[3]: https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct "Original SmolLM2 model card and Apache-2.0 license"
[4]: https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF "Quantized SmolLM2 GGUF model card and Apache-2.0 license"

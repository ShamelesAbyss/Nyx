# NYX User Guide — 0.3.0

## Connect to Ollama in Termux

NYX checks the local Ollama API at `http://127.0.0.1:11434` on launch. Install/configure Ollama in your supported Android environment (NYX does not install it), then start it in Termux:

```sh
ollama serve
```

Use **Models → Refresh** to retry discovery. The default server should expose `GET /api/tags` and `POST /api/chat`. To use another Ollama host/port, open **Settings**, enter its API base URL (without `/api`), and choose **Save & connect**. Non-loopback hosts require the separate **Allow external server** consent. NYX does not scan other apps' private directories.

## OpenAI-compatible servers and API keys

A compatible base URL ends with `/v1`, such as `http://127.0.0.1:8080/v1` for loopback development. The server must implement `GET /models` and streaming `POST /chat/completions`; function calling requires a compatible tools interface. NYX sends standard Bearer authentication only to those `/v1` model/chat requests and never adds it to Ollama routes.

Save/connect the endpoint first. Then enter an optional key in the masked Settings field. AES-GCM protects it using a non-exportable Android Keystore key; ciphertext is app-private/no-backup and Room stores only a random reference. Remove or replace the key in Settings. Remote keys require HTTPS; loopback HTTP is allowed for local development only. Redirects are disabled. Error diagnostics are bounded and redact the exact configured key, but do not expose keys in a URL. Keystore protection cannot defend against a rooted/compromised OS, runtime memory inspection, or the server that receives the key; TLS certificate pinning is not implemented.

## Models, chat, and troubleshooting

Select a discovered model in Chat or Models. NYX does not download models. Chat is persisted locally, streams tokens, supports stop/copy/regenerate, and the current input/section are retained across Activity recreation. Context length and CPU thread options currently apply to Ollama; temperature is sent to both backends.

If a model/server rejects native tool calls with an Ollama HTTP 400 capability error, NYX surfaces a bounded server diagnostic, records a warning, and retries that agent turn once without tools. The text-only fallback is instructed not to claim tools ran. No tools run in that fallback. Other HTTP 400 errors include a shortened server response detail when available. Use a model/runtime with tool support for calculator, planning, memory, and workspace operations.

## Agents, documents, and workflows

The Agents screen provides built-in templates and lets you create or edit an agent's name and system instructions. Agents have bounded steps and only the local allowlisted calculator, per-agent private key/value memory, structured plan, and app-private single-filename workspace tools. Shell access and unrestricted file access are not available. Chat, agent, and workflow inference tasks are serialized so they do not concurrently load the phone's local backend.

Use the folder action to select a user-approved UTF-8 text, Markdown, CSV, JSON, or log file. Each import is limited to 256 KB and the combined task to 60,000 characters. Imported content is appended to the current task; it is not maintained as a separate library or metadata record. It is sent to the configured inference endpoint when you run the agent, so avoid importing sensitive text if you have not verified the endpoint. PDF, office formats, OCR, and binary files are not supported.

Workflows run selected agents sequentially in the displayed order and pass accumulated text forward; execution logs are stored locally. NYX does not yet have user-editable workflow templates or parallel execution.

## Pixel Fold, memory, and GGUF

The owner reports the previous NYX build worked on the Pixel 10 Pro Fold's cover and unfolded screens with local Ollama. In 0.3, narrow layouts retain bottom navigation and wide layouts use a navigation rail; Chat shows history beside the conversation, while Agents and Workflows use split workspaces. Current section and unsent chat draft are saveable across Activity recreation. Width adapts, but hinge/posture avoidance has not been independently verified on 0.3.

Models may show a RAM advisory comparing an Ollama-reported model file size with a snapshot of currently available system RAM. File size is not runtime memory demand; treat this only as a rough warning. Settings shows Android/ABI, memory, thermal snapshot, endpoint, and model. There is no automatic thermal scheduler, guaranteed fit prediction, Tensor acceleration, Gemini Nano, or TPU integration.

Choosing a `.gguf` file in Models records the URI only. Embedded llama.cpp/JNI inference, model loading/unloading, and GPU acceleration are not in this build.

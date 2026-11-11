# NYX User Guide

## Connect to an inference server

On launch, NYX checks Ollama at `http://127.0.0.1:11434` and lists `/api/tags`. Start Ollama yourself in your Android environment; NYX does not install it. In Termux, after installing/configuring Ollama, run `ollama serve`. Use **Models → Refresh** to check again. For another Ollama service, enter its base URL in **Settings** without `/api`, then choose **Save & connect**.

NYX also supports OpenAI-compatible APIs when the base URL ends in `/v1`. A local development server can use a URL such as `http://127.0.0.1:8080/v1`; remote endpoints should use HTTPS. The server must expose `GET /models` and streaming `POST /chat/completions`. Function tools require compatible tool support. NYX sends optional standard Bearer authentication on these two routes only. It does not send API keys to Ollama.

### Save an API key

1. Enter and **Save & connect** the `/v1` endpoint first.
2. Enable **Allow external server** before connecting to a non-loopback host. Verify that you trust the server.
3. Enter the key in the masked **API key** field and tap **Save key securely**. Remote hosts require HTTPS; plain HTTP is accepted for loopback development only.
4. NYX encrypts the credential with AES-GCM using a non-exportable Android Keystore key. The encrypted value is in app-private no-backup storage; the Room database stores only a random credential reference. The key is not displayed again. Use **Remove key** to delete it from this device, or enter a new value to replace it.

A key may be lost if app data is cleared, the app is uninstalled, or device Keystore material becomes unavailable; enter it again in those cases. This storage protects data at rest, but not a rooted/compromised device, runtime memory inspection, or the endpoint receiving the key. NYX does not pin TLS certificates in this build. Do not place secrets in endpoint URLs.

Select an installed model in Chat or Models. NYX does not download models. Temperature is sent in both modes; context length and CPU-thread values currently apply to Ollama only.

## Chat and screen layouts

Create a chat and send a prompt. **Stop** cancels an in-flight request; conversation history supports selecting, renaming, copying, deleting, and regenerating. On phones, navigation is at the bottom. At expanded widths, NYX uses a persistent navigation rail and Chat can show the history list beside the conversation. Layouts respond to available width, but hinge/posture-specific pane avoidance has not yet been tested on physical foldables.

## Agents and text documents

Select a built-in agent or create one with custom instructions. Agents use a bounded loop and can access only the basic arithmetic calculator, per-agent private memory, structured plan storage, and a workspace confined to app-private simple filenames. There is no shell or general Android filesystem access.

On the Agents screen, use the folder icon to select a user-approved UTF-8 text, Markdown, CSV, JSON, or log file. The importer enforces a 256 KB file limit and a 60,000-character combined task limit. PDF, DOCX, XLSX, binary files, and OCR are not supported. Imported text is added directly to the current agent task, not kept as a separate document record. When you run the task, that text is sent to the selected inference server; verify the endpoint before importing sensitive material. The agent can also analyze content you paste into the task field.

## Workflows, models, and GGUF

Workflows run selected agents sequentially in displayed list order; each receives the goal and earlier results. Logs are stored locally. GGUF selection in Models registers the user-granted URI only; native llama.cpp/JNI loading, generation, and GPU acceleration are not included.

The Settings diagnostics panel shows a snapshot of Android version, ABI, app heap class, available system RAM, thermal state where Android exposes it, and the active endpoint/model. These are informational advisories, not a model-fit estimator or thermal-aware inference scheduler. NYX does not claim Tensor TPU or Gemini Nano acceleration.

## Privacy and limitations

NYX includes no analytics, advertising, account, or built-in cloud service. A user-configured remote server may receive prompts, imported text, conversation context, and the API key. External requests require explicit consent; credentials require TLS for non-loopback hosts. The app supports cleartext network traffic for local development, so do not enter a remote HTTP API key. Chat, agent, and workflow data are stored app-privately and normally removed with the app.

# NYX User Guide

## First launch and inference servers

On launch, NYX checks `http://127.0.0.1:11434` for Ollama and lists models from `/api/tags`. Start Ollama yourself in your Android environment; NYX does not install it. In Termux, after installing and configuring Ollama, run:

```sh
ollama serve
```

Use **Models → Refresh** to check again. To use another Ollama instance, open **Settings**, enter the base URL without `/api`, and choose **Save & connect**.

NYX also recognizes an OpenAI-compatible endpoint when its base URL ends with `/v1`, for example `http://127.0.0.1:8080/v1`. The server must expose `GET /models` and streaming `POST /chat/completions`; it should support standard function tools if you want agents to use tools. Model names are taken from the endpoint's models list. No API-key field is provided in this build, so configure authentication-free local APIs only.

Select an installed model from Chat or Models. NYX does not download models. Temperature is sent in both modes; context length and CPU thread settings are currently sent only to Ollama.

## Chat

Create a chat and send a prompt. Responses stream from the selected endpoint; **Stop** cancels an in-flight request. Conversations are stored in the app's local Room database. History supports selecting, renaming, copying, deleting, and regenerating. An empty model list or offline endpoint is reported; NYX does not simulate model responses.

## Agents

Choose one of the built-in templates or create an agent with custom system instructions. Enter a task and run it. The bounded agent loop can use the following local tools: a basic arithmetic calculator; per-agent private key-value memory; a structured plan store; and file reads/writes restricted to a simple filename in that agent's app-private workspace.

The app provides no shell execution or unrestricted Android filesystem access. Execution status and outputs are stored locally. Stop cancels the active run. The File Analysis template analyzes supplied task text; there is no general-purpose document-reading/import workflow for agents.

## Workflows

Select agents and enter a goal. The workflow runs them sequentially in the displayed list order. Each agent receives the original goal plus accumulated text results from prior steps. Logs are local; parallel execution is not enabled.

## GGUF registration

On **Models**, select **Choose** and use Android's document picker to grant access to a `.gguf` file. NYX records the selected URI and does not copy a multi-gigabyte file by default. **This build does not include llama.cpp/JNI inference, so a registered GGUF cannot yet be loaded or used to generate text.** Use an Ollama or compatible API-served model for inference.

## Privacy

The default endpoint is loopback. Non-local endpoints require enabling **Allow external server**. A remote server may receive prompts and conversation context; verify its operator and prefer HTTPS. NYX includes no built-in telemetry, advertising, account, or cloud AI service. Chats and agent records are app-private and are normally removed when the app is uninstalled. NYX does not scan general shared storage; model registration uses Android's system picker.

# NYX User Guide — 0.4.0

## Connect to local inference

NYX checks Ollama at `http://127.0.0.1:11434`. Install and start Ollama in the environment you manage; NYX does not install it. For Ollama, the expected APIs are `GET /api/tags` and `POST /api/chat`. Use **Models → Refresh** after starting the server. OpenAI-compatible servers use a base URL ending in `/v1`, provide `GET /models` and streaming `POST /chat/completions`, and may use an optional Bearer key.

Remote endpoints require explicit consent. API keys are masked during entry, encrypted by Android Keystore, stored in app-private no-backup files, and never placed in chat or endpoint URLs. Remote API-key transport requires HTTPS; loopback HTTP is allowed for local development. The setting screen displays installed package version metadata.

## Create and follow an objective

1. Choose an available model and an agent whose enabled tools fit the work.
2. Open **Autonomy**, enter a clear objective, and select **Plan and execute**.
3. NYX asks the model for a JSON task DAG. The parser validates size, task fields, IDs, dependencies, and cycles before any agent task starts. Model-provided tools or agent choices are ignored.
4. Tasks run sequentially through the selected agent's existing bounded allowlist (calculator, private memory, structured plan, or sandboxed workspace). Review task statuses, prerequisites, trace, and saved result artifacts.
5. Pause or cancel as needed. If execution is interrupted, an active task may have had side effects. NYX marks it for review and does not replay it automatically. Read the trace/output and use **Review & resume** only if another attempt is safe.

Plans are capped at 12 tasks, one model task runs at a time, and the current verifier checks that an output exists and that no tool error/denial was reported. It does not independently prove semantic correctness, successful external actions, or model claims. No automatic dynamic replanning, parallel tool tasks, or 20-task queue is provided in 0.4.0.

## Termux / Debian status

The source includes a standalone RUN_COMMAND adapter core with explicit per-request authorization, argv-based descriptors, environment labels, bounded callback output, and result classification. It is not exposed in the app UI or available to agents/objectives in this release. There is no in-app setup/connectivity wizard, autonomous shell tool, persistent process supervision, or verified Debian PRoot integration. A timeout/cancel only ends NYX's wait for the callback; it cannot guarantee the Termux process itself stops. Do not treat this as usable terminal automation yet. Never assume NYX can read Termux's private files.

## Existing agents, workflows, chat, and imports

Chat history persists locally; streaming requests can be stopped, and prior answers can be copied or regenerated. Agents can be edited and use only configured safe tools. Sequential workflows pass earlier output to later agents. A user-selected UTF-8 text/Markdown/CSV/JSON/log file may be appended to an agent task (256 KB file limit, 60,000-character combined task limit); it is sent to the configured inference endpoint when the agent runs. PDF/office parsing, OCR, and a separate document library are not included.

## Durability and device limits

Room migration 1→2 is additive and retains existing tables. Objective/task/events/artifacts are saved in Room. On process restart, active records are conservatively changed to paused/needs-attention, with uncertain tasks requiring review. The execution loop is still owned by the app ViewModel while the process is alive: no foreground service or WorkManager job keeps work running after process death, and Android may stop it in background.

Settings diagnostics are point-in-time Android memory and thermal snapshots; they do not represent Debian/Termux memory, loaded model memory, swap pressure, or workload limits. Width-adaptive layouts are preserved, but v0.4.0 has not been verified on Pixel 10 Pro Fold/GrapheneOS. No native GGUF inference, Tensor/TPU acceleration, or Android accessibility/UI automation is included.

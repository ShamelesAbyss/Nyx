# NYX User Guide — 0.5.0

## Terminal and real command execution

NYX executes commands through Termux, not through a simulated shell. Setup:

1. Install Termux from a trusted source.
2. Add `allow-external-apps=true` to `~/.termux/termux.properties` and restart Termux.
3. Grant NYX the `com.termux.permission.RUN_COMMAND` permission in Android settings.
4. Open **Autonomy → Terminal → Detect environment**. NYX reports the real `HOME`, `PREFIX`, shell, `proot-distro`, `timeout`, and detected tools.
5. For Debian, install it yourself with `proot-distro install debian`, then select **Debian** in the Terminal panel. Unverified configurations fail visibly; NYX does not pretend a distribution exists.

Commands classified as read-only run immediately. Mutating commands show an approval card with the exact command; nothing runs until you approve. Root, `su`, protected Android settings, disk formatting, and similar operations are blocked outright.

When `timeout` is available, commands are wrapped with coreutils `timeout`, which terminates the child. Without it, NYX bounds how long it waits but cannot guarantee the Termux process stopped.

Autonomous terminal use is separate and off by default. Enable **Allow terminal tools for objectives** and set a workspace path such as `~/projects/app`. Agents can then use `terminal_run`, `file_read`, `file_write`, `file_list`, `file_search`, `project_detect`, `build_run`, and `test_run`, confined to that workspace. Destructive and privileged commands remain blocked even then.

## How objectives execute

Objectives run in a foreground service with a visible notification and Pause/Cancel actions. Tasks run concurrently when their dependencies allow, within limits derived from your device's RAM, thermal state, battery, and storage. Closing the app UI does not cancel a running objective. If Android kills the process, work stops; on next start, any task that was running is marked for review because it may already have had side effects.

A task completes only when its declared evidence passes: exit code, expected output, file existence, file contents, or checksum. If you declare no evidence, NYX requires non-empty output and labels that as weak proof. Failed tasks are retried only when the work was read-only; otherwise you must review and approve.

## Connect to local inference

NYX checks Ollama at `http://127.0.0.1:11434`. Install and start Ollama in the environment you manage; NYX does not install it. For Ollama, the expected APIs are `GET /api/tags` and `POST /api/chat`. Use **Models → Refresh** after starting the server. OpenAI-compatible servers use a base URL ending in `/v1`, provide `GET /models` and streaming `POST /chat/completions`, and may use an optional Bearer key.

Remote endpoints require explicit consent. API keys are masked during entry, encrypted by Android Keystore, stored in app-private no-backup files, and never placed in chat or endpoint URLs. Remote API-key transport requires HTTPS; loopback HTTP is allowed for local development. The setting screen displays installed package version metadata.

## Create and follow an objective

1. Choose an available model and an agent whose enabled tools fit the work.
2. Open **Autonomy**, enter a clear objective, and select **Plan and execute**.
3. NYX asks the model for a JSON task DAG. The parser validates size, task fields, IDs, dependencies, and cycles before any agent task starts. Model-provided tools or agent choices are ignored.
4. Tasks run in dependency order, concurrently where the resource policy allows, through the selected agent's allowlist plus the authorized workspace tools if enabled. Review task statuses, prerequisites, trace, evidence, and saved result artifacts.
5. Pause or cancel as needed. If execution is interrupted, an active task may have had side effects. NYX marks it for review and does not replay it automatically. Read the trace/output and use **Review & resume** only if another attempt is safe.

Plans are capped at 40 tasks. Completion is decided by locally checked evidence, not by model prose. No dynamic replanning mid-run, mid-process resumption after a kill, or multi-device sync is provided in 0.5.0.

## Termux / Debian status

The terminal bridge is wired into the UI and into agent tools, but it is device-unverified. It cannot read Termux's private files; it only exchanges RUN_COMMAND requests and callback results. Debian/PRoot execution is implemented through `proot-distro login` but has not been validated on hardware here. A cancelled command stops NYX waiting; only the coreutils `timeout` wrapper can guarantee the child is terminated.

## Existing agents, workflows, chat, and imports

Chat history persists locally; streaming requests can be stopped, and prior answers can be copied or regenerated. Agents can be edited and use only configured safe tools. Sequential workflows pass earlier output to later agents. A user-selected UTF-8 text/Markdown/CSV/JSON/log file may be appended to an agent task (256 KB file limit, 60,000-character combined task limit); it is sent to the configured inference endpoint when the agent runs. PDF/office parsing, OCR, and a separate document library are not included.

## Durability and device limits

Room migrations 1→2 and 2→3 are additive and retain existing tables. Objectives, tasks, events, artifacts, terminal history, evidence, and context records are saved in Room. On process restart, active records are conservatively changed to paused/needs-attention, with uncertain tasks requiring review. A foreground service keeps work running while the UI is closed, but Android may still stop it under memory or battery pressure.

Settings diagnostics are point-in-time Android memory and thermal snapshots; they do not represent Debian/Termux memory, loaded model memory, swap pressure, or exact workload limits. Width-adaptive layouts are preserved, but v0.5.0 has not been verified on Pixel 10 Pro Fold/GrapheneOS. No native GGUF inference, Tensor/TPU acceleration, or Android accessibility/UI automation is included.

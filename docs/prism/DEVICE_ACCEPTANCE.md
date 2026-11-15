# PRISM independent physical-device acceptance

Status: **NOT RUN** — no connected Android device or compatible ARM64 emulator. Instrumentation assembly and Robolectric results are separate evidence.

Manus must record hardware/model/API/window dp/font scale, commit, installed signer/version, steps, actual outcome and screenshot/video references for each item. Use a conventional compact ARM64 phone and a real foldable, both orientations, folded/unfolded, split-screen, small/large font and TalkBack.

| Area | Required acceptance | Current result |
|---|---|---|
| Navigation | All nine sections; rail overflow; Sections menu; back/dismiss; system/cutout/gesture areas | Not run |
| State | Fold/resize/rotate/IME/process restoration preserves drafts/selection/history; never duplicate tools/inference/resume | Host draft round-trip/constrained-width only; hardware not run |
| Chat/models | Real connection and denied permission; model select/import/load/unload/delete; composer/full draft/Send/Stop/copy/history | Read-only host rendering only; real services not run |
| Agents/workflows | Create/edit/select/configure/import context; execute/cancel/output; invalid/empty prerequisites | Read-only host rendering only; real execution not run |
| Autonomy/results | Success/failure/pause/cancel/needs-attention, historical reopen while another mission runs, empty inspection/test failure/verify artifact/development/long result, task/log controls | Pure projection and UI callback tests; full real mission not run |
| Files | Original-objective ownership, removed/tampered files, preview/save/overwrite/share recipient, app-private paths, retained source URLs | Existing JVM gates and disabled UI actions; real SAF/share not run |
| Termux/Ollama | Host/guest environment, managed recovery/reconcile/ownership, permission denial, native inference, no replay after resize | Existing engine regressions; real device not run |
| Arsenal/Gateways | Search/clear; import/procedures/config/enable/remove; server credentials/allowlists/health/tool invocation/delete | Host search state only; live tool/network not run |
| Performance/Settings | Actual hardware values, modes/calibration consent; every group, secret drafts, grants/timeouts/repair/emergency stop | Read-only/group host checks; real configuration not run |
| Upgrade | Install accepted signer over public v0.9.2.1; keep data; no Room migration; uninstall-free update | Not run; local key is incompatible and must not be used |

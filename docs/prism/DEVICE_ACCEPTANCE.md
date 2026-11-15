# PRISM device and emulator acceptance

Status: **PARTIAL — emulator navigation, adaptive layout and install-over-upgrade passed; physical hardware and live services were not run.**

Manus exercised the exact release APK (`ec24559247a33efc62314b0f43ccc306ca91eac8d151b5d1f7cddfc19e98bbb7`) on an Android API 30 x86_64 Google APIs AVD with `libndk_translation` ARM64 translation. The compact window was 360×640 px at 160 dpi (360 dp wide); the expanded window was 1000×800; the large-text pass used font scale 1.7. Google services were disabled to reduce background load, animations were off, and `hide_error_dialogs=1`. Captures are in [`emulator-acceptance/`](emulator-acceptance/). This is emulator evidence, not a physical ARM64 phone or foldable.

| Area | Required acceptance | Current result |
|---|---|---|
| Navigation | All nine sections; rail overflow; Sections menu; back/dismiss; system/cutout/gesture areas | **PASS on emulator:** all nine destinations rendered; expanded rail exposed all nine entries; compact bottom bar and Sections menu worked |
| State | Fold/resize/rotate/IME/process restoration preserves drafts/selection/history; never duplicate tools/inference/resume | **PARTIAL:** compact→expanded→compact resize and 170% text rendered; host draft round-trip passes. Real rotation, fold/unfold, IME, split-screen and process-death restoration not run |
| Chat/models | Real connection and denied permission; model select/import/load/unload/delete; composer/full draft/Send/Stop/copy/history | **NOT RUN live services:** Chat and Models screens render; no Ollama connection, model import/load or send/stop |
| Agents/workflows | Create/edit/select/configure/import context; execute/cancel/output; invalid/empty prerequisites | **NOT RUN execution:** Agents and Workflows screens render; no real agent or workflow run |
| Autonomy/results | Success/failure/pause/cancel/needs-attention, historical reopen while another mission runs, empty inspection/test failure/verify artifact/development/long result, task/log controls | **NOT RUN real mission:** pure projection and UI callback tests pass; Autonomy screen renders |
| Files | Original-objective ownership, removed/tampered files, preview/save/overwrite/share recipient, app-private paths, retained source URLs | **NOT RUN:** existing JVM ownership gates pass; no real SAF/share recipient or file action |
| Termux/Ollama | Host/guest environment, managed recovery/reconcile/ownership, permission denial, native inference, no replay after resize | **NOT RUN:** existing engine regressions pass; no real Termux/PRoot/Ollama/native inference |
| Arsenal/Gateways | Search/clear; import/procedures/config/enable/remove; server credentials/allowlists/health/tool invocation/delete | **RENDER ONLY:** screens and local search render; no live gateway/tool/network |
| Performance/Settings | Actual hardware values, modes/calibration consent; every group, secret drafts, grants/timeouts/repair/emergency stop | **RENDER ONLY:** Performance modes and Settings groups render; no real configuration change |
| Upgrade | Install accepted signer over public v0.9.2.1; keep data; no Room migration; uninstall-free update | **PASS on emulator:** public v0.9.2.1 (code 26) → v0.9.3 (code 27) via `pm install -r`; same data directory and `files/upgrade-marker.txt` survived; app launched |

The emulator produced occasional system/System UI ANRs under ARM translation and a stale top-edge pixel artifact when resizing from the expanded rail to the compact window. These are recorded as emulator limitations, not production assertions. The compiled `PrismScreensInstrumentedTest` suite passed **12/12 tests in 272.6 s** at default font scale on this emulator. The earlier 170% run had one missing-node failure in the Autonomy draft test; resetting to default text size resolved it. See [TEST_RESULTS.md](../../TEST_RESULTS.md).
